@file:OptIn(ExperimentalLayoutApi::class)

package dev.meshmind.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.meshmind.core.model.BulletinItem
import dev.meshmind.core.model.BulletinKind
import dev.meshmind.core.routing.BulletinView
import dev.meshmind.core.security.Role
import dev.meshmind.core.security.TrustLevel

@Composable
fun TrustBadge(level: TrustLevel, role: Role = Role.USER) = when (level) {
    TrustLevel.VERIFIED -> Pill(if (role == Role.USER) "✓ Verified" else "✓ ${role.name.lowercase().replaceFirstChar { it.uppercase() }}", MeshColors.ok)
    TrustLevel.SELF_SIGNED -> Pill("Unverified ID", MeshColors.warn)
    TrustLevel.UNSIGNED -> Pill("Unsigned", MeshColors.sos)
    TrustLevel.INVALID -> Pill("Invalid", MeshColors.sos)
}

fun severityColor(severity: Int): Color = when (severity) {
    3 -> MeshColors.sos
    2 -> Color(0xFFE5732E)
    1 -> MeshColors.warn
    else -> MeshColors.gateway
}

fun kindIcon(kind: BulletinKind): String = when (kind) {
    BulletinKind.ALERT -> "⚠️"
    BulletinKind.WEATHER -> "⛅"
    BulletinKind.NOTICE -> "📢"
    BulletinKind.TRANSPORT -> "🚇"
    BulletinKind.INFO -> "ℹ️"
}

fun ago(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000} min ago"
    else -> "${ms / 3_600_000} h ago"
}

/** One live-info item with a severity bar on the left. */
@Composable
fun BulletinItemCard(item: BulletinItem, board: BulletinView, now: Long) {
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(severityColor(item.severity)))
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(kindIcon(item.kind), fontSize = 18.sp)
                    Text(item.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
                if (item.body.isNotBlank()) Text(item.body, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    when {
                        board.official -> Pill("✓ Official", MeshColors.ok)
                        board.viaGateway -> Pill("via gateway ${board.from.substringBefore('/')}", MeshColors.gateway)
                        else -> Pill("✓ ${board.signerRole.name.lowercase()} · ${board.bulletin.boardId}", MeshColors.ok)
                    }
                    Text("${item.source} · ${ago(now - item.issuedAt)}", style = MaterialTheme.typography.labelSmall, color = MeshColors.muted)
                }
            }
        }
    }
}

@Composable
fun LiveInfo(bulletins: List<BulletinView>, now: Long, emptyText: String?) {
    val items = bulletins.flatMap { b -> b.bulletin.items.filter { it.expiresAt > now }.map { it to b } }
        .sortedWith(compareByDescending<Pair<BulletinItem, BulletinView>> { it.first.severity }.thenByDescending { it.first.issuedAt })
    if (items.isEmpty()) {
        emptyText?.let { Text(it, color = MeshColors.muted, style = MaterialTheme.typography.bodyMedium) }
        return
    }
    items.take(6).forEach { (item, board) -> BulletinItemCard(item, board, now) }
}
