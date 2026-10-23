package app.ecoz

import android.app.DownloadManager
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
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** A file the laptop has offered to send, as described in its offer. */
data class IncomingFile(val id: String, val name: String, val size: Long)

/**
 * Moves files between the phone and the laptop over the same pinned-TLS link as everything else.
 *
 * Both directions ask first. Sending to the laptop starts with an offer the laptop's owner can
 * accept or decline; no data moves until it is accepted. Files from the laptop are only fetched
 * once this phone's own setting (or the user, via the notification) has accepted them. A batch is
 * one question and one progress notification. Incoming files are saved to Downloads/laptop
 * through MediaStore, which needs no storage permission.
 */
object Transfers {
    /** Files from the laptop are kept together in Downloads/laptop. */
    private const val RECEIVE_FOLDER = "laptop"

    private val nextId = AtomicInteger(1000)
    private val running = AtomicInteger(0)

    /** Called when the last transfer finishes, so a service started only for it can stop. */
    @Volatile var onIdle: (() -> Unit)? = null

    private fun tracked(scope: CoroutineScope, block: suspend () -> Unit) {
        running.incrementAndGet()
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } finally {
                if (running.decrementAndGet() == 0) onIdle?.invoke()
            }
        }
    }

    // ---- phone -> laptop --------------------------------------------------------------

    private class Outgoing(val uri: Uri, val name: String, val size: Long, val spooled: File?)

    fun upload(context: Context, scope: CoroutineScope, store: Store, uris: List<Uri>) {
        val pairing = store.load() ?: return
        tracked(scope) { uploadBatch(context, store, pairing, uris) }
    }

    private suspend fun uploadBatch(context: Context, store: Store, pairing: Pairing, uris: List<Uri>) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val resolver = context.contentResolver
        val items = uris.map { describe(context, resolver, it) }
        val title = if (items.size == 1) items[0].name else "${items.size} files"
        val notice = ProgressNotice(context, notifications, nextId.incrementAndGet(), peer = pairing.name)
        try {
            // Step 1: ask. The laptop shows Accept / Decline and answers; nothing is sent until then.
            notice.waiting("Waiting for ${pairing.name} to accept $title…")
            var offer = Offer(Outcome(false, "No answer"))
            val asked = Sender.run(context, store, pairing) { target ->
                withContext(Dispatchers.IO) {
                    offer = EcozClient.offerFiles(target, items.map { it.name to it.size })
                    offer.outcome
                }
            }.first
            if (!asked.ok) return notice.finish("Couldn't send $title", asked.message)
            if (!offer.accepted || offer.tickets.size != items.size) {
                return notice.finish("${pairing.name} didn't accept $title", "Nothing was sent.")
            }

            // Step 2: stream each approved file with its one-time ticket.
            val total = items.sumOf { it.size }
            var finished = 0L
            for ((index, item) in items.withIndex()) {
                val source = item.spooled?.inputStream() ?: resolver.openInputStream(item.uri)
                    ?: return notice.finish("Couldn't send ${item.name}", "The file couldn't be opened.")
                val outcome = source.use {
                    EcozClient.upload(pairing, offer.tickets[index], item.size, it) { sent ->
                        notice.progress("Sending $title", finished + sent, total)
                    }
                }
                if (!outcome.ok) return notice.finish("Couldn't send ${item.name}", outcome.message)
                finished += item.size
            }
            notice.finish("Sent $title", "Saved on ${pairing.name}")
        } catch (e: Exception) {
            notice.finish("Couldn't send $title", e.message ?: "Unexpected error")
        } finally {
            items.forEach { it.spooled?.delete() }
        }
    }

    /** Name and exact size of a shared file. The laptop needs the size up front, so spool if unknown. */
    private fun describe(context: Context, resolver: ContentResolver, uri: Uri): Outgoing {
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
        if (size >= 0) return Outgoing(uri, name, size, null)
        val file = File.createTempFile("upload", null, context.cacheDir)
        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
        return Outgoing(uri, name, file.length(), file)
    }

    // ---- laptop -> phone --------------------------------------------------------------

    fun receive(context: Context, scope: CoroutineScope, store: Store, files: List<IncomingFile>) {
        val pairing = store.load() ?: return
        tracked(scope) { receiveBatch(context, store, pairing, files) }
    }

    /** The user (or the phone's own setting) said no: tell the laptop so it can forget the offer. */
    fun decline(context: Context, scope: CoroutineScope, store: Store, files: List<IncomingFile>) {
        val pairing = store.load() ?: return
        tracked(scope) {
            for (file in files) Sender.run(context, store, pairing) { target ->
                withContext(Dispatchers.IO) { EcozClient.decline(target, file.id) }
            }
        }
    }

    private suspend fun receiveBatch(context: Context, store: Store, pairing: Pairing, files: List<IncomingFile>) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val resolver = context.contentResolver
        val title = if (files.size == 1) cleanName(files[0].name) else "${files.size} files"
        val notice = ProgressNotice(context, notifications, nextId.incrementAndGet(), peer = pairing.name)
        val total = files.sumOf { it.size }
        var finished = 0L
        var lastSaved: Pair<Uri, String>? = null
        try {
            for (file in files) {
                val name = cleanName(file.name)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                    ?: "application/octet-stream"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$RECEIVE_FOLDER")
                    put(MediaStore.MediaColumns.IS_PENDING, 1) // hidden from other apps until complete
                }
                val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return notice.finish("Couldn't save $title", "No room to create the file in Downloads/laptop.")
                try {
                    val outcome = Sender.run(context, store, pairing) { source ->
                        withContext(Dispatchers.IO) {
                            val sink = resolver.openOutputStream(target, "wt")
                                ?: return@withContext Outcome(false, "Couldn't write to Downloads.")
                            sink.use {
                                EcozClient.download(source, file.id, file.size, it) { got ->
                                    notice.progress("Receiving $title", finished + got, total)
                                }
                            }
                        }
                    }.first
                    if (!outcome.ok) throw IOException(outcome.message)
                    resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    lastSaved = target to mime
                    finished += file.size
                } catch (e: Exception) {
                    runCatching { resolver.delete(target, null, null) } // never leave a partial file in Downloads
                    throw e
                }
            }
            val tap = if (files.size == 1) lastSaved?.let { openFile(context, it.first, it.second) } else openDownloads(context)
            notice.finish("Received $title", "Saved in Downloads/$RECEIVE_FOLDER. Tap to open.", tap)
        } catch (e: Exception) {
            notice.finish("Couldn't receive $title", e.message ?: "Unexpected error")
        }
    }

    /** A name from the laptop reduced to something safe to create. */
    private fun cleanName(raw: String): String =
        raw.substringAfterLast('/').substringAfterLast('\\').filter { !it.isISOControl() }.trim().ifEmpty { "file" }.take(200)

    private fun openFile(context: Context, uri: Uri, mime: String): PendingIntent = PendingIntent.getActivity(
        context, uri.hashCode(),
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openDownloads(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** One notification per batch: a bar while it runs, a result when it ends. */
    private class ProgressNotice(
        private val context: Context,
        private val notifications: NotificationManager,
        private val id: Int,
        private val peer: String,
    ) {
        private var lastUpdateMs = 0L

        fun waiting(headline: String) {
            notifications.notify(
                id,
                NotificationCompat.Builder(context, ListenerService.CHANNEL_TRANSFER)
                    .setSmallIcon(R.drawable.ic_stat_ecoz)
                    .setContentTitle(headline)
                    .setContentText(peer)
                    .setOngoing(true)
                    .setProgress(0, 0, true)
                    .build(),
            )
        }

        fun progress(headline: String, done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (now - lastUpdateMs < UPDATE_EVERY_MS && done < total) return
            lastUpdateMs = now
            notifications.notify(
                id,
                NotificationCompat.Builder(context, ListenerService.CHANNEL_TRANSFER)
                    .setSmallIcon(R.drawable.ic_stat_ecoz)
                    .setContentTitle(headline)
                    .setContentText(if (total > 0) "${done * 100 / total}%  ·  $peer" else peer)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setProgress(if (total > 0) 1000 else 0, if (total > 0) (done * 1000 / total).toInt() else 0, total <= 0)
                    .build(),
            )
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
