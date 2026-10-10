@file:OptIn(ExperimentalLayoutApi::class)

package dev.meshmind.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.meshmind.app.MeshRuntime
import dev.meshmind.core.routing.MeshSnapshot
import dev.meshmind.core.security.Role
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phone verification: turns a self-signed identity into a MeshMind-verified one (and organizer/responder roles). */
@Composable
fun IdentityScreen(runtime: MeshRuntime, snap: MeshSnapshot) {
    val enroll by runtime.enroll.collectAsStateWithLifecycle()
    val backendUrl by runtime.backendUrl.collectAsStateWithLifecycle()
    val nodeId by runtime.nodeId.collectAsStateWithLifecycle()
    val version by runtime.identityVersion.collectAsStateWithLifecycle()
    val cert = remember(version, nodeId) { runtime.identityStore.enrolledCertificate()?.takeIf { it.nodeId == nodeId } }
    val fingerprint = remember { runtime.identityStore.signer.fingerprint }
    var phone by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(Role.USER) }
    var orgCode by rememberSaveable { mutableStateOf("") }
    val verified = cert != null

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Spacer(Modifier.size(4.dp))
        Card(colors = CardDefaults.cardColors(containerColor = (if (verified) MeshColors.ok else MeshColors.warn).copy(alpha = 0.12f)), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (verified) "🛡" else "👤", fontSize = 32.sp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(nodeId, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (verified) "Verified ${cert!!.role.name.lowercase()} · phone confirmed" else "Unverified: others see your messages marked “unverified ID”",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text("Key fingerprint", style = MaterialTheme.typography.labelSmall, color = MeshColors.muted)
                Text(fingerprint.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                if (cert != null) {
                    val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                    Text("Issued by MeshMind authority ${cert.issuer} · valid until ${fmt.format(Date(cert.expiresAt))}", style = MaterialTheme.typography.bodySmall, color = MeshColors.muted)
                }
                if (snap.identity != null && (snap.identity!!.level.name == "VERIFIED") != verified) {
                    Text("The running mesh restarts to use the new identity…", color = MeshColors.warn, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Section("Why verify") {
            Text("• Every message you send is signed by this phone. Nobody can change it on the way or pretend to be you.", style = MaterialTheme.typography.bodyMedium)
            Text("• Verified phones show a ✓ to everyone and get a fair share of shared Internet; organizers and responders can broadcast notices.", style = MaterialTheme.typography.bodyMedium)
            Text("• Your number stays with the MeshMind server. Other phones only ever see your name and the ✓.", style = MaterialTheme.typography.bodyMedium)
            Text("SOS always works, verified or not.", fontWeight = FontWeight.SemiBold, color = MeshColors.sos)
        }

        Section(if (verified) "Verify again" else "Verify this phone (needs Internet once)") {
            OutlinedTextField(backendUrl, { runtime.setBackendUrl(it) }, label = { Text("MeshMind backend") }, placeholder = { Text("http://192.168.1.20:8787") },
                singleLine = true, keyboardOptions = IdKeyboard, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it.filter { c -> c.isDigit() || c == '+' }.take(16) }, label = { Text("Phone number") }, placeholder = { Text("+91 98765 43210") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
            OutlinedButton(enabled = !enroll.busy && phone.length >= 10 && backendUrl.isNotBlank(), onClick = { runtime.requestCode(phone) }) {
                Text(if (enroll.codeSent) "Send a new code" else "Send code")
            }
            if (enroll.codeSent) {
                enroll.demoCode?.let { Text("Demo code: $it", fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = MeshColors.gateway) }
                OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("6-digit code") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                Text("Role", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Role.entries.forEach { r -> FilterChip(role == r, { role = r }, label = { Text(r.name.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                if (role != Role.USER) {
                    OutlinedTextField(orgCode, { orgCode = it }, label = { Text("Event organizer code") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                Button(enabled = !enroll.busy && code.length == 6, onClick = { runtime.verifyCode(phone, code, role, orgCode) }) { Text("Verify") }
            }
            enroll.message?.let { Text(it, color = if (enroll.error) MeshColors.sos else MeshColors.ok) }
            if (verified) TextButton(onClick = { runtime.forgetVerification() }) { Text("Remove verification from this phone") }
        }
        Spacer(Modifier.size(24.dp))
    }
}
