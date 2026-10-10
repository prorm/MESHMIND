@file:OptIn(ExperimentalLayoutApi::class)

package dev.meshmind.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.meshmind.app.sim.SimulationController

/** Zig-zag layout so the optional shortcut edges (A–C, B–D) don't overlap the main line. */
private val simPositions = mapOf(
    "A" to Offset(0.1f, 0.3f), "B" to Offset(0.3f, 0.7f), "C" to Offset(0.5f, 0.3f),
    "D" to Offset(0.7f, 0.7f), "E" to Offset(0.9f, 0.3f),
)

@Composable
fun SimulationScreen(sim: SimulationController) {
    val st by sim.state.collectAsStateWithLifecycle()
    val hlEdges = st.highlight.zipWithNext().map { (a, b) -> if (a < b) a to b else b to a }.toSet()

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Section("Virtual mesh A–E (real Router code, SimulatedTransport)") {
                GraphCanvas(
                    st.nodes, simPositions,
                    st.edges.map { GraphEdge(it.a, it.b, up = it.up, highlighted = it.up && (it.a to it.b) in hlEdges, direct = true) }
                        .filter { it.up || it.a to it.b in setOf("A" to "B", "B" to "C", "C" to "D", "D" to "E") },
                    style = { id ->
                        val s = st.snapshots[id]
                        val q = s?.queued?.size ?: 0
                        NodeStyle(
                            if (id in st.gateways) MeshColors.gateway else MeshColors.ok,
                            if (id in st.highlight) MeshColors.warn else null,
                            buildString {
                                if (id in st.gateways) append("🌐 ")
                                append("in ${s?.inbox?.size ?: 0}")
                                if (q > 0) append(" · q $q")
                            },
                        )
                    },
                )
                if (st.caption.isNotEmpty()) Text(st.caption, style = Mono, color = MeshColors.ok)
            }
            Section("Edges (tap to cut ✕ / restore)") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    st.edges.forEach { e ->
                        FilterChip(e.up, { sim.toggleEdge(e.a, e.b) }, label = { Text("${e.a}–${e.b} ${if (e.up) "●" else "✕"}") })
                    }
                }
                Text("Internet gateway (fake Internet)", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sim.ids.forEach { id -> FilterChip(id in st.gateways, { sim.toggleGateway(id) }, label = { Text("🌐 $id") }) }
                }
            }
            Section("Scenarios") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { sim.sendText("A", "C", "Meet me at Gate C") }) { Text("Text A→C") }
                    Button(onClick = { sim.sendText("A", "E", "Hello E, 4 hops away") }) { Text("Text A→E") }
                    Button(onClick = { sim.requestUrl("A") }, colors = ButtonDefaults.buttonColors(containerColor = MeshColors.gateway)) { Text("🌐 A: GET via gateway") }
                    OutlinedButton(onClick = { sim.startTransfer("A", "E") }) { Text("📦 256 KB A→E (LOW)") }
                    Button(onClick = { sim.sendSos("A", "E") }, colors = ButtonDefaults.buttonColors(containerColor = MeshColors.sos)) { Text("🚨 MEDICAL SOS A→E") }
                    OutlinedButton(onClick = { sim.reset() }) { Text("Reset") }
                }
                Text(
                    "Try: cut C–D, send Text A→E (parks on C), restore C–D (delivered). Or mark D as gateway, GET from A. " +
                        "Or start the 256 KB transfer, then hit SOS: it overtakes the chunks.",
                    color = MeshColors.muted,
                )
                st.snapshots["A"]?.transfers?.let { TransferRows(it) }
            }
            Section("Ask, fair share & live info") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { sim.ask("A", "Is the metro running?") }, colors = ButtonDefaults.buttonColors(containerColor = MeshColors.gateway)) { Text("🔎 A: metro?") }
                    Button(onClick = { sim.ask("C", "metro running now") }) { Text("🔎 C: same (cache)") }
                    Button(onClick = { sim.crowdRush() }, colors = ButtonDefaults.buttonColors(containerColor = MeshColors.warn)) { Text("🌊 Crowd rush") }
                    OutlinedButton(onClick = { sim.broadcastLiveInfo() }) { Text("📢 Live info") }
                    OutlinedButton(onClick = { sim.organizerNotice() }) { Text("📢 D: organizer notice") }
                }
                Text(
                    "Ask once from A, then the same from C: C's answer comes from a phone's cache, not the Internet. " +
                        "Crowd rush: B spams fake \"urgent\" questions; watch the gateway serve A's first-aid question first.",
                    color = MeshColors.muted,
                )
                val now = System.currentTimeMillis()
                st.snapshots["A"]?.bulletins?.takeIf { it.isNotEmpty() }?.let { LiveInfo(it, now, null) }
                st.snapshots.values.firstNotNullOfOrNull { it.gatewayQueue }?.let { GatewayQueueView(it) }
                listOf("A", "C").forEach { id ->
                    st.snapshots[id]?.inet?.filter { it.answer != null }?.take(3)?.forEach { r ->
                        Text("$id ← ${r.answer!!.text.take(80)}", style = Mono)
                        Text("   ${if (r.answerVerified) "✓ signed" else "⚠ unverified"} · ${r.servedFrom} · need ${r.response?.need}", style = Mono, color = MeshColors.muted)
                    }
                }
            }
            Section("Per-node") {
                st.nodes.forEach { id ->
                    val s = st.snapshots[id] ?: return@forEach
                    Text(
                        "$id fwd=${s.stats.packetsForwarded} dlv=${s.stats.deliveredToMe} dup=${s.stats.duplicatesRejected} q=${s.queued.size} " +
                            "gw=${s.nearestGateway?.let { "${it.gatewayId}@${it.hopDistance}" } ?: "-"}",
                        style = Mono,
                    )
                }
            }
            Text("Log (newest first)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 4.dp))
        }
        items(st.log.take(300)) { line -> Text(line, style = Mono) }
    }
}
