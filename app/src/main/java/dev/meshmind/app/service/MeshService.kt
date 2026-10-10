package dev.meshmind.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.meshmind.app.MeshMindApp
import dev.meshmind.app.R
import dev.meshmind.app.ui.MainActivity
import dev.meshmind.core.model.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/**
 * Foreground service (type connectedDevice) so advertising/discovery and store-and-forward keep running when the
 * app is in the background or the screen is off. Without it Android would kill Nearby activity within minutes.
 */
class MeshService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runtime get() = (application as MeshMindApp).runtime
    private val notifiedSos = HashSet<String>()
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runtime.launch { runtime.stopMesh() }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Starting mesh…"), type)
        runtime.launch { runtime.startMesh() }
        if (observing) return START_STICKY
        observing = true

        scope.launch {
            runtime.router.filterNotNull().flatMapLatest { r ->
                combine(r.state, runtime.transportStatus) { s, t -> s to t }
            }.collectLatest { (s, t) ->
                val gw = s.nearestGateway
                val text = "${s.selfId} · peers ${s.connectedPeers.size} · nearby ${t.discovered} · " +
                    (if (s.isGateway) "GATEWAY" else if (gw != null) "gateway ${gw.hopDistance} hops" else "no gateway")
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
                s.inbox.filter { it.type == PacketType.SOS && notifiedSos.add(it.packetId) }.forEach { m ->
                    notifySos(m.from, m.text, m.hopCount)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        runtime.launch { runtime.stopMesh() }
        super.onDestroy()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_STATUS, "Mesh status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_SOS, "SOS alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun contentIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun buildNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MeshService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle("MeshMind mesh running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun notifySos(from: String, text: String, hops: Int) {
        val n = NotificationCompat.Builder(this, CH_SOS)
            .setSmallIcon(R.drawable.ic_stat_mesh)
            .setContentTitle("🚨 SOS from $from ($hops hops)")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentIntent())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(from.hashCode(), n) }
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val CH_STATUS = "mesh_status"
        private const val CH_SOS = "mesh_sos"
        private const val ACTION_STOP = "dev.meshmind.STOP"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java))
        fun stop(context: Context) = context.startService(Intent(context, MeshService::class.java).setAction(ACTION_STOP))
    }
}
