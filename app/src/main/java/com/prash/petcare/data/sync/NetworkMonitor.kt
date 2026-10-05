package com.prash.petcare.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Reports whether the device currently has usable internet. */
class NetworkMonitor(private val context: Context) {

    /** Emits true while the device has internet, false while it does not. */
    fun observeIsOnline(): Flow<Boolean> = callbackFlow {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

        if (connectivityManager == null) {
            // Should not happen on a real device.
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {

            /** A network became the default. Whether it is usable is settled below. */
            override fun onAvailable(network: Network) {
                trySend(hasUsableInternet(connectivityManager))
            }

            /** The default network went away. */
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

        // The current state, sent before any callback arrives.
        trySend(hasUsableInternet(connectivityManager))

        connectivityManager.registerDefaultNetworkCallback(callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** Whether the default network right now can reach the internet. */
    private fun hasUsableInternet(connectivityManager: ConnectivityManager): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)?.isUsableInternet() == true
    }

    /** Both halves of "actually online": the network offers internet. */
    private fun NetworkCapabilities.isUsableInternet(): Boolean =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
