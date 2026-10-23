package app.ecoz

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Mirrors phone notifications to the laptop. Android starts and binds this itself once the
 * user grants notification access; nothing here runs unless "Mirror notifications" is on.
 */
class NotificationMirrorService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recent = HashMap<String, Pair<String, Long>>() // key -> (last text, time sent)

    /** Mirrored notifications still on the phone, so the laptop can press their buttons. */
    private val active = LinkedHashMap<String, StatusBarNotification>()

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val store = Store(applicationContext)
        if (!store.mirrorNotifications || !worthMirroring(sbn)) return
        val pairing = store.load() ?: return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        synchronized(active) {
            active[sbn.key] = sbn
            if (active.size > MAX_ACTIVE) active.remove(active.keys.first())
        }

        // Apps re-post the same notification as progress updates; don't repeat it on the laptop.
        val now = System.currentTimeMillis()
        val last = recent[sbn.key]
        if (last != null && last.first == text && now - last.second < DUPLICATE_WINDOW_MS) return
        recent[sbn.key] = text to now
        if (recent.size > MAX_RECENT) recent.remove(recent.keys.first())

        scope.launch {
            val body = JSONObject().put("key", sbn.key).put("app", appLabel(sbn)).put("title", title).put("text", text)
            body.put("package", sbn.packageName)
                .put("subtext", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty())
                .put("actions", actionsOf(sbn))
            appIcon(sbn)?.let { body.put("appIcon", it) }
            picture(sbn)?.let { body.put("image", it) }
            Sender.run(applicationContext, store, pairing) { target ->
                withContext(Dispatchers.IO) { EcozClient.post(target, "/v1/notification", body) }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(active) { active.remove(sbn.key) }
        val store = Store(applicationContext)
        if (!store.mirrorNotifications) return
        val pairing = store.load() ?: return
        if (recent.remove(sbn.key) == null) return // never mirrored, nothing to close
        scope.launch {
            Sender.run(applicationContext, store, pairing) { target ->
                withContext(Dispatchers.IO) {
                    EcozClient.post(target, "/v1/notification/dismiss", JSONObject().put("key", sbn.key))
                }
            }
        }
    }

    /** The buttons the app put on this notification (Reply, Mark as read, ...), by position. */
    private fun actionsOf(sbn: StatusBarNotification): JSONArray {
        val list = JSONArray()
        sbn.notification.actions?.forEachIndexed { index, action ->
            val title = action.title?.toString()?.trim().orEmpty()
            if (title.isEmpty() || action.actionIntent == null) return@forEachIndexed
            val reply = action.remoteInputs?.any { it.allowFreeFormInput } == true
            list.put(JSONObject().put("index", index).put("title", title).put("reply", reply))
        }
        return list
    }

    /** Presses button [index] on a mirrored notification; with [text], sends it as the reply. */
    private fun perform(key: String, index: Int, text: String?) {
        val action = synchronized(active) { active[key] }?.notification?.actions?.getOrNull(index) ?: return
        val pending = action.actionIntent ?: return
        try {
            if (text == null) {
                pending.send()
                return
            }
            val inputs = action.remoteInputs ?: return
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val fill = Intent()
            RemoteInput.addResultsToIntent(inputs, fill, results)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT)
            pending.send(applicationContext, 0, fill)
        } catch (_: PendingIntent.CanceledException) {
            // the app already dismissed or replaced the notification
        }
    }

    /** Skips our own notifications and the persistent, silent or private ones nobody wants echoed. */
    private fun worthMirroring(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification
        val noisy = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY
        if (n.flags and noisy != 0) return false
        if (n.visibility == Notification.VISIBILITY_SECRET) return false
        if (n.category == Notification.CATEGORY_CALL && Store(applicationContext).callsEnabled) return false // shown by the call card
        return when (n.category) {
            Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM -> false
            else -> true
        }
    }

    /** Notifications carry their app's info, so no package lookup (and no extra permission) is needed. */
    private fun appInfo(sbn: StatusBarNotification): ApplicationInfo? {
        val extras = sbn.notification.extras
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            extras.getParcelable("android.appInfo", ApplicationInfo::class.java)
        else @Suppress("DEPRECATION") extras.getParcelable("android.appInfo") as? ApplicationInfo
    }

    private fun appLabel(sbn: StatusBarNotification): String =
        appInfo(sbn)?.loadLabel(packageManager)?.toString() ?: sbn.packageName.substringAfterLast('.')

    /** The app's launcher icon, small, as base64 PNG. */
    private fun appIcon(sbn: StatusBarNotification): String? = runCatching {
        appInfo(sbn)?.loadIcon(packageManager)?.let { encode(it.toBitmap(APP_ICON_PX, APP_ICON_PX), Bitmap.CompressFormat.PNG, 100) }
    }.getOrNull()

    /**
     * The most informative picture: a big picture if the notification has one, otherwise the
     * sender's avatar. Chat apps put the contact's photo here, which is what made KDE Connect's
     * notifications look good.
     */
    private fun picture(sbn: StatusBarNotification): String? = runCatching {
        val n = sbn.notification
        val big = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            n.extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        else @Suppress("DEPRECATION") n.extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap
        if (big != null) return@runCatching encode(fitWithin(big, PICTURE_MAX_PX), Bitmap.CompressFormat.JPEG, 82)
        val avatar: Drawable = n.getLargeIcon()?.loadDrawable(applicationContext) ?: return@runCatching null
        encode(avatar.toBitmap(AVATAR_PX, AVATAR_PX), Bitmap.CompressFormat.PNG, 100)
    }.getOrNull()

    private fun fitWithin(bitmap: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxSide) return bitmap
        val scale = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
    }

    private fun encode(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        @Volatile private var instance: NotificationMirrorService? = null

        /** Called when the laptop presses a button; does nothing if the listener isn't bound. */
        fun invoke(key: String, index: Int, text: String?) {
            instance?.perform(key, index, text)
        }

        private const val MAX_ACTIVE = 100
        private const val DUPLICATE_WINDOW_MS = 2_000L
        private const val MAX_RECENT = 200
        private const val APP_ICON_PX = 96
        private const val AVATAR_PX = 160
        private const val PICTURE_MAX_PX = 480
    }
}
