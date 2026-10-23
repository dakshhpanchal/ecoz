package app.ecoz

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moves files between the phone and the laptop over the same pinned-TLS link as everything else,
 * with a progress notification for each transfer. Downloads are saved to the phone's Downloads
 * folder through MediaStore, which needs no storage permission.
 */
object Transfers {
    private val nextId = AtomicInteger(1000)
    private val running = AtomicInteger(0)

    /** Called when the last transfer finishes, so a service started only for it can stop. */
    @Volatile var onIdle: (() -> Unit)? = null

    // ---- phone -> laptop --------------------------------------------------------------

    fun upload(context: Context, scope: CoroutineScope, store: Store, uris: List<Uri>) {
        val pairing = store.load() ?: return
        for (uri in uris) {
            running.incrementAndGet()
            scope.launch(Dispatchers.IO) {
                try {
                    uploadOne(context, store, pairing, uri)
                } finally {
                    if (running.decrementAndGet() == 0) onIdle?.invoke()
                }
            }
        }
    }

    private suspend fun uploadOne(context: Context, store: Store, pairing: Pairing, uri: Uri) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val resolver = context.contentResolver
        val (name, knownSize) = describe(resolver, uri)
        val id = nextId.incrementAndGet()
        val progress = ProgressNotice(context, notifications, id, "Sending $name", pairing.name)

        // The server needs the exact length up front; spool to cache only if the source can't say.
        var spooled: File? = null
        val size = if (knownSize >= 0) knownSize else run {
            val file = File.createTempFile("upload", null, context.cacheDir).also { spooled = it }
            resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            file.length()
        }
        try {
            val outcome = Sender.run(context, store, pairing) { target ->
                withContext(Dispatchers.IO) {
                    val source = spooled?.inputStream() ?: resolver.openInputStream(uri)
                        ?: return@withContext Outcome(false, "Couldn't open $name.")
                    source.use { EcozClient.upload(target, name, size, it) { sent -> progress.update(sent, size) } }
                }
            }.first
            progress.finish(if (outcome.ok) "Sent $name" else "Couldn't send $name", if (outcome.ok) "Saved on ${pairing.name}" else outcome.message)
        } catch (e: Exception) {
            progress.finish("Couldn't send $name", e.message ?: "Unexpected error")
        } finally {
            spooled?.delete()
        }
    }

    private fun describe(resolver: ContentResolver, uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        return name to size
    }

    // ---- laptop -> phone --------------------------------------------------------------

    fun receive(context: Context, scope: CoroutineScope, store: Store, fileId: String, rawName: String, size: Long) {
        val pairing = store.load() ?: return
        running.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                receiveOne(context, store, pairing, fileId, cleanName(rawName), size)
            } finally {
                if (running.decrementAndGet() == 0) onIdle?.invoke()
            }
        }
    }

    private suspend fun receiveOne(context: Context, store: Store, pairing: Pairing, fileId: String, name: String, size: Long) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val resolver = context.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val id = nextId.incrementAndGet()
        val progress = ProgressNotice(context, notifications, id, "Receiving $name", pairing.name)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until it is complete
        }
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (target == null) {
            progress.finish("Couldn't save $name", "No room to create the file in Downloads.")
            return
        }
        try {
            val outcome = Sender.run(context, store, pairing) { source ->
                withContext(Dispatchers.IO) {
                    val sink = resolver.openOutputStream(target, "wt")
                        ?: return@withContext Outcome(false, "Couldn't write to Downloads.")
                    sink.use { EcozClient.download(source, fileId, size, it) { got -> progress.update(got, size) } }
                }
            }.first
            if (!outcome.ok) throw java.io.IOException(outcome.message)
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            progress.finish("Received $name", "Saved to Downloads. Tap to open.", openIntent(context, target, mime))
        } catch (e: Exception) {
            runCatching { resolver.delete(target, null, null) } // never leave a partial file in Downloads
            progress.finish("Couldn't receive $name", e.message ?: "Unexpected error")
        }
    }

    /** A name from the laptop reduced to something safe to create. */
    private fun cleanName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() }.trim().ifEmpty { "file" }.take(200)

    private fun openIntent(context: Context, uri: Uri, mime: String): PendingIntent = PendingIntent.getActivity(
        context, uri.hashCode(),
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** One transfer's notification: a progress bar while it runs, a result when it ends. */
    private class ProgressNotice(
        private val context: Context,
        private val notifications: NotificationManager,
        private val id: Int,
        private val title: String,
        private val peer: String,
    ) {
        private var lastUpdateMs = 0L

        fun update(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateMs < UPDATE_EVERY_MS && done < total) return
            lastUpdateMs = now
            val builder = NotificationCompat.Builder(context, ListenerService.CHANNEL_TRANSFER)
                .setSmallIcon(R.drawable.ic_stat_ecoz)
                .setContentTitle(title)
                .setContentText(if (total > 0) "${done * 100 / total}%  ·  $peer" else peer)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(if (total > 0) 1000 else 0, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
            notifications.notify(id, builder.build())
        }

        fun finish(headline: String, detail: String, tap: PendingIntent? = null) {
            val builder = NotificationCompat.Builder(context, ListenerService.CHANNEL_TRANSFER)
                .setSmallIcon(R.drawable.ic_stat_ecoz)
                .setContentTitle(headline)
                .setContentText(detail)
                .setAutoCancel(true)
                .setOngoing(false)
                .setProgress(0, 0, false)
            tap?.let { builder.setContentIntent(it) }
            notifications.notify(id, builder.build())
        }

        private companion object { const val UPDATE_EVERY_MS = 400L }
    }
}
