@file:OptIn(ExperimentalLayoutApi::class)

package dev.meshmind.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.meshmind.app.MeshRuntime
import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetResponse
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.routing.InetResult
import dev.meshmind.core.routing.MeshSnapshot
import dev.meshmind.core.routing.Router
import dev.meshmind.core.security.TrustLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val suggestions = listOf("Is the metro running?", "Nearest hospital", "Which exit gates are open?", "Weather next 3 hours")

/** Ask anything through the mesh, see live info, and see exactly how each answer got here. */
@Composable
fun AskScreen(runtime: MeshRuntime, router: Router?, snap: MeshSnapshot, navigate: (Screen) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    val asks = snap.inet.filter { it.request.kind == InetKind.ASK || it.request.kind == InetKind.READ }

    fun ask(q: String) {
        val text = q.trim()
        if (text.isEmpty() || router == null) return
        scope.launch { router.sendAsk(text, runtime.batteryPercent()) }
        query = ""
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Spacer(Modifier.size(4.dp)) }
        item { ConnectivityHero(snap, router != null, navigate) }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it.take(300) },
                placeholder = { Text("Ask anything: trains, hospitals, exits…") },
                leadingIcon = { Text("🔎", fontSize = 18.sp) },
                trailingIcon = {
                    FilledIconButton(onClick = { ask(query) }, enabled = router != null && query.isNotBlank()) { Text("➤", fontSize = 18.sp) }
                },
                singleLine = true, shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { ask(query) }),
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                suggestions.forEach { s -> AssistChip(onClick = { ask(s) }, enabled = router != null, label = { Text(s) }) }
            }
            if (router == null) Text("Start the mesh on Home to ask.", color = MeshColors.sos, modifier = Modifier.padding(top = 4.dp))
        }
        if (snap.bulletins.isNotEmpty()) {
            item { Header("Live info") }
            item { LiveInfo(snap.bulletins, now, null) }
        }
        if (asks.isNotEmpty()) item { Header("Your questions") }
        items(asks, key = { it.requestId }) { r -> AnswerCard(r, now, onRead = { url -> scope.launch { router?.sendRead(url) } }, onRetry = { ask(r.request.body.ifEmpty { r.request.url }) }) }
        if (asks.isEmpty()) item {
            Text(
                "Answers are fetched by a nearby phone that has Internet, signed by MeshMind so nobody along the way can alter them, " +
                    "and remembered by every phone they pass, so the next person asking the same thing gets it instantly.",
                color = MeshColors.muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun Header(text: String) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))

/** One line that answers "can I reach the Internet right now, and through whom?". */
@Composable
private fun ConnectivityHero(snap: MeshSnapshot, running: Boolean, navigate: (Screen) -> Unit) {
    val gw = snap.gateways.minByOrNull { it.hopDistance + 3 * it.load }
    val (icon, title, sub, color) = when {
        !running -> Quad("⏸", "Mesh is off", "Start it on Home to reach nearby phones.", MeshColors.muted)
        snap.isGateway -> Quad("📶", "You are sharing Internet", "Nearby phones' questions go through this phone.", MeshColors.gateway)
        gw != null -> Quad("🌐", "Internet reachable", "via ${gw.gatewayId} · ${gw.hopDistance} hop${if (gw.hopDistance == 1) "" else "s"}" +
            (if (snap.gateways.size > 1) " · ${snap.gateways.size} gateways" else "") +
            (if (gw.load > 0.5) " · busy" else ""), MeshColors.ok)
        snap.connectedPeers.isNotEmpty() -> Quad("🔍", "Looking for a phone with Internet", "${snap.connectedPeers.size} phone(s) nearby. Questions wait in the mesh until one appears.", MeshColors.warn)
        else -> Quad("📡", "No phones nearby yet", "Questions are kept and sent as soon as someone is in range.", MeshColors.warn)
    }
    Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 30.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = color)
                Text(sub, style = MaterialTheme.typography.bodySmall)
            }
            snap.identity?.let { id ->
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.clickable { navigate(Screen.IDENTITY) }) {
                    TrustBadge(id.level, id.role)
                }
            }
        }
    }
}

private data class Quad(val a: String, val b: String, val c: String, val d: Color)

@Composable
private fun AnswerCard(r: InetResult, now: Long, onRead: (String) -> Unit, onRetry: () -> Unit) {
    var why by remember { mutableStateOf(false) }
    var full by remember { mutableStateOf(false) }
    val resp = r.response
    val a = r.answer
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (r.request.kind == InetKind.READ) "📄 ${r.request.url}" else r.request.body,
                fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, maxLines = 2,
            )
            when {
                resp == null -> Progress(r, now)
                resp.status == InetStatus.OK && a != null -> {
                    val text = if (r.request.kind == InetKind.READ && !full && a.text.length > 420) a.text.take(420) + "…" else a.text
                    Text(text, style = MaterialTheme.typography.bodyLarge)
                    if (r.request.kind == InetKind.READ && a.text.length > 420) {
                        TextButton(onClick = { full = !full }) { Text(if (full) "Show less" else "Read full text") }
                    }
                    if (a.sources.isNotEmpty() && r.request.kind == InetKind.ASK) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            a.sources.forEach { s ->
                                AssistChip(onClick = { onRead(s.url) }, label = { Text("📄 ${s.title.take(28)}", fontSize = 12.sp) })
                            }
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (r.answerVerified) Pill("✓ Signed by MeshMind", MeshColors.ok) else Pill("⚠ Unverified answer", MeshColors.sos)
                        Pill(servedLabel(r), MeshColors.gateway)
                        if (resp.need >= 0) Pill("${resp.needCategory} · ${resp.need}/100", MeshColors.priority(dev.meshmind.core.model.Priority.NORMAL))
                    }
                    Text("${a.provider} · ${ago(now - a.createdAt)}", style = MaterialTheme.typography.labelSmall, color = MeshColors.muted)
                }
                resp.status == InetStatus.OK -> Text(resp.bodyText.take(800), style = Mono)
                else -> {
                    Text(
                        when (resp.status) {
                            InetStatus.DEFERRED -> "⏳ ${resp.message}"
                            InetStatus.REJECTED -> "✕ ${resp.message}"
                            else -> "⚠ ${resp.message}"
                        },
                        color = if (resp.status == InetStatus.DEFERRED) MeshColors.warn else MeshColors.sos,
                    )
                    TextButton(onClick = onRetry) { Text("Try again") }
                }
            }
            if (resp != null && resp.needReasons.isNotEmpty()) {
                Text(if (why) "Hide why ▲" else "Why this priority? ▼", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { why = !why })
                if (why) {
                    Column(Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(10.dp).fillMaxWidth()) {
                        resp.needReasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        if (resp.queueWaitMs > 0) Text("• waited ${"%.1f".format(resp.queueWaitMs / 1000.0)} s in the gateway's fair-share queue", style = MaterialTheme.typography.bodySmall)
                        if (resp.requestRoute.isNotEmpty()) Text("• route: ${resp.requestRoute.joinToString(" → ")} → 🌐", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(r: InetResult, now: Long) {
    val atGateway = r.reachedGatewayAt != null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Column {
            Text(if (atGateway) "At the gateway · waiting for its turn" else "Travelling through the mesh…", style = MaterialTheme.typography.bodyMedium)
            Text("sent ${ago(now - r.sentAt)}", style = MaterialTheme.typography.labelSmall, color = MeshColors.muted)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Step("Sent", true); Step("At gateway", atGateway); Step("Answered", false)
    }
}

@Composable
private fun Step(label: String, done: Boolean) {
    Text((if (done) "● " else "○ ") + label, fontSize = 12.sp, color = if (done) MeshColors.ok else MeshColors.muted)
}

private fun servedLabel(r: InetResult): String {
    val took = (r.completedAt ?: r.sentAt) - r.sentAt
    return when (r.servedFrom) {
        InetResponse.SERVED_LOCAL_CACHE -> "⚡ Instant · saved on this phone"
        InetResponse.SERVED_RELAY_CACHE -> "📡 From ${r.response?.gatewayId}'s cache · ${"%.1f".format(took / 1000.0)} s"
        InetResponse.SERVED_GATEWAY_CACHE -> "🗄 Gateway cache · ${"%.1f".format(took / 1000.0)} s"
        else -> "🌐 via ${r.response?.gatewayId} · ${"%.1f".format(took / 1000.0)} s"
    }
}

/** Small helper so other screens can show a trust level next to a sender. */
@Composable
fun SenderTrust(level: TrustLevel) {
    if (level == TrustLevel.VERIFIED) Pill("✓", MeshColors.ok) else if (level == TrustLevel.SELF_SIGNED) Pill("unverified", MeshColors.muted)
}
