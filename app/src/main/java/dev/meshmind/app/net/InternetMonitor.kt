package dev.meshmind.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Does this phone really have Internet?" Uses the OS's own captive-portal/validation probe
 * (NET_CAPABILITY_VALIDATED), which costs nothing extra, plus a slow 60 s re-check in case callbacks are missed.
 */
class InternetMonitor(context: Context, scope: CoroutineScope) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val _validated = MutableStateFlow(check())
    val validated: StateFlow<Boolean> = _validated.asStateFlow()

    init {
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    _validated.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }

                override fun onLost(network: Network) { _validated.value = check() }
            })
        }
        scope.launch {
            while (true) {
                delay(60_000)
                _validated.value = check()
            }
        }
    }

    private fun check(): Boolean {
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
