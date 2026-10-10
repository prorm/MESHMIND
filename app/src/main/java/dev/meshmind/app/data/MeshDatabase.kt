package dev.meshmind.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import dev.meshmind.core.model.Packet
import dev.meshmind.core.model.PacketState
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.model.StoredPacket
import dev.meshmind.core.routing.PacketStore
import dev.meshmind.core.routing.StartupLoad

@Entity(tableName = "packets")
class PacketEntity(
    @PrimaryKey val packetId: String,
    val sourceId: String,
    val destinationId: String,
    val type: String,
    val priority: String,
    val createdAt: Long,
    val expiresAt: Long,
    val ttl: Int,
    val hopCount: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    val route: String,
    val label: String,
    val state: String,
    val updatedAt: Long,
    val origin: Boolean,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val signature: ByteArray?,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val cert: ByteArray?,
)

@Entity(tableName = "acked")
class AckedEntity(@PrimaryKey val packetId: String, val at: Long)

@Dao
interface PacketDao {
    @Query("SELECT * FROM packets ORDER BY createdAt")
    suspend fun all(): List<PacketEntity>

    /** Startup: rows the router keeps in memory (in flight, or shown in the UI). Mirrors keepInMemoryAtStartup. */
    @Query("SELECT * FROM packets WHERE state IN ('QUEUED','FORWARDING') OR type IN ('TEXT','SOS','INET_REQUEST') ORDER BY createdAt")
    suspend fun startupRows(): List<PacketEntity>

    /** Startup: finished bulk/control rows are only needed as IDs (duplicate detection); payloads stay on disk. */
    @Query("SELECT packetId FROM packets WHERE state NOT IN ('QUEUED','FORWARDING') AND type NOT IN ('TEXT','SOS','INET_REQUEST')")
    suspend fun finishedBulkIds(): List<String>

    @Upsert
    suspend fun upsert(e: PacketEntity)

    @Query("UPDATE packets SET state = :state, updatedAt = :at WHERE packetId = :id")
    suspend fun updateState(id: String, state: String, at: Long)

    @Query("SELECT packetId FROM acked ORDER BY at")
    suspend fun ackedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAcked(e: AckedEntity)

    @Query("DELETE FROM packets")
    suspend fun clearPackets()

    @Query("DELETE FROM acked")
    suspend fun clearAcked()

    /** Housekeeping: forget relayed packets that are finished and old. */
    @Query("DELETE FROM packets WHERE origin = 0 AND state IN ('DELIVERED','EXPIRED') AND updatedAt < :before")
    suspend fun pruneFinished(before: Long)

    /** Housekeeping: finished chunks/ACKs/responses (any origin) have no UI value once a few hours old. */
    @Query("DELETE FROM packets WHERE state IN ('DELIVERED','EXPIRED') AND type NOT IN ('TEXT','SOS','INET_REQUEST') AND updatedAt < :before")
    suspend fun pruneFinishedBulk(before: Long)

    @Query("DELETE FROM acked WHERE at < :before")
    suspend fun pruneAcked(before: Long)
}

/** v2: packets carry their origin's signature and certificate (protocol v2); v1 rows are dropped on upgrade. */
@Database(entities = [PacketEntity::class, AckedEntity::class], version = 2, exportSchema = false)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun dao(): PacketDao

    companion object {
        fun build(context: Context): MeshDatabase =
            Room.databaseBuilder(context.applicationContext, MeshDatabase::class.java, "meshmind.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

/** Room-backed store-and-forward buffer: packets survive app restarts and phone reboots. */
class RoomPacketStore(private val dao: PacketDao) : PacketStore {
    override suspend fun loadAll(): List<StoredPacket> {
        dao.pruneFinished(System.currentTimeMillis() - 48 * 3_600_000L)
        dao.pruneAcked(System.currentTimeMillis() - 72 * 3_600_000L)
        return dao.all().mapNotNull { it.toStored() }
    }

    override suspend fun loadStartup(): StartupLoad {
        val now = System.currentTimeMillis()
        dao.pruneFinished(now - 48 * 3_600_000L)
        dao.pruneFinishedBulk(now - 6 * 3_600_000L)
        dao.pruneAcked(now - 72 * 3_600_000L)
        return StartupLoad(dao.startupRows().mapNotNull { it.toStored() }, dao.finishedBulkIds())
    }

    override suspend fun upsert(packet: StoredPacket) = dao.upsert(packet.toEntity())
    override suspend fun updateState(packetId: String, state: PacketState, at: Long) = dao.updateState(packetId, state.name, at)
    override suspend fun loadAckedIds(): List<String> = dao.ackedIds()
    override suspend fun addAcked(packetId: String, at: Long) = dao.insertAcked(AckedEntity(packetId, at))
    override suspend fun clear() { dao.clearPackets(); dao.clearAcked() }

    private fun StoredPacket.toEntity() = PacketEntity(
        packet.packetId, packet.sourceId, packet.destinationId, packet.type.name, packet.priority.name,
        packet.createdAt, packet.expiresAt, packet.ttl, packet.hopCount, packet.payload,
        packet.routeHistory.joinToString(","), packet.label, state.name, updatedAt, origin, packet.signature, packet.cert,
    )

    private fun PacketEntity.toStored(): StoredPacket? = runCatching {
        StoredPacket(
            Packet(
                packetId, sourceId, destinationId, PacketType.valueOf(type), Priority.valueOf(priority),
                createdAt, expiresAt, ttl, hopCount, payload,
                if (route.isEmpty()) emptyList() else route.split(","), label, signature, cert,
            ),
            PacketState.valueOf(state), updatedAt, origin,
        )
    }.getOrNull()
}
