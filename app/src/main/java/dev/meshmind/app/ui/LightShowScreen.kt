package dev.meshmind.app.ui

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.meshmind.app.MeshRuntime
import dev.meshmind.core.light.LightPreset
import dev.meshmind.core.light.LightSnapshot
import dev.meshmind.core.light.LightCue
import dev.meshmind.core.light.LightEffect
import dev.meshmind.core.light.LightScene
import androidx.compose.foundation.text.selection.SelectionContainer
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun LightShowScreen(runtime: MeshRuntime) {
    val controller by runtime.lightShow.collectAsStateWithLifecycle()
    val stateFlow = remember(controller) { controller?.state ?: MutableStateFlow(LightSnapshot()) }
    val state by stateFlow.collectAsStateWithLifecycle()
    val console by runtime.lightConsole.collectAsStateWithLifecycle()
    val nodeId by runtime.nodeId.collectAsStateWithLifecycle()
    var controlNote by remember { mutableStateOf<String?>(null) }
    var effect by rememberSaveable { mutableStateOf(LightEffect.WAVE) }
    var bpm by rememberSaveable { mutableIntStateOf(100) }
    var brightness by rememberSaveable { mutableIntStateOf(85) }
    var reverse by rememberSaveable { mutableStateOf(false) }
    var preset by rememberSaveable { mutableStateOf(LightPreset.RAINBOW) }
    var duration by rememberSaveable { mutableLongStateOf(60_000) }
    var period by rememberSaveable { mutableLongStateOf(8_000) }
    var fullScreen by rememberSaveable { mutableStateOf(false) }
    var localNow by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalContext.current as? Activity

    LaunchedEffect(controller) { controller?.discover() }

    LaunchedEffect(state.show) {
        while (state.show != null) withFrameNanos { localNow = SystemClock.elapsedRealtime() }
    }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                fullScreen = false
                controller?.let { runtime.launch { if (runtime.lightConsole.value == null || !it.state.value.hosting) it.leave() } }
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            controller?.let { runtime.launch { if (runtime.lightConsole.value == null || !it.state.value.hosting) it.leave() } }
        }
    }
    DisposableEffect(state.show != null, activity) {
        val window = activity?.window
        val alreadyKeptOn = (window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) ?: 0) != 0
        if (state.show != null) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { if (!alreadyKeptOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(state.show) { if (state.show == null) fullScreen = false }

    val position = state.positionAt(localNow)
    val playing = position != null && position >= 0 && position < (state.show?.durationMs ?: 0)
    val color = Color(state.colorAt(localNow, nodeId))
    val stage = when {
        state.show == null -> "Ready to light up the mesh"
        position == null -> "Synchronizing…"
        position < 0 -> "Starts in ${(-position + 999) / 1_000} s"
        !playing -> "Show finished"
        else -> "${(state.show!!.durationMs - position + 999) / 1_000} s remaining"
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Turn connected phones into one shared canvas.", style = MaterialTheme.typography.titleLarge)
        Text("Smooth screen colors, played to the host's timeline. No Internet required.", style = MaterialTheme.typography.bodyMedium)
        ControlHint(controlNote)
        if (controller == null) {
            Text("Start the mesh on Home, then return here to host or join a show.", color = MaterialTheme.colorScheme.error)
        }
        Box(Modifier.fillMaxWidth().height(180.dp).background(color), contentAlignment = Alignment.Center) {
            Surface(color = Color.Black.copy(alpha = .75f), contentColor = Color.White) {
                Text(stage, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), fontSize = 20.sp)
            }
        }
        if (state.show != null) {
            Text("${state.show!!.preset.title} · ${if (state.hosting) "Hosting" else "Host: ${state.show!!.hostId}"}",
                style = MaterialTheme.typography.titleMedium)
            Text(state.note)
            if (state.hosting) Text("${state.readyCount} other phone(s) recently synchronized")
            else state.clock?.let { Text("Best sync round trip: ${it.roundTripMs} ms", style = Mono) }
            if (state.hosting && controller != null) {
                Text("Conductor", style = MaterialTheme.typography.titleLarge)
                LightEffect.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { option ->
                        FilterChip(selected = effect == option, onClick = { effect = option }, label = { Text(option.title) })
                    } }
                }
                Text("Tempo · $bpm BPM")
                Slider(value = bpm.toFloat(), onValueChange = { bpm = it.toInt() }, valueRange = 30f..180f)
                Text("Master brightness · $brightness%")
                Slider(value = brightness.toFloat(), onValueChange = { brightness = it.toInt() }, valueRange = 0f..100f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = reverse, onCheckedChange = { reverse = it }); Text("Reverse motion")
                }
                val pending = state.programs.any { it.effectiveAt > localNow }
                Button(onClick = { val c = controller; if (c != null) runtime.launch {
                    runCatching { c.program(listOf(LightCue(0, LightScene(effect = effect, bpm = bpm, brightness = brightness,
                        direction = if (reverse) -1 else 1)))) }.onFailure { controlNote = it.message }
                } }, enabled = !pending, modifier = Modifier.fillMaxWidth()) { Text(if (pending) "Cue queued…" else "Fire scene on the next beat") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller?.let { c -> runtime.launch { runCatching { c.blackout() }.onFailure { controlNote = it.message } } } }) { Text("Blackout") }
                    OutlinedButton(onClick = { controller?.let { c -> runtime.launch { runCatching { c.restore() }.onFailure { controlNote = it.message } } } }, enabled = !pending) { Text("Restore") }
                }
                OutlinedButton(onClick = { controller?.let { c -> runtime.launch { runCatching { c.finale(bpm) }.onFailure { controlNote = it.message } } } }, enabled = !pending) { Text("Run five-act finale") }
                OutlinedButton(onClick = { controller?.let { c -> runtime.launch { c.arrangeGrid() } } }) { Text("Arrange joined phones in a virtual grid") }
                state.participants.forEach { phone -> Text("${phone.nodeId} · group ${phone.seat.group + 1} · ${phone.roundTripMs} ms · cue ${phone.revision}", style = MaterialTheme.typography.bodySmall) }
            } else if (state.show != null) {
                Text("Your position", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { (0..3).forEach { group ->
                    FilterChip(selected = state.seat.group == group, onClick = { controller?.let { c -> runtime.launch { c.setSeat(state.seat.copy(group = group)) } } }, label = { Text("${group + 1}") })
                } }
                Text("Left / right · ${state.seat.x / 10}%")
                Slider(state.seat.x.toFloat(), { value -> controller?.let { c -> runtime.launch { c.setSeat(state.seat.copy(x = value.toInt())) } } }, valueRange = 0f..1000f)
                Text("Front / back · ${state.seat.y / 10}%")
                Slider(state.seat.y.toFloat(), { value -> controller?.let { c -> runtime.launch { c.setSeat(state.seat.copy(y = value.toInt())) } } }, valueRange = 0f..1000f)
            }
            Button(onClick = { fullScreen = true }, modifier = Modifier.fillMaxWidth(), enabled = state.clock != null) {
                Text("Open full screen")
            }
            OutlinedButton(onClick = { controller?.let { runtime.launch { it.leave() } } }, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.hosting) "Stop show for everyone" else "Leave show")
            }
        } else {
            Text("Join a nearby show", style = MaterialTheme.typography.titleLarge)
            if (state.offers.isEmpty()) Text(if (controller == null) "The mesh is stopped." else "Listening for hosts… Ask another phone to host a show.")
            state.offers.forEach { offer ->
                OutlinedButton(onClick = { controller?.let { runtime.launch { it.join(offer.showId) } } },
                    modifier = Modifier.fillMaxWidth(), enabled = controller != null) {
                    Text("Join ${offer.hostId} · ${offer.preset.title}")
                }
            }
            HorizontalDivider()
            Text("Host a show", style = MaterialTheme.typography.titleLarge)
            LightPreset.entries.forEach { option ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = preset == option, onClick = { preset = option })
                    Column {
                        Text(option.title, style = MaterialTheme.typography.titleMedium)
                        Text(option.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Duration", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30_000L, 60_000L, 120_000L).forEach { value ->
                    FilterChip(selected = duration == value, onClick = { duration = value }, label = { Text("${value / 1000} s") })
                }
            }
            Text("Color cycle", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Slow" to 12_000L, "Medium" to 8_000L, "Quick" to 4_000L).forEach { (label, value) ->
                    FilterChip(selected = period == value, onClick = { period = value }, label = { Text(label) })
                }
            }
            Button(onClick = { controller?.let { runtime.launch { it.host(preset, duration, period) } } },
                modifier = Modifier.fillMaxWidth(), enabled = controller != null) { Text("Host · start in 10 seconds") }
            Text(state.note, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text("Laptop control", style = MaterialTheme.typography.titleLarge)
        if (console == null) {
            OutlinedButton(onClick = { runtime.launch { runCatching { runtime.enableLightConsole() }.onFailure { controlNote = it.message ?: "Could not open the controller" } } }, enabled = controller != null) { Text("Enable USB browser console") }
            Text("Connect this phone by USB, enable USB debugging, then open the paired console on your laptop.", style = MaterialTheme.typography.bodySmall)
        } else {
            SelectionContainer { Column {
                Text("adb forward tcp:8765 tcp:8765", style = Mono)
                Text(console!!.url, style = Mono)
            } }
            Text("The paired laptop can host and conduct shows. While enabled, hosting continues when this screen closes. Disable it to revoke access and end the hosted show.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { runtime.launch { runtime.disableLightConsole() } }) { Text("Disable laptop control") }
        }
        Text("Joining is optional. Leaving this screen or putting the app in the background ends screen participation; " +
            "a host with laptop control enabled keeps conducting. " +
            "A buffered show can finish during a link drop; a remote Stop needs a working mesh connection.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (fullScreen && state.show != null) {
        Dialog(onDismissRequest = { fullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
            DisposableEffect(dialogWindow) {
                val previous = dialogWindow?.attributes?.screenBrightness
                dialogWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                dialogWindow?.let { w -> w.attributes = w.attributes.apply { screenBrightness = .8f } }
                onDispose {
                    dialogWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (previous != null) dialogWindow.attributes = dialogWindow.attributes.apply { screenBrightness = previous }
                }
            }
            Box(Modifier.fillMaxSize().background(color)) {
                Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = Color.Black.copy(alpha = .85f), contentColor = Color.White) {
                    Column(Modifier.navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stage)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TextButton(onClick = { fullScreen = false }, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Controls") }
                            TextButton(onClick = { fullScreen = false; controller?.let { runtime.launch { it.leave() } } },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) {
                                Text(if (state.hosting) "Stop show" else "Leave show")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlHint(note: String?) { note?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
