package app.ecoz

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URI

/** Connection status shown on the main screen. */
object ListenerState {
    val status = MutableStateFlow("Off")
}

/**
 * Keeps an outbound connection to the laptop so it can push links and text to the phone.
 * The phone is always the one connecting, so nothing needs to be reachable on the phone.
 */
class ListenerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handle = StreamHandle()
    private val seen = LinkedHashSet<String>() // event ids already shown (the laptop may re-send)
    private var job: Job? = null
    private lateinit var notifications: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notifications = getSystemService(NotificationManager::class.java)
        createChannels()
        ServiceCompat.startForeground(
            this, FOREGROUND_ID, foregroundNotification("Connecting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (job == null) job = scope.launch { connectLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        handle.close() // unblocks the read so the loop can exit
        scope.cancel()
        ListenerState.status.value = "Off"
        super.onDestroy()
    }

    private suspend fun connectLoop() {
        val store = Store(applicationContext)
        var backoffMs = INITIAL_BACKOFF_MS
        while (scope.isActive) {
            val pairing = store.load()
            if (pairing == null) {
                setStatus("Not paired")
                stopSelf()
                return
            }
            setStatus("Connecting to ${pairing.name}…")
            val outcome = EcozClient.listen(
                pairing, handle,
                onConnected = {
                    backoffMs = INITIAL_BACKOFF_MS
                    setStatus("Connected to ${pairing.name}")
                },
                onEvent = { event -> handleEvent(pairing, event) },
            )
            if (!scope.isActive) return

            if (outcome.unreachable) {
                setStatus("Looking for ${pairing.name}…")
                val found = Discovery.find(applicationContext, pairing.fingerprint)
                if (found != null && (found.host != pairing.host || found.port != pairing.port)) {
                    store.save(pairing.copy(host = found.host, port = found.port))
                    backoffMs = INITIAL_BACKOFF_MS
                    continue
                }
            }
            setStatus("${outcome.message} Retrying…")
            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun handleEvent(pairing: Pairing, event: JSONObject) {
        val id = event.optString("id")
        val value = event.optString("value")
        if (id.isNotEmpty() && seen.add(id)) {
            if (seen.size > MAX_SEEN) seen.remove(seen.first())
            when (event.optString("type")) {
                "url" -> showLink(id, value)
                "text" -> copyText(id, value)
                "clip" -> if (Store(applicationContext).clipboardSync) ClipboardSync.apply(applicationContext, value)
                "pull" -> showPullRequest(id)
            }
        }
        // Ack even duplicates, otherwise the laptop keeps re-sending them.
        if (id.isNotEmpty()) EcozClient.ack(pairing, id)
    }

    private fun showLink(id: String, url: String) {
        val uri = Uri.parse(url)
        val open = PendingIntent.getActivity(
            this, id.hashCode(), Intent(Intent.ACTION_VIEW, uri),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val host = runCatching { URI(url).host }.getOrNull() ?: url
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_ecoz)
            .setContentTitle("Link from laptop")
            .setContentText(host)
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        notifications.notify(id.hashCode(), notification)
    }

    /** The laptop wants our clipboard. Only a tap can grant that, since reading needs focus. */
    private fun showPullRequest(id: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_ecoz)
            .setContentTitle("Laptop wants your clipboard")
            .setContentText("Copy something, then tap to send it")
            .setContentIntent(sendClipboardIntent())
            .addAction(0, "Send clipboard", sendClipboardIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(PULL_REQUEST_MS)
            .build()
        notifications.notify(id.hashCode(), notification)
    }

    private fun sendClipboardIntent(): PendingIntent = PendingIntent.getActivity(
        this, 1, Intent(this, ClipboardSendActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun copyText(id: String, text: String) {
        // Writing the clipboard from the background is allowed; only reading is restricted.
        ClipboardSync.apply(applicationContext, text)
        val notification = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_stat_ecoz)
            .setContentTitle("Copied from laptop")
            .setContentText(text.take(80))
            .setAutoCancel(true)
            .setTimeoutAfter(TEXT_NOTICE_MS)
            .build()
        notifications.notify(id.hashCode(), notification)
    }

    private fun setStatus(text: String) {
        ListenerState.status.value = text
        notifications.notify(FOREGROUND_ID, foregroundNotification(text))
    }

    private fun foregroundNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_ecoz)
            .setContentTitle("ecoz")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Send clipboard", sendClipboardIntent())
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Connection status", NotificationManager.IMPORTANCE_LOW)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_INCOMING, "From laptop", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    companion object {
        private const val CHANNEL_SERVICE = "service"
        private const val CHANNEL_INCOMING = "incoming"
        private const val FOREGROUND_ID = 1
        private const val INITIAL_BACKOFF_MS = 2_000L
        private const val MAX_BACKOFF_MS = 30_000L
        private const val MAX_SEEN = 100
        private const val TEXT_NOTICE_MS = 10_000L
        private const val PULL_REQUEST_MS = 120_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ListenerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ListenerService::class.java))
        }
    }
}
