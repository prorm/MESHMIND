package dev.meshmind.app.ui

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.model.Priority
import dev.meshmind.core.routing.TrackStatus
import dev.meshmind.core.routing.TrackedPacket

object MeshColors {
    val ok = Color(0xFF2E9E6B)
    val warn = Color(0xFFE0A100)
    val sos = Color(0xFFD7263D)
    val gateway = Color(0xFF2F6FDB)
    val muted = Color(0xFF8A9499)
    fun priority(p: Priority) = when (p) {
        Priority.CRITICAL -> sos
        Priority.HIGH -> Color(0xFFE5732E)
        Priority.NORMAL -> gateway
        Priority.LOW -> muted
    }
}

@Composable
fun MeshTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFF7FD1C7), secondary = Color(0xFF9DB8FF), surface = Color(0xFF111A1D), background = Color(0xFF0B1214))
    } else {
        lightColorScheme(primary = Color(0xFF0E5E66), secondary = Color(0xFF2F6FDB), background = Color(0xFFF4F6F6))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** For node IDs / names: no autocorrect or predictive text mangling the value. */
val IdKeyboard = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done)

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

@Composable
fun Section(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Box(
        Modifier.background(color.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun PriorityPill(p: Priority, label: String = "") =
    Pill(if (label.isNotEmpty() && label != p.name) "${p.name} · $label" else p.name, MeshColors.priority(p))

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MeshColors.muted)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = valueColor)
    }
}

/** "A → B ✓  B → C ✓  DELIVERED" for a tracked packet. */
@Composable
fun RouteLine(t: TrackedPacket, selfId: NodeId) {
    val (text, color) = when {
        t.status == TrackStatus.DELIVERED && t.deliveredRoute.size >= 2 ->
            t.deliveredRoute.zipWithNext().joinToString("  ") { "${it.first} → ${it.second} ✓" } + "  DELIVERED" to MeshColors.ok
        t.status == TrackStatus.DELIVERED -> "DELIVERED" to MeshColors.ok
        t.status == TrackStatus.EXPIRED -> "EXPIRED (TTL/lifetime ran out)" to MeshColors.sos
        t.firstHops.isNotEmpty() -> t.firstHops.joinToString("  ") { "$selfId → $it ✓" } + "  … in transit / stored on relays" to MeshColors.warn
        else -> "QUEUED: no peer yet, held here (store-and-forward)" to MeshColors.muted
    }
    Text(text, style = Mono, color = color)
    if (t.status == TrackStatus.DELIVERED) {
        val extra = buildList {
            add("${t.hopCount} hop(s)")
            t.rttMs?.let { add("ack rtt $it ms") }
            t.oneWayMs?.let { add("one-way $it ms*") }
            if (t.confirmedBy.isNotEmpty()) add("confirmed by ${t.confirmedBy.joinToString()}")
        }
        Text(extra.joinToString(" · "), style = Mono, color = MeshColors.muted)
    }
}

data class GraphEdge(val a: NodeId, val b: NodeId, val up: Boolean = true, val highlighted: Boolean = false, val direct: Boolean = false)

data class NodeStyle(val fill: Color, val ring: Color? = null, val caption: String = "")

/** Minimal node/edge diagram; positions are fractions of the canvas (0..1). */
@Composable
fun GraphCanvas(
    nodes: List<NodeId>,
    positions: Map<NodeId, Offset>,
    edges: List<GraphEdge>,
    style: (NodeId) -> NodeStyle,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(240.dp)) {
        fun at(id: NodeId): Offset? = positions[id]?.let { Offset(it.x * size.width, it.y * size.height) }
        val r = 22.dp.toPx()
        for (e in edges) {
            val p1 = at(e.a) ?: continue
            val p2 = at(e.b) ?: continue
            val color = when {
                !e.up -> MeshColors.sos.copy(alpha = 0.6f)
                e.highlighted -> MeshColors.ok
                e.direct -> onSurface.copy(alpha = 0.7f)
                else -> onSurface.copy(alpha = 0.3f)
            }
            drawLine(
                color, p1, p2, strokeWidth = if (e.highlighted) 6.dp.toPx() else 2.5.dp.toPx(),
                pathEffect = if (e.up) null else PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
            )
            if (!e.up) {
                val mid = Offset((p1.x + p2.x) / 2, (p1.y + p2.y) / 2)
                val tl = measurer.measure("✕", TextStyle(color = MeshColors.sos, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                drawText(tl, topLeft = Offset(mid.x - tl.size.width / 2, mid.y - tl.size.height / 2))
            }
        }
        for (id in nodes) {
            val c = at(id) ?: continue
            val s = style(id)
            drawCircle(s.fill, r, c)
            s.ring?.let { drawCircle(it, r + 5.dp.toPx(), c, style = Stroke(3.dp.toPx())) }
            val label = measurer.measure(id.substringBefore('-').take(6), TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold))
            drawText(label, topLeft = Offset(c.x - label.size.width / 2, c.y - label.size.height / 2))
            if (s.caption.isNotEmpty()) {
                val cap = measurer.measure(s.caption, TextStyle(color = onSurface, fontSize = 10.sp))
                drawText(cap, topLeft = Offset(c.x - cap.size.width / 2, c.y + r + 6.dp.toPx()))
            }
        }
    }
}

@Composable
fun StatGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (k, v) ->
                    Column(
                        Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(v, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(k, style = MaterialTheme.typography.labelSmall, color = MeshColors.muted)
                    }
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

