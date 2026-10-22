package app.handoff

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.coroutines.resume

data class Found(val host: String, val port: Int)

/**
 * Finds the paired laptop on the LAN through mDNS (`_handoff._tcp`). The announcement
 * carries a fingerprint prefix so we only pick our own laptop; the TLS pin does the real
 * authentication when we connect.
 */
object Discovery {
    private const val SERVICE_TYPE = "_handoff._tcp."
    private const val DEFAULT_TIMEOUT_MS = 5_000L

    suspend fun find(context: Context, fingerprint: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Found? {
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java) ?: return null
        val wanted = fingerprint.take(16).toByteArray()
        var listener: NsdManager.DiscoveryListener? = null
        return try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Found?> { cont ->
                    val discovery = object : NsdManager.DiscoveryListener {
                        override fun onServiceFound(info: NsdServiceInfo) = resolve(nsd, info, wanted) { found ->
                            if (cont.isActive) cont.resume(found)
                        }

                        override fun onStartDiscoveryFailed(type: String, code: Int) {
                            if (cont.isActive) cont.resume(null)
                        }

                        override fun onStopDiscoveryFailed(type: String, code: Int) {}
                        override fun onDiscoveryStarted(type: String) {}
                        override fun onDiscoveryStopped(type: String) {}
                        override fun onServiceLost(info: NsdServiceInfo) {}
                    }
                    listener = discovery
                    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
                }
            }
        } finally {
            listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        }
    }

    @Suppress("DEPRECATION") // resolveService is the only option below API 34
    private fun resolve(nsd: NsdManager, info: NsdServiceInfo, wanted: ByteArray, onFound: (Found) -> Unit) {
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(failed: NsdServiceInfo, code: Int) {
                // Android resolves one service at a time; retry if another resolve was in flight.
                if (code == NsdManager.FAILURE_ALREADY_ACTIVE) resolve(nsd, failed, wanted, onFound)
            }

            override fun onServiceResolved(resolved: NsdServiceInfo) {
                if (resolved.attributes["fp"]?.contentEquals(wanted) != true) return
                val host = ipv4(resolved) ?: return
                onFound(Found(host, resolved.port))
            }
        })
    }

    /** The service listens on IPv4 only, so ignore any IPv6 address mDNS returns first. */
    private fun ipv4(info: NsdServiceInfo): String? {
        val addresses: List<InetAddress> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) info.hostAddresses
            else listOfNotNull(@Suppress("DEPRECATION") info.host)
        return addresses.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress
    }
}

object Sender {
    /**
     * Runs [call] against the saved address; if the laptop is unreachable, looks it up via
     * mDNS, saves the new address and retries once.
     */
    suspend fun run(
        context: Context,
        store: Store,
        pairing: Pairing,
        call: suspend (Pairing) -> Outcome,
    ): Pair<Outcome, Pairing> {
        val first = call(pairing)
        if (first.ok || !first.unreachable) return first to pairing

        val found = Discovery.find(context, pairing.fingerprint)
            ?: return first.copy(message = "Can't find ${pairing.name} on this network.") to pairing
        if (found.host == pairing.host && found.port == pairing.port) return first to pairing

        val moved = pairing.copy(host = found.host, port = found.port)
        delay(150)
        val second = call(moved)
        if (second.ok) store.save(moved)
        return second to if (second.ok) moved else pairing
    }
}
