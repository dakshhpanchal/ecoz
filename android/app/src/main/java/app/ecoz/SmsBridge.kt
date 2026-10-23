package app.ecoz

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.telephony.SmsManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Answers the laptop's requests to list, read and send text messages.
 *
 * Only plain SMS is reachable: Android keeps RCS chats ("Chat features") out of the SMS database, so
 * no other app can read them either. Texts sent from here are not saved in the Messages app, because
 * only the default SMS app may write the SMS database; the laptop keeps its own copy for the session.
 *
 * Every request is refused unless the user switched this on in the app and Android granted the
 * permission, and sending is rate-limited so a mistake on the laptop cannot send a flood.
 */
object SmsBridge {
    private const val MAX_THREADS = 40
    private const val SCAN_ROWS = 600
    private const val MAX_MESSAGES = 120
    private const val MAX_BODY_CHARS = 1500
    private const val MAX_SENDS_PER_MINUTE = 10
    private val sendTimes = ArrayDeque<Long>()
    private val nameCache = HashMap<String, String?>()

    /** Returns the JSON reply for one request: {"ok": true, "data": ...} or {"ok": false, "error": "..."}. */
    fun handle(context: Context, store: Store, request: JSONObject): JSONObject = try {
        when {
            !store.smsEnabled -> fail("Texts are switched off in the ecoz app on the phone.")
            !granted(context, Manifest.permission.READ_SMS) -> fail("Android has not allowed ecoz to read texts.")
            else -> when (request.optString("op")) {
                "threads" -> ok(threads(context))
                "messages" -> ok(messages(context, request.getLong("thread_id")))
                "send" -> send(context, request.getString("address"), request.getString("text"))
                else -> fail("Unknown request.")
            }
        }
    } catch (e: Exception) {
        fail(e.message ?: "Something went wrong reading texts.")
    }

    private fun ok(data: Any) = JSONObject().put("ok", true).put("data", data)
    private fun fail(message: String) = JSONObject().put("ok", false).put("error", message)
    private fun granted(context: Context, permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** The latest message of each conversation, newest first. */
    private fun threads(context: Context): JSONObject {
        val seen = LinkedHashMap<Long, JSONObject>()
        val unread = HashMap<Long, Int>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("thread_id", "address", "body", "date", "type", "read"),
            null, null, "date DESC LIMIT $SCAN_ROWS",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (c.getInt(4) == Telephony.Sms.MESSAGE_TYPE_INBOX && c.getInt(5) == 0) unread[id] = (unread[id] ?: 0) + 1
                if (id !in seen && seen.size < MAX_THREADS) {
                    val address = c.getString(1).orEmpty()
                    seen[id] = JSONObject()
                        .put("thread_id", id).put("address", address).put("name", displayName(context, address))
                        .put("snippet", c.getString(2).orEmpty().take(140)).put("date", c.getLong(3))
                        .put("outgoing", c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX)
                }
            }
        }
        val list = JSONArray()
        seen.forEach { (id, row) -> list.put(row.put("unread", unread[id] ?: 0)) }
        return JSONObject().put("threads", list)
    }

    /** The most recent messages of one conversation, oldest first. */
    private fun messages(context: Context, threadId: Long): JSONObject {
        val rows = ArrayList<JSONObject>()
        var address = ""
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf("_id", "address", "body", "date", "type"),
            "thread_id = ?", arrayOf(threadId.toString()), "date DESC LIMIT $MAX_MESSAGES",
        )?.use { c ->
            while (c.moveToNext()) {
                if (address.isEmpty()) address = c.getString(1).orEmpty()
                rows += JSONObject().put("id", c.getLong(0)).put("date", c.getLong(3))
                    .put("body", c.getString(2).orEmpty().take(MAX_BODY_CHARS))
                    .put("outgoing", c.getInt(4) != Telephony.Sms.MESSAGE_TYPE_INBOX)
            }
        }
        rows.reverse()
        return JSONObject().put("thread_id", threadId).put("address", address)
            .put("name", displayName(context, address)).put("messages", JSONArray(rows))
    }

    private fun send(context: Context, address: String, text: String): JSONObject {
        if (!granted(context, Manifest.permission.SEND_SMS)) return fail("Android has not allowed ecoz to send texts.")
        val number = address.trim()
        if (!Regex("""[+0-9][0-9 ()\-]{2,24}""").matches(number)) return fail("That doesn't look like a phone number.")
        if (text.isBlank() || text.length > 1600) return fail("The message is empty or too long.")
        synchronized(sendTimes) {
            val now = System.currentTimeMillis()
            while (sendTimes.isNotEmpty() && now - sendTimes.first() > 60_000) sendTimes.removeFirst()
            if (sendTimes.size >= MAX_SENDS_PER_MINUTE) return fail("Too many texts in a minute. Wait a little.")
            sendTimes.addLast(now)
        }
        val sms = context.getSystemService(SmsManager::class.java)
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
        return ok(JSONObject().put("sent", true))
    }

    /** The contact's name for a number, if Android lets us look it up. */
    private fun displayName(context: Context, address: String): String? {
        if (address.isEmpty() || !granted(context, Manifest.permission.READ_CONTACTS)) return null
        return nameCache.getOrPut(address) {
            runCatching {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
                context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
        }
    }
}
