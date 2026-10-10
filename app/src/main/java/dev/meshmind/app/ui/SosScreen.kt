package dev.meshmind.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetRequest
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.routing.MeshSnapshot
import dev.meshmind.core.routing.Router
import kotlinx.coroutines.launch

@Composable
fun SosScreen(router: Router?, snap: MeshSnapshot) {
    val scope = rememberCoroutineScope()
    var description by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("*") }
    var viaInternet by remember { mutableStateOf(false) }

    fun fire(prefix: String) {
        val r = router ?: return
        val text = listOf(prefix, description.trim()).filter { it.isNotEmpty() }.joinToString(": ")
        scope.launch {
            r.sendSos(text, dest.ifBlank { "*" })
            if (viaInternet) r.sendInternetRequest(InetRequest(InetKind.WEBHOOK_POST, "", "SOS from ${snap.selfId}: $text"), sos = true)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        if (router == null) Text("Mesh is stopped. Start it on Home first.", color = MeshColors.sos, modifier = Modifier.padding(8.dp))
        Section("Emergency") {
            Text("SOS is always CRITICAL: it jumps ahead of every queued packet on every hop.", color = MeshColors.muted)
            OutlinedTextField(description, { description = it }, label = { Text("Description (optional)") }, modifier = Modifier.fillMaxWidth())
            Text("Send to", fontWeight = FontWeight.SemiBold)
            DestinationPicker(snap, dest, allowEveryone = true) { dest = it }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(viaInternet, { viaInternet = it })
                Text("Also post to the gateway's webhook (needs a gateway in the mesh)")
            }
            Button(
                onClick = { fire("MEDICAL") }, enabled = router != null,
                colors = ButtonDefaults.buttonColors(containerColor = MeshColors.sos),
                modifier = Modifier.fillMaxWidth().height(72.dp),
            ) { Text("🚨 MEDICAL SOS", fontSize = 22.sp, fontWeight = FontWeight.Black) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("FIRE", "LOST", "TRAPPED").forEach { kind ->
                    Button(onClick = { fire(kind) }, enabled = router != null, colors = ButtonDefaults.buttonColors(containerColor = MeshColors.sos.copy(alpha = 0.8f))) {
                        Text("🚨 $kind")
                    }
                }
            }
        }
        if (snap.transfers.any { it.completedAt == null }) {
            Section("Transfers in progress (watch them pause and resume)") { TransferRows(snap.transfers) }
        }
        Section("SOS sent") {
            val sent = snap.tracked.filter { it.type == PacketType.SOS || (it.type == PacketType.INET_REQUEST && it.label == "SOS") }
            if (sent.isEmpty()) Text("None.", color = MeshColors.muted)
            sent.take(10).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (t.type == PacketType.SOS) "→ ${if (t.destination == "*") "everyone" else t.destination}" else "→ webhook via gateway", fontWeight = FontWeight.Bold)
                    PriorityPill(t.priority, t.label)
                }
                Text(t.preview)
                RouteLine(t, snap.selfId)
                HorizontalDivider()
            }
        }
        Section("SOS received") {
            val got = snap.inbox.filter { it.type == PacketType.SOS }
            if (got.isEmpty()) Text("None.", color = MeshColors.muted)
            got.forEach { m -> InboxRow(m.from, m.text, m.priority, m.label, m.route, m.hopCount, m.latencyMs, true, m.trust) }
        }
    }
}
