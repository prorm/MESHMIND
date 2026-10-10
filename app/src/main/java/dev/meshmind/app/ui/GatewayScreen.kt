@file:OptIn(ExperimentalLayoutApi::class)

package dev.meshmind.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.meshmind.app.MeshRuntime
import dev.meshmind.core.gateway.GatewayQueueSnapshot
import dev.meshmind.core.gateway.NeedCategory
import dev.meshmind.core.gateway.QueueItemView
import dev.meshmind.core.routing.MeshSnapshot
import dev.meshmind.core.routing.Router
import dev.meshmind.core.security.Role
import kotlinx.coroutines.launch

fun categoryColor(c: NeedCategory): Color = when (c) {
    NeedCategory.EMERGENCY -> MeshColors.sos
    NeedCategory.HEALTH -> Color(0xFFE5732E)
    NeedCategory.SAFETY -> MeshColors.warn
    NeedCategory.FAMILY -> Color(0xFF9B59B6)
    NeedCategory.TRANSPORT -> MeshColors.gateway
    NeedCategory.INFO -> Color(0xFF16A085)
    NeedCategory.LEISURE -> MeshColors.muted
}

/** Sharing this phone's Internet: consent, budget, the live fair-share queue, and organizer tools. */
@Composable
fun GatewayScreen(runtime: MeshRuntime, router: Router?, snap: MeshSnapshot) {
    val scope = rememberCoroutineScope()
    val settings by runtime.gatewaySettings.collectAsStateWithLifecycle()
    val validated by runtime.internet.validated.collectAsStateWithLifecycle()
    val backendUrl by runtime.backendUrl.collectAsStateWithLifecycle()
    var advanced by remember { mutableStateOf(false) }
    val active = runtime.isGatewayActive

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.size(4.dp))
        Card(colors = CardDefaults.cardColors(containerColor = (if (active) MeshColors.gateway else MeshColors.muted).copy(alpha = 0.12f)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (active) "📶" else "📴", fontSize = 30.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (active) "Sharing Internet with the crowd" else "Not sharing", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                active -> "Answers questions for nearby phones, most urgent first."
                                !validated && !settings.forceGateway -> "This phone has no Internet right now."
                                else -> "Turn on consent below to help people around you."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(settings.consent, { v -> runtime.updateGateway { it.copy(consent = v) } })
                    Text("  I agree to relay questions for nearby phones", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Budget: ${settings.perMinute} requests / minute", style = MaterialTheme.typography.labelLarge)
                Slider(value = settings.perMinute.toFloat(), onValueChange = { v -> runtime.updateGateway { it.copy(perMinute = v.toInt()) } },
                    valueRange = 5f..60f, steps = 10)
                OutlinedTextField(backendUrl, { runtime.setBackendUrl(it) }, label = { Text("MeshMind backend (answers + live info)") },
                    placeholder = { Text("http://192.168.1.20:8787") }, singleLine = true, keyboardOptions = IdKeyboard, modifier = Modifier.fillMaxWidth())
                if (backendUrl.isBlank()) Text("Without a backend this phone can only relay SOS webhooks and raw requests.", color = MeshColors.warn, style = MaterialTheme.typography.bodySmall)
                Text(
                    "Not an open proxy: questions go only to the MeshMind backend (answers come back signed), raw requests only to " +
                        "allowlisted HTTPS sites. SOS always goes first; everyone else shares the link fairly.",
                    color = MeshColors.muted, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        val q = snap.gatewayQueue
        if (q != null) {
            GatewayQueueView(q)
        } else if (active) {
            Text("Waiting for the first request…", color = MeshColors.muted)
        }

        if (active && backendUrl.isNotBlank()) {
            OutlinedButton(onClick = { scope.launch { runtime.refreshBulletin() } }, enabled = router != null) { Text("📢 Fetch & broadcast live info now") }
        }

        snap.identity?.takeIf { it.role != Role.USER }?.let { OrganizerPanel(router, it.role) }

        Text(if (advanced) "Hide advanced ▲" else "Advanced (demo & raw tools) ▼", color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { advanced = !advanced }.padding(vertical = 6.dp))
        if (advanced) {
            Section("Advanced") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(settings.forceGateway, { v -> runtime.updateGateway { it.copy(forceGateway = v) } })
                    Text("  Demo: act as gateway even without validated Internet")
                }
                OutlinedTextField(settings.webhookUrl, { v -> runtime.updateGateway { it.copy(webhookUrl = v.trim()) } },
                    label = { Text("SOS webhook (e.g. https://ntfy.sh/your-topic)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                var allow by remember { mutableStateOf(settings.allowlist.sorted().joinToString(", ")) }
                OutlinedTextField(allow, { allow = it }, label = { Text("Allowlisted domains for raw requests") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    runtime.updateGateway { s -> s.copy(allowlist = allow.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()) }
                }) { Text("Save allowlist") }
            }
        }
        Spacer(Modifier.size(24.dp))
    }
}

/** The live fair-share queue plus the crowd-needs bars (also shown in the simulator). */
@Composable
fun GatewayQueueView(q: GatewayQueueSnapshot) {
    QueuePanel(q)
    DemandPanel(q)
}

@Composable
private fun QueuePanel(q: GatewayQueueSnapshot) {
    Section("Fair-share queue") {
        StatGrid(listOf(
            "served" to q.served.toString(), "from cache" to q.fromCache.toString(), "deferred" to q.deferred.toString(),
            "waiting" to q.items.count { !it.running }.toString(), "this minute" to "${q.usedThisMinute}/${q.perMinute}",
            "capacity" to q.capacity.toString(),
        ))
        if (q.items.isEmpty()) Text("Queue empty: every request so far has been served.", color = MeshColors.muted)
        q.items.forEach { QueueRow(it) }
        if (q.recent.isNotEmpty()) {
            Text("Recently served", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
            q.recent.take(6).forEach { r ->
                Text("${r.requester} · ${r.category.label} ${r.need}/100 · waited ${"%.1f".format(r.waitedMs / 1000.0)} s · ${r.preview.take(40)}",
                    style = Mono, color = MeshColors.muted)
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueueItemView) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(if (item.bypass) "SOS" else if (item.running) "RUNNING" else "#${item.priority}", if (item.bypass) MeshColors.sos else categoryColor(item.category))
            Text(item.requester, fontWeight = FontWeight.Bold)
            Text(item.preview.take(42), style = MaterialTheme.typography.bodySmall, maxLines = 1, modifier = Modifier.weight(1f))
        }
        LinearProgressIndicator(progress = { (item.need / 100f).coerceIn(0f, 1f) }, color = categoryColor(item.category),
            modifier = Modifier.fillMaxWidth().height(6.dp))
        Text("${item.category.label} · need ${item.need}/100 · waiting ${item.waitedMs / 1000}s", style = MaterialTheme.typography.labelSmall, color = MeshColors.muted)
        if (open) item.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/** What people are asking about: the gateway's view of crowd needs (no personal data, categories only). */
@Composable
private fun DemandPanel(q: GatewayQueueSnapshot) {
    if (q.demand.isEmpty()) return
    Section("What people need (last 10 min)") {
        val max = q.demand.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        NeedCategory.entries.filter { (q.demand[it] ?: 0) > 0 }.forEach { c ->
            val n = q.demand[c] ?: 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.label, modifier = Modifier.width(110.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) {
                    LinearProgressIndicator(progress = { n / max.toFloat() }, color = categoryColor(c), modifier = Modifier.fillMaxWidth().height(10.dp))
                }
                Text("  $n", fontWeight = FontWeight.Bold)
            }
        }
        val urgent = (q.demand[NeedCategory.EMERGENCY] ?: 0) + (q.demand[NeedCategory.HEALTH] ?: 0) + (q.demand[NeedCategory.SAFETY] ?: 0)
        if (urgent >= 5) Text("⚠ $urgent safety-related questions in 10 minutes: tell the organizers.", color = MeshColors.sos, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun OrganizerPanel(router: Router?, role: Role) {
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var severity by remember { mutableIntStateOf(1) }
    var sent by remember { mutableStateOf<String?>(null) }
    Section("Broadcast a notice (${role.name.lowercase()})") {
        Text("Every phone in the mesh sees it, marked ✓ ${role.name.lowercase()}. Ordinary phones cannot post here.", color = MeshColors.muted, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(title, { title = it.take(120) }, label = { Text("Title (e.g. Gate B closed)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it.take(500) }, label = { Text("Details") }, modifier = Modifier.fillMaxWidth())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Info" to 0, "Advisory" to 1, "Warning" to 2, "Emergency" to 3).forEach { (label, v) ->
                FilterChip(severity == v, { severity = v }, label = { Text(label) })
            }
        }
        Button(enabled = router != null && title.isNotBlank(), onClick = {
            scope.launch {
                val ok = router?.postNotice(title, body, severity) == true
                sent = if (ok) "Broadcast ✓" else "Not sent"
                if (ok) { title = ""; body = "" }
            }
        }) { Text("📢 Broadcast to everyone") }
        sent?.let { Text(it, color = MeshColors.ok) }
    }
}
