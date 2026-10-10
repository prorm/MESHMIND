package dev.meshmind.app.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import dev.meshmind.app.MeshRuntime
import dev.meshmind.app.service.MeshService
import dev.meshmind.core.routing.MeshSnapshot

/** Runtime permissions Nearby Connections needs on this API level (POST_NOTIFICATIONS is optional). */
fun requiredPermissions(): List<String> = buildList {
    if (Build.VERSION.SDK_INT >= 31) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    }
    // Needed on every level in practice (see manifest comment); FINE implies COARSE.
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
}

private fun optionalPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

private fun missing(ctx: Context) = requiredPermissions().filter {
    ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
}

@Composable
fun HomeScreen(runtime: MeshRuntime, snap: MeshSnapshot, running: Boolean, navigate: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val transport by runtime.transportStatus.collectAsStateWithLifecycle()
    val hasInternet by runtime.internet.validated.collectAsStateWithLifecycle()
    val nodeId by runtime.nodeId.collectAsStateWithLifecycle()
    var permissionNote by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val still = missing(ctx)
        if (still.isEmpty()) {
            permissionNote = null
            MeshService.start(ctx)
        } else {
            permissionNote = "Missing: ${still.joinToString { it.substringAfterLast('.') }}. Nearby cannot discover phones without them."
        }
    }
    val playOk = remember {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS
    }
    val btOn = remember(running) {
        runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true }.getOrDefault(true)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)).padding(14.dp),
        ) {
            Text("NO INTERNET REQUIRED", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Black, fontSize = 20.sp)
            Text(
                "Nearby phones relay for each other over Bluetooth / Wi-Fi. This phone's Internet: " +
                    if (hasInternet) "✓ available" else "✕ none",
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }

        val liveNow = System.currentTimeMillis()
        if (snap.bulletins.any { b -> b.bulletin.items.any { it.expiresAt > liveNow } }) {
            Section("Live info") { LiveInfo(snap.bulletins.take(2), liveNow, null) }
        }

        Section("This node") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(nodeId, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                snap.identity?.let { TrustBadge(it.level, it.role) } ?: run {
                    val cert = remember(nodeId) { runtime.identityStore.enrolledCertificate()?.takeIf { it.nodeId == nodeId } }
                    if (cert != null) TrustBadge(dev.meshmind.core.security.TrustLevel.VERIFIED, cert.role) else TrustBadge(dev.meshmind.core.security.TrustLevel.SELF_SIGNED)
                }
            }
            KeyValue(
                "Mesh", if (running) "RUNNING" else "STOPPED",
                if (running) MeshColors.ok else MeshColors.sos,
            )
            KeyValue("Radio", "${if (transport.advertising) "advertising" else "—"} · ${if (transport.discovering) "discovering" else "scan paused"}")
            KeyValue("Nearby (discovered)", transport.discovered.toString())
            KeyValue("Connected peers", "${snap.connectedPeers.size}  ${snap.connectedPeers.joinToString()}")
            val gw = snap.nearestGateway
            KeyValue(
                "Internet gateways", if (snap.isGateway) "THIS PHONE" else "${snap.gateways.size}" +
                    (gw?.let { "  (nearest ${it.gatewayId}, ${it.hopDistance} hops)" } ?: ""),
                if (snap.isGateway || gw != null) MeshColors.gateway else Color.Unspecified,
            )
            transport.lastError?.let { Text("Last radio error: $it", color = MeshColors.sos, fontSize = 12.sp) }
            if (!playOk) Text("Google Play services unavailable: Nearby Connections will not work on this device.", color = MeshColors.sos)
            if (!btOn) Text("Bluetooth is off. Turn it on (airplane mode can keep Bluetooth on).", color = MeshColors.warn)
            permissionNote?.let { Text(it, color = MeshColors.sos) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!running) {
                    Button(onClick = {
                        val m = missing(ctx)
                        if (m.isEmpty()) MeshService.start(ctx) else launcher.launch((m + optionalPermissions()).toTypedArray())
                    }) { Text("Start mesh") }
                } else {
                    OutlinedButton(onClick = { MeshService.stop(ctx) }) { Text("Stop mesh") }
                }
            }
            if (!running) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newName, { newName = it }, label = { Text("Rename (e.g. A)") }, singleLine = true, keyboardOptions = IdKeyboard, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { if (runtime.rename(newName)) newName = "" }) { Text("Set") }
                }
            }
        }

        val buttons = listOf(
            Triple("🚨 Send SOS", Screen.SOS, MeshColors.sos),
            Triple("🔎 Ask & Live info", Screen.ASK, MeshColors.gateway),
            Triple("💬 Messages", Screen.MESSAGES, null),
            Triple("📶 Share my Internet", Screen.GATEWAY, null),
            Triple("🛡 Identity", Screen.IDENTITY, null),
            Triple("🕸 Network", Screen.NETWORK, null),
            Triple("Light Show", Screen.LIGHT_SHOW, null),
            Triple("🧪 Simulation", Screen.SIMULATION, null),
            Triple("🐞 Debug", Screen.DEBUG, null),
        )
        buttons.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, target, color) ->
                    Button(
                        onClick = { navigate(target) },
                        modifier = Modifier.weight(1f).height(64.dp),
                        colors = if (color != null) ButtonDefaults.buttonColors(containerColor = color) else ButtonDefaults.buttonColors(),
                    ) { Text(label, fontWeight = FontWeight.Bold) }
                }
            }
        }

        if (snap.inbox.isNotEmpty()) {
            Section("Latest received") {
                snap.inbox.take(3).forEach { m ->
                    Text("${if (m.type.name == "SOS") "🚨 " else ""}${m.from}: ${m.text}", fontWeight = FontWeight.SemiBold)
                    Text("${m.route.joinToString(" → ")} · ${m.hopCount} hops · ${m.latencyMs} ms", style = Mono, color = MeshColors.muted)
                }
            }
        }
        Text(
            "Simulation works without phones or permissions. Real mesh needs 2+ phones with Google Play services.",
            style = MaterialTheme.typography.bodySmall, color = MeshColors.muted, modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}
