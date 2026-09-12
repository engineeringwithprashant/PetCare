package com.prash.petcare.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Reports whether the device currently has usable internet.
 *
 * This exists so [SyncManager] can retry the moment connectivity returns rather than waiting for
 * the user to change something else or restart the app — which was the hole that let a change made
 * offline sit unsent indefinitely.
 *
 * Kept as its own class for two reasons. It is the only part of the sync that needs a [Context], so
 * isolating it keeps [SyncManager] free of Android framework types and testable with a fake flow;
 * and connectivity is a genuinely separate question from syncing, with its own awkward corners
 * (below) that would otherwise clutter the merge logic.
 *
 * Requires the ACCESS_NETWORK_STATE permission, declared in the manifest. It is a normal
 * permission, granted at install, so there is nothing to ask the user for.
 *
 * @property context Application context. An Activity's would tie the callback's lifetime to a
 *   screen, when what is wanted is the process's.
 */
class NetworkMonitor(private val context: Context) {

    /**
     * Emits true while the device has internet, false while it does not.
     *
     * **Why a callbackFlow.** [ConnectivityManager] reports changes by invoking a callback object,
     * which is the opposite shape from a flow. `callbackFlow` bridges the two: it registers the
     * callback when someone starts collecting, forwards each call into the stream, and — this is
     * the part worth having — unregisters it in [awaitClose] when collection stops. A network
     * callback that is never unregistered is a leak the system keeps alive for the life of the
     * process.
     *
     * **Why the default network.** `registerDefaultNetworkCallback` watches the one network the app
     * would actually use, rather than every network the device can see. Watching them individually
     * means handling a phone that has both Wi-Fi and mobile data, where losing one says nothing
     * about whether the device is still online.
     *
     * **Why capabilities, not just "available".** A network can be connected and still lead
     * nowhere — a hotel Wi-Fi sign-in page is the classic case. NET_CAPABILITY_VALIDATED is the
     * system's own verdict that traffic really reaches the internet, and it is what stops the sync
     * being woken up to fail against a captive portal. It cannot be asked for in advance, only
     * observed, which is why it is checked here rather than declared in a NetworkRequest.
     *
     * [distinctUntilChanged] matters because these callbacks fire far more often than connectivity
     * actually changes — capabilities are re-reported as signal strength and transports shift —
     * and every duplicate would otherwise be a reason to sync.
     */
    fun observeIsOnline(): Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        if (connectivityManager == null) {
            // Should not happen on a real device, but the API is nullable and a missing service is
            // no reason to crash. Reporting "offline" simply means the sync relies on its other
            // triggers instead.
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {

            /** A network became the default. Whether it is usable is settled below. */
            override fun onAvailable(network: Network) {
                trySend(hasUsableInternet(connectivityManager))
            }

            /**
             * The default network went away.
             *
             * Re-checked rather than assumed offline: losing Wi-Fi usually means falling back to
             * mobile data, and the system may have already handed over by the time this arrives.
             */
            override fun onLost(network: Network) {
                trySend(hasUsableInternet(connectivityManager))
            }

            /** The default network's abilities changed — validated, or no longer validated. */
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                trySend(capabilities.isUsableInternet())
            }
        }

        // The current state, sent before any callback arrives, so a collector is never left waiting
        // for the connection to change before it learns anything at all.
        trySend(hasUsableInternet(connectivityManager))

        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** Whether the default network right now can reach the internet. */
    private fun hasUsableInternet(connectivityManager: ConnectivityManager): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)?.isUsableInternet() == true
    }

    /**
     * Both halves of "actually online": the network offers internet, and the system has confirmed
     * that traffic over it arrives somewhere.
     */
    private fun NetworkCapabilities.isUsableInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
