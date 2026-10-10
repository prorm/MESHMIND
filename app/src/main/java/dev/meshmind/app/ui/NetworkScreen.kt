package dev.meshmind.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.meshmind.app.MeshRuntime
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import dev.meshmind.core.routing.MeshSnapshot
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NetworkScreen(runtime: MeshRuntime, snap: MeshSnapshot) {
    val lock by runtime.peerFilter.collectAsStateWithLifecycle()
    var lockText by remember(lock) { mutableStateOf(lock.sorted().joinToString(", ")) }
    val nodes = snap.knownNodes.ifEmpty { listOf(snap.selfId) }
    // Self in the centre when there are neighbours; everyone else on a circle.
    val others = nodes.filter { it != snap.selfId }
    val positions = buildMap {
        put(snap.selfId, Offset(0.5f, 0.5f))
        others.forEachIndexed { i, id ->
            val a = 2 * Math.PI * i / others.size.coerceAtLeast(1) - Math.PI / 2
            put(id, Offset(0.5f + 0.38f * cos(a).toFloat(), 0.5f + 0.38f * sin(a).toFloat()))
        }
    }
    val direct = snap.connectedPeers.toSet()
    val gateways = snap.gateways.map { it.gatewayId }.toSet()
    val edges = snap.edges.map { (a, b) ->
        GraphEdge(a, b, direct = (a == snap.selfId && b in direct) || (b == snap.selfId && a in direct))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        Section("Live mesh (direct links bold, learned links faint)") {
            GraphCanvas(nodes, positions, edges, style = { id ->
                when {
                    id == snap.selfId -> NodeStyle(MeshColors.ok, if (snap.isGateway) MeshColors.gateway else null, "me")
                    id in gateways -> NodeStyle(MeshColors.gateway, MeshColors.gateway, "gateway")
                    id in direct -> NodeStyle(MeshColors.ok.copy(alpha = 0.75f), caption = "peer")
                    else -> NodeStyle(MeshColors.muted)
                }
            })
            Text("Links further away are inferred from the route history of packets seen here.", style = Mono, color = MeshColors.muted)
        }
        Section("Counters") {
            val s = snap.stats
            StatGrid(
                listOf(
                    "peers" to snap.connectedPeers.size.toString(),
                    "gateways" to (if (snap.isGateway) "me" else snap.gateways.size.toString()),
                    "known nodes" to nodes.size.toString(),
                    "forwarded" to s.packetsForwarded.toString(),
                    "delivered" to s.deliveredToMe.toString(),
                    "dup rejected" to s.duplicatesRejected.toString(),
                    "queued" to snap.queued.size.toString(),
                    "KB sent" to (s.bytesSent / 1024).toString(),
                    "KB recv" to (s.bytesReceived / 1024).toString(),
                ),
            )
        }
        Section("Peers") {
            if (snap.peers.isEmpty()) Text("No direct peers.", color = MeshColors.muted)
            snap.peers.forEach { p ->
                Text("${p.nodeId ?: "(handshaking)"} · endpoint ${p.endpointId} · queue ${p.queued} · sent ${p.framesSent} frames / ${p.bytesSent / 1024} KB", style = Mono)
            }
        }
        Section("Topology lock (testing aid)") {
            Text(
                "Phones on one table all hear each other. To force a line A↔B↔C↔D, set A: \"B\", B: \"A, C\", C: \"B, D\", D: \"C\". " +
                    "Names are matched on the part before '-'. Empty = link with anyone.",
                color = MeshColors.muted,
            )
            OutlinedTextField(lockText, { lockText = it }, label = { Text("Only link with") }, singleLine = true, keyboardOptions = IdKeyboard, modifier = Modifier.fillMaxWidth())
            Button(onClick = { runtime.setPeerFilter(lockText.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()) }) {
                Text(if (lock.isEmpty()) "Apply (currently: anyone)" else "Apply (currently: ${lock.joinToString()})")
            }
        }
        Section("Gateway table") {
            if (snap.gateways.isEmpty()) Text("Empty.", color = MeshColors.muted)
            snap.gateways.forEach { g ->
                Text("${g.gatewayId}  dist=${g.hopDistance}  via=${g.viaPeer}  expires in ${(g.expiresAt - System.currentTimeMillis()) / 1000}s", style = Mono)
            }
        }
    }
}
