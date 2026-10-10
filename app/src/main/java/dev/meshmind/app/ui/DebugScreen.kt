@file:OptIn(ExperimentalLayoutApi::class)

package dev.meshmind.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.meshmind.core.routing.LogCategory
import dev.meshmind.core.routing.MeshLogger
import dev.meshmind.core.routing.MeshSnapshot
import dev.meshmind.core.routing.Router
import dev.meshmind.core.sage.SageShadow
import dev.meshmind.core.sage.ShadowStats
import dev.meshmind.app.MeshRuntime
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

private fun exportReport(ctx: Context, router: Router, sage: SageShadow?) {
    val dir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "meshmind-${router.selfId}-${System.currentTimeMillis()}.txt")
    file.writeText(router.exportReport() + (sage?.let { "\n" + it.exportCsv() } ?: ""))
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.exports", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, file.name)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(send, "Export MeshMind debug log"))
}

@Composable
fun DebugScreen(router: Router?, snap: MeshSnapshot, runtime: MeshRuntime, openRawTools: () -> Unit = {}) {
    val ctx = LocalContext.current
    val sage by runtime.sage.collectAsStateWithLifecycle()
    val sageOn by runtime.sageEnabled.collectAsStateWithLifecycle()
    val sageStatsFlow = remember(sage) { sage?.statsFlow ?: MutableStateFlow(ShadowStats()) }
    val sageStats by sageStatsFlow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val logsFlow = remember(router) { router?.logger?.entries ?: MutableStateFlow(emptyList()) }
    val logs by logsFlow.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf<LogCategory?>(null) }
    val shown = remember(logs, filter) { logs.asReversed().filter { filter == null || it.category == filter }.take(800) }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                Button(enabled = router != null, onClick = { router?.let { exportReport(ctx, it, sage) } }) { Text("Export to text") }
                OutlinedButton(enabled = router != null, onClick = { scope.launch { router?.clearHistory() } }) { Text("Clear") }
                OutlinedButton(onClick = openRawTools) { Text("Raw Internet") }
            }
            val s = snap.stats
            Section("Counters") {
                StatGrid(
                    listOf(
                        "frames rx" to s.framesReceived.toString(),
                        "forwarded" to s.packetsForwarded.toString(),
                        "originated" to s.packetsOriginated.toString(),
                        "delivered" to s.deliveredToMe.toString(),
                        "acks rx" to s.acksReceived.toString(),
                        "duplicates" to s.duplicatesRejected.toString(),
                        "expired" to s.expired.toString(),
                        "TTL drops" to s.ttlExhausted.toString(),
                        "send fails" to s.sendFailures.toString(),
                        "bytes tx" to s.bytesSent.toString(),
                        "bytes rx" to s.bytesReceived.toString(),
                        "beacons" to s.beaconsReceived.toString(),
                        "refused (trust)" to s.rejectedUntrusted.toString(),
                        "answer cache" to snap.answerCacheSize.toString(),
                        "answered for others" to snap.relayCacheAnswers.toString(),
                    ),
                )
            }
            Section("SAGE shadow mode") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Switch(checked = sageOn, onCheckedChange = { runtime.setSageEnabled(it) })
                    Text("Score routing decisions on this phone. Logging only: routing and SOS are never changed.", color = MeshColors.muted)
                }
                val st = sageStats
                StatGrid(
                    listOf(
                        "model" to if (st.modelLoaded) "loaded" else "missing",
                        "scored" to st.scored.toString(),
                        "eco would skip" to st.wouldSkip.toString(),
                        "no peer summary" to st.noPeerSummary.toString(),
                        "peer summaries" to "${st.peersWithSummary} (${st.summariesReceived} rx)",
                        "contact bins" to st.binsObserved.toString(),
                        "contacts known" to st.knownContacts.toString(),
                        "score p50/p95" to "${st.p50Micros}/${st.p95Micros} µs",
                        "errors / dropped" to "${st.errors} / ${st.dropped}",
                    ),
                )
                st.lastError?.let { Text("last error: $it", color = MeshColors.sos) }
            }
            Section("Delivery metrics (latest first)") {
                val rows = snap.metrics.asReversed().filter { it.type.name != "CHUNK" && it.type.name != "ACK" }.take(25)
                if (rows.isEmpty()) Text("No deliveries yet.", color = MeshColors.muted)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(String.format(java.util.Locale.US, "%-4s %-13s %-8s %-4s %-7s %-7s %s", "side", "type", "prio", "hops", "1way", "rtt", "route"), style = Mono, color = MeshColors.muted)
                    rows.forEach { m ->
                        Text(
                            String.format(
                                java.util.Locale.US,
                                "%-4s %-13s %-8s %-4d %-7s %-7s %s",
                                if (m.side == "source") "src" else "dst", m.type, m.priority, m.hops, "${m.oneWayMs}", m.rttMs?.toString() ?: "-", m.route.joinToString(">"),
                            ),
                            style = Mono,
                        )
                    }
                }
            }
            Section("Queued for forwarding (${snap.queued.size})") {
                if (snap.queued.isEmpty()) Text("Queue empty.", color = MeshColors.muted)
                snap.queued.take(20).forEach { q ->
                    Text("${q.packetId.take(8)} ${q.type} ${q.priority} ${q.sourceId}→${q.destinationId} ${q.state} ttl=${q.ttl} hops=${q.hops} age=${q.ageMs / 1000}s", style = Mono)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(filter == null, { filter = null }, label = { Text("ALL") })
                LogCategory.entries.forEach { c -> FilterChip(filter == c, { filter = if (filter == c) null else c }, label = { Text(c.name) }) }
            }
        }
        items(shown) { e ->
            val color = when (e.category) {
                LogCategory.ERROR -> MeshColors.sos
                LogCategory.DELIVERY -> MeshColors.ok
                LogCategory.DUPLICATE, LogCategory.EXPIRY -> MeshColors.warn
                LogCategory.GATEWAY, LogCategory.BEACON -> MeshColors.gateway
                else -> androidx.compose.ui.graphics.Color.Unspecified
            }
            Text("${MeshLogger.formatTime(e.time)} [${e.category}] ${e.message}", style = Mono, color = color, modifier = Modifier.padding(vertical = 1.dp))
        }
    }
}
