package dev.meshmind.app.ui

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.meshmind.app.MeshMindApp
import dev.meshmind.app.MeshRuntime
import dev.meshmind.app.sim.SimulationController
import dev.meshmind.core.routing.MeshSnapshot
import kotlinx.coroutines.flow.MutableStateFlow

enum class Screen(val title: String) {
    HOME("MeshMind"), MESSAGES("Messages"), SOS("SOS"), ASK("Ask & Live info"), GATEWAY("Share my Internet"),
    IDENTITY("Identity & trust"), RAW_INTERNET("Raw Internet tools"),
    NETWORK("Network"), DEBUG("Debug"), SIMULATION("Simulation"), LIGHT_SHOW("Light Show"),
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val runtime = (application as MeshMindApp).runtime
        setContent { MeshTheme { MeshApp(runtime) } }
    }
}

private val simulation by lazy { SimulationController() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshApp(runtime: MeshRuntime) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }

    val router by runtime.router.collectAsStateWithLifecycle()
    val nodeId by runtime.nodeId.collectAsStateWithLifecycle()
    val snapFlow = remember(router, nodeId) { router?.state ?: MutableStateFlow(MeshSnapshot(nodeId)) }
    val snap by snapFlow.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (screen == Screen.HOME) "MeshMind · $nodeId" else screen.title) },
                navigationIcon = {
                    if (screen != Screen.HOME) IconButton(onClick = { screen = Screen.HOME }) { Text("←", fontSize = 22.sp) }
                },
            )
        },
    ) { padding ->
        // Android 15+ is edge-to-edge: the keyboard no longer resizes the window, so pad for the IME ourselves.
        Box(Modifier.padding(padding).consumeWindowInsets(padding).imePadding()) {
            when (screen) {
                Screen.HOME -> HomeScreen(runtime, snap, router != null) { screen = it }
                Screen.MESSAGES -> MessagesScreen(router, snap)
                Screen.SOS -> SosScreen(router, snap)
                Screen.ASK -> AskScreen(runtime, router, snap) { screen = it }
                Screen.GATEWAY -> GatewayScreen(runtime, router, snap)
                Screen.IDENTITY -> IdentityScreen(runtime, snap)
                Screen.RAW_INTERNET -> RawInternetScreen(runtime, router, snap)
                Screen.NETWORK -> NetworkScreen(runtime, snap)
                Screen.DEBUG -> DebugScreen(router, snap, runtime) { screen = Screen.RAW_INTERNET }
                Screen.SIMULATION -> SimulationScreen(simulation)
                Screen.LIGHT_SHOW -> LightShowScreen(runtime)
            }
        }
    }
}
