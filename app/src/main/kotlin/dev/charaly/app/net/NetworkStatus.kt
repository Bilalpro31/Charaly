package dev.charaly.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the device can currently reach the internet.
 *
 * ## Why the product needs this, and only this
 *
 * Exactly one feature needs the network: discovering and downloading a model from the
 * Hugging Face Hub. Everything that makes up a story - world state, memory, knowledge,
 * the event log, and inference itself - is local and always works.
 *
 * So the indicator's job is narrow and important: to stop the Model Library offering a
 * download that is about to fail, and to say plainly what still works when it cannot run.
 * It is not a health check, and it does not attempt to be - a captive portal is
 * indistinguishable from a working connection until a request is made, which is why
 * [dev.charaly.runtime.net.NetworkCapability.offline] treats the pessimistic reading as the
 * default.
 *
 * ## Why a flow rather than a polled boolean
 *
 * `registerDefaultNetworkCallback` delivers changes as they happen, so the indicator
 * updates when connectivity changes instead of whenever a screen happens to open. It also
 * means the app holds no timer, which matters for something read as often as this.
 */
class NetworkStatus(context: Context) {

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * The current state, read once.
     *
     * A missing `ConnectivityManager` - which should not happen on any supported device -
     * reports false rather than throwing, because the pessimistic answer is also the safe
     * one: the Hub is offered a retry rather than a download that cannot finish.
     */
    fun isOnline(): Boolean {
        val manager = connectivity ?: return false
        val active = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Connectivity as a stream, starting with the current value.
     *
     * [NET_CAPABILITY_VALIDATED] rather than merely [NET_CAPABILITY_INTERNET], because a
     * network that is configured but cannot actually validate - a hotel captive portal, a
     * plane's wifi - will fail every request, and reporting it as "online" would produce a
     * search that times out instead of an honest offline state.
     */
    fun observe(): Flow<Boolean> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(isOnline())
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onUnavailable() {
                trySend(false)
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                trySend(isOnline())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        trySend(isOnline())
        manager.registerNetworkCallback(request, callback)
        awaitClose {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }.distinctUntilChanged()
}