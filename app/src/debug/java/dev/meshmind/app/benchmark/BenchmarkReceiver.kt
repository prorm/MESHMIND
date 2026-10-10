package dev.meshmind.app.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dev.meshmind.core.light.LightPreset
import dev.meshmind.app.MeshMindApp
import dev.meshmind.app.service.MeshService
import dev.meshmind.core.model.InetKind
import dev.meshmind.core.model.InetRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest

/** Debug-only observation/control of the existing APIs; no routing or radio policy changes. */
class BenchmarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val runtime = (context.applicationContext as MeshMindApp).runtime
        val token = intent.getStringExtra("token") ?: "result"
        require(token.matches(Regex("[A-Za-z0-9_-]+")))
        runtime.launch {
            val out = JSONObject()
            try {
                val command = intent.getStringExtra("command") ?: "snapshot"
                val r = runtime.router.value
                val destination = intent.getStringExtra("destination") ?: "*"
                val text = intent.getStringExtra("text") ?: "benchmark"
                out.put("commandTime", System.currentTimeMillis())
                out.put("before", encode(r?.state?.value))
                when (command) {
                    "start" -> { MeshService.start(context); runtime.startMesh() }
                    "stop" -> { runtime.stopMesh(); MeshService.stop(context) }
                    "text" -> out.put("packet", encode(requireNotNull(r).sendText(destination, text)))
                    "sos" -> out.put("packet", encode(requireNotNull(r).sendSos(text, destination)))
                    "internet" -> out.put("packet", encode(requireNotNull(r).sendInternetRequest(InetRequest(InetKind.HTTP_GET, "https://example.com/", ""))))
                    "ask" -> out.put("requestId", requireNotNull(r).sendAsk(text, runtime.batteryPercent()))
                    "read" -> out.put("requestId", requireNotNull(r).sendRead(intent.getStringExtra("url") ?: "https://example.com/"))
                    "notice" -> out.put("posted", requireNotNull(r).postNotice(text, intent.getStringExtra("body") ?: "", intent.getIntExtra("severity", 1)))
                    "bulletin_refresh" -> out.put("broadcast", runtime.refreshBulletin())
                    "config" -> {
                        intent.getStringExtra("backendUrl")?.let { runtime.setBackendUrl(it) }
                        if (intent.hasExtra("consent")) runtime.updateGateway { it.copy(consent = intent.getBooleanExtra("consent", false)) }
                        if (intent.hasExtra("force")) runtime.updateGateway { it.copy(forceGateway = intent.getBooleanExtra("force", false)) }
                        if (intent.hasExtra("perMinute")) runtime.updateGateway { it.copy(perMinute = intent.getIntExtra("perMinute", 30)) }
                        out.put("backendUrl", runtime.backendUrl.value)
                    }
                    "enroll" -> {
                        val phone = intent.getStringExtra("phone") ?: error("phone extra required")
                        val code = runtime.backend.enrollStart(phone) ?: error("backend is not in demo OTP mode")
                        val role = dev.meshmind.core.security.Role.valueOf(intent.getStringExtra("role") ?: "USER")
                        out.put("role", runtime.enrollNow(phone, code, role, intent.getStringExtra("organizerCode") ?: "").name)
                    }
                    "bulk_sos" -> {
                        val bytes = ByteArray(intent.getIntExtra("bytes", 262144)) { (it % 251).toByte() }
                        out.put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                        out.put("transferId", requireNotNull(r).sendTransfer(destination, token, bytes))
                        out.put("atInjection", encode(r.state.value))
                        out.put("packet", encode(r.sendSos(text, destination)))
                    }
                    "file" -> {
                        val bytes = requireNotNull(r).receivedFile(intent.getStringExtra("transferId") ?: "")
                        out.put("fileBytes", bytes?.size ?: 0)
                        out.put("sha256", bytes?.let { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } })
                    }
                    "sage_enable" -> runtime.setSageEnabled(intent.getBooleanExtra("on", true))
                    "sage_export" -> out.put("sageCsv", runtime.sage.value?.exportCsv())
                    "light_host" -> requireNotNull(runtime.lightShow.value).host(LightPreset.valueOf(intent.getStringExtra("preset") ?: "RAINBOW"),
                        intent.getIntExtra("durationMs", 60_000).toLong(), intent.getIntExtra("periodMs", 8_000).toLong())
                    "light_discover" -> requireNotNull(runtime.lightShow.value).discover()
                    "light_join" -> requireNotNull(runtime.lightShow.value).let { c ->
                        c.join(intent.getStringExtra("showId") ?: c.state.value.offers.first().showId) }
                    "light_leave" -> requireNotNull(runtime.lightShow.value).leave()
                    "light_console" -> if (intent.getBooleanExtra("on", true)) runtime.enableLightConsole() else runtime.disableLightConsole()
                    "snapshot" -> Unit
                    else -> error("Unknown benchmark command $command")
                }
                out.put("ok", true)
            } catch (e: Exception) { out.put("ok", false); out.put("error", e.toString()) }
            out.put("deviceTime", System.currentTimeMillis())
            out.put("nodeId", runtime.nodeId.value)
            out.put("peerFilter", encode(runtime.peerFilter.value))
            out.put("internetValidated", runtime.internet.validated.value)
            out.put("gatewayActive", runtime.isGatewayActive)
            out.put("running", runtime.router.value != null)
            out.put("sageEnabled", runtime.sageEnabled.value)
            out.put("sage", encode(runtime.sage.value?.statsFlow?.value))
            out.put("snapshot", encode(runtime.router.value?.state?.value))
            out.put("logs", encode(runtime.router.value?.logger?.entries?.value))
            runtime.lightShow.value?.state?.value?.let { light ->
                // Elapsed-realtime clock used by playback, and the color this phone renders at that instant.
                val elapsed = SystemClock.elapsedRealtime()
                out.put("lightElapsed", elapsed)
                out.put("lightColor", light.colorAt(elapsed, runtime.nodeId.value))
                out.put("light", encode(light))
            }
            out.put("lightConsoleUrl", runtime.lightConsole.value?.url ?: JSONObject.NULL)
            val dir = File(context.filesDir, "benchmark").apply { mkdirs() }
            val target = File(dir, "$token.json")
            val temp = File(dir, "$token.tmp")
            temp.writeText(out.toString())
            temp.renameTo(target)
            pending.finish()
        }
    }

    private fun encode(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is String, is Number, is Boolean -> v
        is Enum<*> -> v.name
        is ByteArray -> JSONObject().put("size", v.size)
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, value) -> o.put(k.toString(), encode(value)) } }
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(encode(it)) } }
        else -> JSONObject().also { o ->
            v.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }.forEach {
                it.isAccessible = true
                o.put(it.name, encode(it.get(v)))
            }
        }
    }
}
