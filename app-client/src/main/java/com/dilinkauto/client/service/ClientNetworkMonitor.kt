package com.dilinkauto.client.service

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.dilinkauto.client.FileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Monitors WiFi network changes with debounced disconnection/reconnection logic.
 *
 * Extracted from [ConnectionService] (SRP): separates network state observation and
 * debounce timing from the core connection service lifecycle.
 */
class ClientNetworkMonitor(
    private val connectivityManager: ConnectivityManager,
    private val scope: CoroutineScope,
    private val debounceDelayMs: Long = DEFAULT_DEBOUNCE_MS,
    private val listener: Listener
) {
    interface Listener {
        fun onNetworkAvailable(network: Network)
        fun onNetworkLostConfirmed(network: Network)
        fun isConnectionActive(): Boolean
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var debounceJob: Job? = null

    fun start() {
        if (networkCallback != null) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                FileLog.i(TAG, "Network available: $network")
                cancelDebounce()
                listener.onNetworkAvailable(network)
            }

            override fun onLost(network: Network) {
                if (listener.isConnectionActive()) {
                    val activeNet = connectivityManager.activeNetwork
                    if (activeNet != null && activeNet != network) {
                        FileLog.i(TAG, "Network lost: $network (not our active network $activeNet — ignoring)")
                        return
                    }
                }
                FileLog.i(TAG, "Network lost: $network — debouncing before reacting")
                cancelDebounce()
                debounceJob = scope.launch {
                    delay(debounceDelayMs)
                    if (!listener.isConnectionActive()) {
                        FileLog.i(TAG, "Network lost confirmed — resetting connection")
                    }
                    listener.onNetworkLostConfirmed(network)
                }
            }
        }

        try {
            connectivityManager.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            FileLog.w(TAG, "Failed to register network callback: ${e.message}")
        }
    }

    fun cancelDebounce() {
        debounceJob?.cancel()
        debounceJob = null
    }

    fun stop() {
        cancelDebounce()
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                FileLog.w(TAG, "Failed to unregister network callback: ${e.message}")
            }
        }
        networkCallback = null
    }

    companion object {
        private const val TAG = "ClientNetworkMonitor"
        const val DEFAULT_DEBOUNCE_MS = 3000L
    }
}
