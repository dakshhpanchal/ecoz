package app.ecoz

import android.content.Context
import android.net.Uri

/** Everything needed to talk to one laptop: address, secret token, pinned cert. */
data class Pairing(
    val host: String,
    val port: Int,
    val token: String,
    val fingerprint: String,
    val name: String,
) {
    companion object {
        private val SHA256_HEX = Regex("[0-9a-f]{64}")

        /** Parses `ecoz://host:port/<token>/<sha256-hex>?name=<laptop>`. */
        fun parse(code: String): Pairing? {
            val uri = runCatching { Uri.parse(code.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "ecoz") return null
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            val segments = uri.pathSegments
            if (segments.size != 2) return null
            val fingerprint = segments[1].lowercase()
            if (!SHA256_HEX.matches(fingerprint)) return null
            return Pairing(host, port, segments[0], fingerprint, uri.getQueryParameter("name") ?: host)
        }
    }
}

/** Private-storage persistence for the single paired laptop. */
class Store(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ecoz", Context.MODE_PRIVATE)

    fun load(): Pairing? {
        val host = prefs.getString("host", null) ?: return null
        val token = prefs.getString("token", null) ?: return null
        val fingerprint = prefs.getString("fingerprint", null) ?: return null
        return Pairing(
            host = host,
            port = prefs.getInt("port", 0),
            token = token,
            fingerprint = fingerprint,
            name = prefs.getString("name", host) ?: host,
        )
    }

    /** Whether the user wants the always-on connection that receives items from the laptop. */
    var listenEnabled: Boolean
        get() = prefs.getBoolean("listen", false)
        set(value) = prefs.edit().putBoolean("listen", value).apply()

    /** This phone may answer the laptop's unlock challenges. Needs the laptop to have approved its key. */
    var unlockEnabled: Boolean
        get() = prefs.getBoolean("unlock", false)
        set(value) = prefs.edit().putBoolean("unlock", value).apply()

    /** Broadcast the proximity beacon (for lock/unlock and hotspot help). Off until the user turns it on. */
    var proximityEnabled: Boolean
        get() = prefs.getBoolean("proximity", false)
        set(value) = prefs.edit().putBoolean("proximity", value).apply()

    /** Ask before accepting files the laptop sends. Each device decides this for itself. */
    var confirmIncomingFiles: Boolean
        get() = prefs.getBoolean("confirm_incoming_files", true)
        set(value) = prefs.edit().putBoolean("confirm_incoming_files", value).apply()

    /** Show phone notifications on the laptop. */
    var mirrorNotifications: Boolean
        get() = prefs.getBoolean("mirror_notifications", false)
        set(value) = prefs.edit().putBoolean("mirror_notifications", value).apply()

    /** Keep the two clipboards in step automatically. */
    var clipboardSync: Boolean
        get() = prefs.getBoolean("clipboard_sync", true)
        set(value) = prefs.edit().putBoolean("clipboard_sync", value).apply()

    fun save(p: Pairing) = prefs.edit()
        .putString("host", p.host)
        .putInt("port", p.port)
        .putString("token", p.token)
        .putString("fingerprint", p.fingerprint)
        .putString("name", p.name)
        .apply()

    fun clear() = prefs.edit().clear().apply()
}
