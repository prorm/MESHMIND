@file:OptIn(ExperimentalLayoutApi::class)

package dev.meshmind.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import dev.meshmind.core.ai.ClassificationInput
import dev.meshmind.core.ai.RuleBasedPriorityClassifier
import dev.meshmind.core.model.NodeId
import dev.meshmind.core.model.PacketType
import dev.meshmind.core.model.Priority
import dev.meshmind.core.routing.MeshSnapshot
import dev.meshmind.core.routing.Router
import dev.meshmind.core.routing.TransferProgress
import kotlinx.coroutines.launch
import kotlin.random.Random

private val previewClassifier = RuleBasedPriorityClassifier()

@Composable
fun DestinationPicker(snap: MeshSnapshot, selected: String, allowEveryone: Boolean = false, onSelect: (String) -> Unit) {
    val options = buildList {
        if (allowEveryone) add("*")
        addAll(snap.knownNodes.filter { it != snap.selfId })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { id ->
            FilterChip(selected = selected == id, onClick = { onSelect(id) }, label = { Text(if (id == "*") "Everyone" else id) })
        }
    }
    OutlinedTextField(
        if (selected == "*") "" else selected, { onSelect(it.trim()) },
        label = { Text("…or type a node ID") }, singleLine = true, keyboardOptions = IdKeyboard, modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun TransferRows(transfers: List<TransferProgress>) {
    transfers.take(4).forEach { t ->
        val f = if (t.total == 0) 0f else t.done.toFloat() / t.total
        Text(
            "${if (t.outgoing) "→ ${t.peer}" else "← ${t.peer}"} ${t.name} ${t.totalBytes / 1024} KB: ${t.done}/${t.total} chunks" +
                (t.completedAt?.let { " ✓ ${it - t.startedAt} ms" } ?: ""),
            style = Mono,
        )
        LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun MessagesScreen(router: Router?, snap: MeshSnapshot) {
    val scope = rememberCoroutineScope()
    var dest by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("Meet me at Gate C") }
    val cls = remember(text) { previewClassifier.classify(ClassificationInput(PacketType.TEXT, text, text.toByteArray().size)) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        if (router == null) Text("Mesh is stopped. Start it on Home first.", color = MeshColors.sos, modifier = Modifier.padding(8.dp))
        Section("Send text") {
            Text("To", fontWeight = FontWeight.SemiBold)
            DestinationPicker(snap, dest) { dest = it }
            OutlinedTextField(text, { text = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Classifier:")
                PriorityPill(cls.priority, cls.label)
            }
            Text(cls.reasons.joinToString(), style = Mono, color = MeshColors.muted)
            Button(
                enabled = router != null && dest.isNotBlank() && dest != snap.selfId && text.isNotBlank(),
                onClick = { scope.launch { router?.sendText(dest, text) } },
            ) { Text("Send") }
        }

        Section("Large transfer (chunked, LOW priority)") {
            Text("Sends random bytes as 16 KB chunks. Press SOS while it runs to see preemption.", color = MeshColors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(128, 512, 1024).forEach { kb ->
                    OutlinedButton(
                        enabled = router != null && dest.isNotBlank() && dest != "*" && dest != snap.selfId,
                        onClick = { scope.launch { router?.sendTransfer(dest, "test-$kb.bin", Random.nextBytes(kb * 1024), Priority.LOW) } },
                    ) { Text("$kb KB") }
                }
            }
            TransferRows(snap.transfers)
        }

        Section("Sent") {
            val sent = snap.tracked.filter { it.type == PacketType.TEXT }
            if (sent.isEmpty()) Text("Nothing sent yet.", color = MeshColors.muted)
            sent.take(20).forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("→ ${t.destination}", fontWeight = FontWeight.Bold)
                    PriorityPill(t.priority, t.label)
                }
                Text(t.preview)
                RouteLine(t, snap.selfId)
                HorizontalDivider()
            }
        }

        Section("Received") {
            val got = snap.inbox
            if (got.isEmpty()) Text("No messages yet.", color = MeshColors.muted)
            got.take(30).forEach { m -> InboxRow(m.from, m.text, m.priority, m.label, m.route, m.hopCount, m.latencyMs, m.type == PacketType.SOS, m.trust) }
        }
        Text("* one-way latency uses two phones' clocks; ack RTT uses one clock and is the reliable number.", style = Mono, color = MeshColors.muted)
    }
}

@Composable
fun InboxRow(from: NodeId, text: String, priority: Priority, label: String, route: List<NodeId>, hops: Int, latency: Long, sos: Boolean, trust: dev.meshmind.core.security.TrustLevel = dev.meshmind.core.security.TrustLevel.UNSIGNED) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${if (sos) "🚨 " else ""}$from", fontWeight = FontWeight.Bold)
            PriorityPill(priority, label)
            SenderTrust(trust)
        }
        Text(text)
        Text("${route.joinToString(" → ")} · $hops hop(s) · $latency ms", style = Mono, color = MeshColors.muted)
    }
    HorizontalDivider()
}
