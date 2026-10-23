package app.ecoz

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Phone calls on the laptop: tells it when a call rings, starts and ends, and carries out the
 * laptop's answer, decline, hang up and dial commands.
 *
 * Only the call itself is handled here, never its audio: Android does not let apps capture call
 * audio. Hearing the call on the laptop needs the phone connected to it over Bluetooth hands-free.
 *
 * Off until the user switches it on in the app and allows each permission. Dialing needs a command
 * from the laptop, and the laptop only accepts those from processes running on the laptop itself.
 */
object CallBridge {
    val PERMISSIONS = arrayOf(
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
    )
    private val NUMBER = Regex("""[+0-9][0-9 ()\-]{2,24}""")

    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Carries out one command from the laptop: {"action": "answer" | "decline" | "hangup" | "dial", "number": ...}. */
    @SuppressLint("MissingPermission")
    fun control(context: Context, scope: CoroutineScope, store: Store, raw: String) {
        val command = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val pairing = store.load() ?: return
        fun fail(message: String) {
            scope.launch(Dispatchers.IO) {
                EcozClient.post(pairing, "/v1/call", JSONObject().put("state", "error").put("message", message))
            }
        }
        if (!store.callsEnabled) return fail("Calls are switched off in the ecoz app on the phone.")
        val telecom = context.getSystemService(TelecomManager::class.java)
        try {
            when (command.optString("action")) {
                "answer" -> {
                    if (!granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) return fail("Android has not allowed ecoz to answer calls.")
                    telecom.acceptRingingCall()
                }
                "decline", "hangup" -> {
                    if (!granted(context, Manifest.permission.ANSWER_PHONE_CALLS)) return fail("Android has not allowed ecoz to end calls.")
                    if (!telecom.endCall()) fail("There was no call to end.")
                }
                "dial" -> {
                    val number = command.optString("number")
                    if (!NUMBER.matches(number)) return fail("That doesn't look like a phone number.")
                    if (!granted(context, Manifest.permission.CALL_PHONE)) return fail("Android has not allowed ecoz to make calls.")
                    lastDialed = number
                    telecom.placeCall(Uri.fromParts("tel", number, null), null)
                }
            }
        } catch (e: SecurityException) {
            fail("Android refused: ${e.message ?: "permission denied"}")
        }
    }

    @Volatile private var lastDialed: String? = null

    /**
     * Watches the phone's call state while the service runs. Registered only while the feature is on
     * and permitted, so it asks for nothing otherwise.
     */
    class Watcher(private val context: Context, private val scope: CoroutineScope) {
        private var registered = false
        private var lastState = ""
        private var lastNumber = ""
        private var ringing = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
                handle(state, number)
            }
        }

        /** Call each tick: listens while [wanted], stops otherwise. */
        fun sync(wanted: Boolean) {
            val allowed = wanted && granted(context, Manifest.permission.READ_PHONE_STATE)
            if (allowed && !registered) {
                ContextCompat.registerReceiver(
                    context, receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED,
                )
                registered = true
            } else if (!allowed && registered) stop()
        }

        fun stop() {
            if (!registered) return
            registered = false
            runCatching { context.unregisterReceiver(receiver) }
        }

        private fun handle(androidState: String, number: String) {
            val pairing = Store(context).load() ?: return
            val (state, who) = when (androidState) {
                TelephonyManager.EXTRA_STATE_RINGING -> { ringing = true; "ringing" to number }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    val n = if (ringing) lastNumber else lastDialed.orEmpty()
                    ringing = false
                    "active" to n
                }
                TelephonyManager.EXTRA_STATE_IDLE -> { ringing = false; lastDialed = null; lastNumber = ""; "ended" to "" }
                else -> return
            }
            val key = "$state/$who"
            if (key == lastState) return // Android sends each change more than once
            lastState = if (state == "ended") "" else key
            if (who.isNotEmpty()) lastNumber = who
            scope.launch(Dispatchers.IO) {
                val body = JSONObject().put("state", state).put("number", who)
                SmsBridge.contactName(context, who)?.let { body.put("name", it) }
                EcozClient.post(pairing, "/v1/call", body)
            }
        }
    }
}
