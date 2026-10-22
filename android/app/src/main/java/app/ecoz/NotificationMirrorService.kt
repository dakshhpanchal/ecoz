package app.ecoz

import android.app.Notification
import android.content.pm.ApplicationInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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

        val body = JSONObject().put("key", sbn.key).put("app", appLabel(sbn)).put("title", title).put("text", text)
        scope.launch {
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

    /** The app's display name. Notifications carry their app's info, so no package lookup is needed. */
    private fun appLabel(sbn: StatusBarNotification): String {
        val extras = sbn.notification.extras
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            extras.getParcelable("android.appInfo", ApplicationInfo::class.java)
        else @Suppress("DEPRECATION") extras.getParcelable("android.appInfo") as? ApplicationInfo
        return info?.loadLabel(packageManager)?.toString() ?: sbn.packageName.substringAfterLast('.')
    }

    private companion object {
        const val DUPLICATE_WINDOW_MS = 2_000L
        const val MAX_RECENT = 200
    }
}
