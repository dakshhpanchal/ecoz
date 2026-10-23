package app.ecoz

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

/**
 * Notices that the laptop is nearby and has no internet, and offers a one-tap shortcut to the hotspot switch.
 *
 * Android does not let an ordinary app turn the hotspot on, so this can only point you at the switch.
 * The notification disappears as soon as the laptop connects, and is shown at most every five minutes.
 * The laptop broadcasts a beacon of its own while it is offline (Bluetooth only reaches a few metres); this recognises
 * it (the tag is derived from the pairing secret, so only your laptop's beacon counts) and shows a
 * notification. Scanning only runs while the laptop is out of reach, and stops once it connects.
 */
class HotspotWatcher(private val context: Context) {
    private var scanning = false
    private var token: String? = null
    private var lastOfferedAt = 0L
    private var lastWanted = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(Beacon.COMPANY_ID) ?: return
            val secret = token ?: return
            val genuine = Beacon.verify(secret, data, Beacon.ROLE_LAPTOP)
            if (result.rssi >= MIN_RSSI && genuine) offer()
        }
    }

    /** Called every few seconds: scan while [wanted], stop otherwise. [reachable] is true while the laptop is connected. */
    fun sync(enabled: Boolean, token: String?, reachable: Boolean) {
        this.token = token
        val wanted = enabled && token != null && !reachable && permitted()
        if (reachable) cancelOffer()
        if (wanted != lastWanted) {
            lastWanted = wanted
            Log.d("ecoz", "hotspot: scanning wanted=$wanted (enabled=$enabled reachable=$reachable permitted=${permitted()})")
        }
        if (wanted && !scanning) start() else if (!wanted && scanning) stop()
    }

    fun stop() {
        if (!scanning) return
        scanning = false
        runCatching { scanner()?.stopScan(callback) }
    }

    private fun start() {
        val scanner = scanner() ?: return
        // only the laptop's beacons: version 1, role 2 (the first two payload bytes)
        val filter = ScanFilter.Builder()
            .setManufacturerData(Beacon.COMPANY_ID, byteArrayOf(Beacon.VERSION.toByte(), Beacon.ROLE_LAPTOP.toByte()), byteArrayOf(-1, -1))
            .build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, callback)
            scanning = true
        } catch (_: SecurityException) {
            scanning = false
        }
    }

    private fun scanner() = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

    private fun permitted() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED

    private fun offer() {
        val now = System.currentTimeMillis()
        if (now - lastOfferedAt < COOLDOWN_MS) return
        Log.d("ecoz", "hotspot: offering the switch (interface check says on=${hotspotIsOn()}, not used)")
        lastOfferedAt = now
        val notifications = context.getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Hotspot for your laptop", NotificationManager.IMPORTANCE_HIGH))
        val open = Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { it.resolveActivity(context.packageManager) != null }
            ?: Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        notifications.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ecoz)
                .setContentTitle("Your laptop has no internet")
                .setContentText("Tap to open the hotspot switch")
                .setContentIntent(PendingIntent.getActivity(context, 7, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                .setAutoCancel(true)
                .setTimeoutAfter(10 * 60_000L)
                .build(),
        )
    }

    private fun cancelOffer() {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        lastOfferedAt = 0L
    }

    /** The hotspot shows up as an extra Wi-Fi interface (wlan1, ap0, swlan0...) holding a private address. */
    private fun hotspotIsOn(): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().any { nic ->
            nic.isUp && Regex("""(wlan[1-9]|ap\d|swlan\d|softap\d)""").matches(nic.name) &&
                nic.inetAddresses.toList().any { it is Inet4Address && it.isSiteLocalAddress }
        }
    }.getOrDefault(false)

    private companion object {
        const val CHANNEL = "hotspot"
        const val NOTIFICATION_ID = 3
        const val MIN_RSSI = -88            // Bluetooth range already means close; this just ignores very faint signals
        const val COOLDOWN_MS = 5 * 60_000L // do not nag more than once every five minutes
    }
}
