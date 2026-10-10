package dev.meshmind.app.light

import android.content.Context
import android.os.SystemClock
import dev.meshmind.core.light.*
import kotlinx.coroutines.CoroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** Authenticated local console assets + narrowly allowlisted show operations. */
class LightConsoleBridge(context: Context, private val controller: LightShowController, scope: CoroutineScope) : AutoCloseable {
    val http = LightConsoleHttp(scope, mapOf(
        "/" to ("text/html" to context.assets.open("light/conductor.html").use { it.readBytes() }),
        "/conductor.css" to ("text/css" to context.assets.open("light/conductor.css").use { it.readBytes() }),
        "/conductor.js" to ("text/javascript" to context.assets.open("light/conductor.js").use { it.readBytes() }),
    ), state = ::snapshot, command = ::command)
    val url get() = http.url

    private fun scene(json: JSONObject) = LightScene(LightEffect.valueOf(json.getString("effect")),
        json.getInt("primary"), json.getInt("secondary"), json.getInt("bpm"), json.getInt("spreadMs"),
        json.getInt("brightness"), json.getInt("groups"), json.optInt("direction", 1))
    private fun seat(json: JSONObject) = LightSeat(json.getInt("group"), json.getInt("x"), json.getInt("y"))
    private fun sceneJson(scene: LightScene) = JSONObject().put("effect", scene.effect.name).put("primary", scene.primary)
        .put("secondary", scene.secondary).put("bpm", scene.bpm).put("spreadMs", scene.spreadMs)
        .put("brightness", scene.brightness).put("groups", scene.groups).put("direction", scene.direction)
    private fun seatJson(seat: LightSeat) = JSONObject().put("group", seat.group).put("x", seat.x).put("y", seat.y)
    private fun programJson(program: LightProgram) = JSONObject().put("revision", program.revision).put("effectiveAt", program.effectiveAt)
        .put("cues", JSONArray().also { array -> program.cues.forEach { array.put(JSONObject().put("atMs", it.atMs).put("scene", sceneJson(it.scene))) } })

    private fun snapshot(): String {
        val state = controller.state.value
        val now = SystemClock.elapsedRealtime()
        val json = JSONObject().put("now", now).put("hosting", state.hosting).put("note", state.note)
            .put("readyCount", state.readyCount).put("maxParticipants", 64)
            .put("programs", JSONArray().also { array -> state.programs.forEach { array.put(programJson(it)) } })
            .put("participants", JSONArray().also { array -> state.participants.forEach {
                array.put(JSONObject().put("nodeId", it.nodeId).put("seat", seatJson(it.seat)).put("ageMs", now - it.lastSeen)
                    .put("roundTripMs", it.roundTripMs).put("revision", it.revision))
            } })
        val show = state.show
        json.put("show", if (show == null) JSONObject.NULL else JSONObject().put("showId", show.showId).put("hostId", show.hostId)
            .put("startAt", show.startAt).put("durationMs", show.durationMs).put("periodMs", show.periodMs).put("preset", show.preset.name))
        return json.toString()
    }

    private suspend fun command(raw: String): String {
        try {
            val json = JSONObject(raw)
            when (json.getString("action")) {
                "host" -> {
                    val scene = scene(json.getJSONObject("scene"))
                    val duration = json.getLong("durationMs").also { require(it in 10_000..300_000) }
                    controller.host(LightPreset.RAINBOW, duration); controller.program(listOf(LightCue(0, scene)))
                }
                "scene" -> controller.program(listOf(LightCue(0, scene(json.getJSONObject("scene")))))
                "timeline" -> {
                    val cues = json.getJSONArray("cues")
                    require(cues.length() in 1..12)
                    controller.program(List(cues.length()) { i -> cues.getJSONObject(i).let { LightCue(it.getInt("atMs"), scene(it.getJSONObject("scene"))) } })
                }
                "finale" -> controller.finale(json.getInt("bpm"))
                "blackout" -> controller.blackout()
                "restore" -> controller.restore()
                "grid" -> controller.arrangeGrid()
                "seat" -> controller.assign(json.getString("nodeId"), seat(json.getJSONObject("seat")))
                "stop" -> { require(controller.state.value.hosting); controller.leave() }
                else -> throw IllegalArgumentException("Unknown action")
            }
            return JSONObject().put("ok", true).toString()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // Return an actionable message without exposing stack traces or pairing credentials.
            return JSONObject().put("ok", false).put("error", if (e is IllegalArgumentException || e is IllegalStateException)
                e.message ?: "Invalid command" else "Invalid controller command; check the fields").toString()
        }
    }

    override fun close() = http.close()
}
