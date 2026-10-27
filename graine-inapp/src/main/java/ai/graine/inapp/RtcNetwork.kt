package ai.graine.inapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * THE NETWORK A WEBRTC CALL IS ON — what its recovery needs to know: whether
 * there is one (nothing is restarted or dialled into no network), and a word
 * when it changes (a new network is worth a new gathering).
 */
internal interface RtcNetwork {
    fun online(): Boolean

    /** Told the online state on every change — gone, back, another network — from any thread. Returns the unsubscribe. */
    fun watch(onChange: (online: Boolean) -> Unit): () -> Unit

    companion object {
        /** No watch: always online, never a change — the rules before the watch existed. */
        val NONE: RtcNetwork = object : RtcNetwork {
            override fun online(): Boolean = true
            override fun watch(onChange: (online: Boolean) -> Unit): () -> Unit = {}
        }
    }
}

/**
 * The phone's default network, from ConnectivityManager. Needs
 * ACCESS_NETWORK_STATE, which the WebRTC module's manifest adds (a call over
 * WebRTC exists only with that module). Anything it cannot read reads as
 * online: a watch must never make a working call think it has no network.
 */
internal class AndroidRtcNetwork(context: Context) : RtcNetwork {
    private val cm = (context.applicationContext ?: context).getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    override fun online(): Boolean = try {
        val c = cm
        if (c == null) true else {
            val n = c.activeNetwork
            n != null && c.getNetworkCapabilities(n)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }
    } catch (_: Exception) {
        true
    }

    override fun watch(onChange: (online: Boolean) -> Unit): () -> Unit {
        val c = cm ?: return {}
        val lock = Any()
        // The default callback reports the current network at once: that is no change.
        var current: Network? = try { c.activeNetwork } catch (_: Exception) { null }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val changed = synchronized(lock) { if (network != current) { current = network; true } else false }
                if (changed) onChange(true)
            }

            override fun onLost(network: Network) {
                val changed = synchronized(lock) { if (network == current) { current = null; true } else false }
                if (changed) onChange(online())
            }
        }
        return try {
            c.registerDefaultNetworkCallback(callback)
            val unwatch: () -> Unit = { runCatching { c.unregisterNetworkCallback(callback) } }
            unwatch
        } catch (_: Exception) {
            {}
        }
    }
}
