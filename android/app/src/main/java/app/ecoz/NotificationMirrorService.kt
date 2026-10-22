package app.ecoz

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
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
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Mirrors phone notifications to the laptop. Android starts and binds this itself once the
 * user grants notification access; nothing here runs unless "Mirror notifications" is on.
 */
class NotificationMirrorService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val recent = HashMap<String, Pair<String, Long>>() // key -> (last text, time sent)

    override fun onDestroy() {
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

        // Apps re-post the same notification as progress updates; don't repeat it on the laptop.
        val now = System.currentTimeMillis()
        val last = recent[sbn.key]
        if (last != null && last.first == text && now - last.second < DUPLICATE_WINDOW_MS) return
        recent[sbn.key] = text to now
        if (recent.size > MAX_RECENT) recent.remove(recent.keys.first())

        scope.launch {
            val body = JSONObject().put("key", sbn.key).put("app", appLabel(sbn)).put("title", title).put("text", text)
            appIcon(sbn)?.let { body.put("appIcon", it) }
            picture(sbn)?.let { body.put("image", it) }
            Sender.run(applicationContext, store, pairing) { target ->
                withContext(Dispatchers.IO) { EcozClient.post(target, "/v1/notification", body) }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
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

    /** Skips our own notifications and the persistent, silent or private ones nobody wants echoed. */
    private fun worthMirroring(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification
        val noisy = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY
        if (n.flags and noisy != 0) return false
        if (n.visibility == Notification.VISIBILITY_SECRET) return false
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

    private companion object {
        const val DUPLICATE_WINDOW_MS = 2_000L
        const val MAX_RECENT = 200
        const val APP_ICON_PX = 96
        const val AVATAR_PX = 160
        const val PICTURE_MAX_PX = 480
    }
}
