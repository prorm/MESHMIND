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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.meshmind.app.MeshRuntime
import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetRequest
import dev.meshmind.core.model.InetStatus
import dev.meshmind.core.routing.MeshSnapshot
import dev.meshmind.core.routing.Router
import kotlinx.coroutines.launch

private val presets = listOf(
    "example.com" to "https://example.com/",
    "Weather (open-meteo)" to "https://api.open-meteo.com/v1/forecast?latitude=12.97&longitude=77.59&current_weather=true",
    "httpbin /get" to "https://httpbin.org/get",
)

/** Developer tools: raw allowlisted GET and webhook POST through the mesh (the product UI is AskScreen). */
@Composable
fun RawInternetScreen(runtime: MeshRuntime, router: Router?, snap: MeshSnapshot) {
    val scope = rememberCoroutineScope()
    val settings by runtime.gatewaySettings.collectAsStateWithLifecycle()
    val validated by runtime.internet.validated.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(presets[0].second) }
    var message by remember { mutableStateOf("Hello from the mesh (no Internet here)") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
        if (router == null) Text("Mesh is stopped. Start it on Home first.", color = MeshColors.sos, modifier = Modifier.padding(8.dp))
        Section("Gateways") {
            KeyValue("Internet gateways", if (snap.isGateway) "THIS PHONE" else snap.gateways.size.toString(), MeshColors.gateway)
            snap.gateways.forEach { g -> Text("🌐 ${g.gatewayId}: ${g.hopDistance} hop(s) via ${g.viaPeer}", style = Mono) }
            if (snap.gateways.isEmpty() && !snap.isGateway) {
                Text("No gateway heard yet. Requests will wait in the mesh (store-and-forward) until a beacon arrives.", color = MeshColors.muted)
            }
        }

        Section("Request via mesh") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { (name, u) -> FilterChip(url == u, { url = u }, label = { Text(name) }) }
            }
            OutlinedTextField(url, { url = it.trim() }, label = { Text("HTTPS URL (gateway allowlist applies)") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = router != null, onClick = { scope.launch { router?.sendInternetRequest(InetRequest(InetKind.HTTP_GET, url, "")) } }) {
                Text("GET via mesh")
            }
            HorizontalDivider()
            OutlinedTextField(message, { message = it }, label = { Text("Message for the gateway's webhook") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = router != null, onClick = { scope.launch { router?.sendInternetRequest(InetRequest(InetKind.WEBHOOK_POST, "", message)) } }) {
                Text("POST to webhook via mesh")
            }
        }

        Section("Results") {
            if (snap.inet.isEmpty()) Text("No requests yet.", color = MeshColors.muted)
            snap.inet.take(10).forEach { r ->
                val resp = r.response
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${r.request.kind}", fontWeight = FontWeight.Bold)
                    PriorityPill(r.priority)
                    when {
                        resp == null && r.reachedGatewayAt != null -> Pill("AT GATEWAY", MeshColors.warn)
                        resp == null -> Pill("WAITING", MeshColors.muted)
                        resp.status == InetStatus.OK -> Pill("OK ${resp.httpCode}", MeshColors.ok)
                        else -> Pill(resp.status.name, MeshColors.sos)
                    }
                }
                Text(r.request.url.ifEmpty { "(gateway webhook) \"${r.request.body}\"" }, style = Mono)
                if (resp != null) {
                    val executed = resp.status != InetStatus.REJECTED
                    Text(
                        "OUT  ${resp.requestRoute.joinToString(" → ")} → ${if (executed) "🌐 Internet" else "✕ refused by gateway"}\n" +
                            "BACK ${r.responseRoute.joinToString(" → ")}",
                        style = Mono, color = if (executed) MeshColors.ok else MeshColors.sos,
                    )
                    Text(
                        "${resp.body.size} B${if (resp.truncated) " (truncated)" else ""} · ${resp.contentType} · round trip ${(r.completedAt ?: 0) - r.sentAt} ms · ${resp.message}",
                        style = Mono, color = MeshColors.muted,
                    )
                    if (resp.body.isNotEmpty()) Text(resp.bodyText.take(1500), style = Mono)
                } else {
                    Text("sent ${(System.currentTimeMillis() - r.sentAt) / 1000}s ago; waiting for a gateway", style = Mono, color = MeshColors.muted)
                }
                HorizontalDivider()
            }
        }

        Section("This phone as gateway") {
            KeyValue("Internet on this phone", if (validated) "✓ validated" else "✕ not validated", if (validated) MeshColors.ok else MeshColors.sos)
            KeyValue("Gateway role", if (runtime.isGatewayActive) "ACTIVE (beaconing)" else "inactive", if (runtime.isGatewayActive) MeshColors.gateway else MeshColors.muted)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.consent, { v -> runtime.updateGateway { it.copy(consent = v) } })
                Text("  I consent to relay allowlisted requests for nearby phones")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(settings.forceGateway, { v -> runtime.updateGateway { it.copy(forceGateway = v) } })
                Text("  Demo: act as gateway even without validated Internet")
            }
            OutlinedTextField(
                settings.webhookUrl, { v -> runtime.updateGateway { it.copy(webhookUrl = v.trim()) } },
                label = { Text("Webhook URL for POSTs (e.g. https://ntfy.sh/your-topic)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            var allow by remember { mutableStateOf(settings.allowlist.sorted().joinToString(", ")) }
            OutlinedTextField(allow, { allow = it }, label = { Text("Allowlisted domains") }, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = {
                runtime.updateGateway { s -> s.copy(allowlist = allow.split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()) }
            }) { Text("Save allowlist") }
            Text(
                "Not an open proxy: HTTPS only, allowlisted hosts, GET or one fixed webhook, no custom headers, " +
                    "16 KB response cap, 6 req/min per requester, 30/min total, redirects not followed.",
                color = MeshColors.muted,
            )
        }
    }
}
