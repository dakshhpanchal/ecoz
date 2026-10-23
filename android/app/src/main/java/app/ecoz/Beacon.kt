package app.ecoz

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Broadcasts a small Bluetooth beacon so the laptop can tell the phone is close.
 *
 * The payload is a version, a role and an 8-byte tag = HMAC(key, role || 30-second counter).
 * The key is derived from the pairing token, so only the paired laptop can recognise the beacon,
 * and because the tag changes every 30 seconds nobody can follow the phone around by it. It carries
 * no name, no address you could use, and no data: it only proves "the phone is here".
 */
class Beacon(private val context: Context) {
    private var callback: AdvertiseCallback? = null
    private var currentCounter = -1L

    /** Makes the advertising match [enabled]: starts it, rotates the tag when the window changes, or stops it. */
    fun sync(enabled: Boolean, token: String?) {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (!enabled || token == null || adapter == null || !adapter.isEnabled || !permitted()) {
            stop()
            return
        }
        val counter = System.currentTimeMillis() / 1000 / WINDOW_SECONDS
        if (callback != null && counter == currentCounter) return
        stop()
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addManufacturerData(COMPANY_ID, payload(token, counter))
            .build()
        val started = object : AdvertiseCallback() {
            override fun onStartFailure(errorCode: Int) {
                Log.w(TAG, "beacon not started: $errorCode")
                callback = null
            }
        }
        try {
            advertiser.startAdvertising(settings, data, started)
            callback = started
            currentCounter = counter
        } catch (e: SecurityException) {
            callback = null
        }
    }

    fun stop() {
        val running = callback ?: return
        callback = null
        runCatching {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeAdvertiser?.stopAdvertising(running)
        }
    }

    private fun permitted() =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "ecoz"
        const val COMPANY_ID = 0xFFFF        // reserved for internal use / testing
        const val VERSION = 1
        const val ROLE_PHONE = 1
        const val ROLE_LAPTOP = 2
        private const val WINDOW_SECONDS = 30L

        private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(message) }

        fun beaconKey(token: String): ByteArray = hmac(token.toByteArray(), "ecoz-beacon-v1".toByteArray())

        /** True if [data] is a genuine beacon with [role] from the paired device (tag checked for the current and adjacent windows). */
        fun verify(token: String, data: ByteArray, role: Int): Boolean {
            if (data.size != 10 || data[0].toInt() != VERSION || data[1].toInt() != role) return false
            val now = System.currentTimeMillis() / 1000 / WINDOW_SECONDS
            return (now - 1..now + 1).any { counter ->
                val message = ByteBuffer.allocate(9).put(role.toByte()).putLong(counter).array()
                MessageDigest.isEqual(hmac(beaconKey(token), message).copyOf(8), data.copyOfRange(2, 10))
            }
        }

        fun payload(token: String, counter: Long): ByteArray {
            val message = ByteBuffer.allocate(9).put(ROLE_PHONE.toByte()).putLong(counter).array()
            val tag = hmac(beaconKey(token), message).copyOf(8)
            return byteArrayOf(VERSION.toByte(), ROLE_PHONE.toByte()) + tag
        }
    }
}
