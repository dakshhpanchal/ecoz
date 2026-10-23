package app.ecoz

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Share-sheet entry point: "Send to laptop". Invisible; shows a toast with the result. */
class ShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val store = Store(this)
        val pairing = store.load()
        val files = sharedFiles()

        when {
            files.isNotEmpty() && pairing != null -> {
                // A big transfer must outlive this invisible screen, so the service does the work.
                ListenerService.upload(this, files)
                finishWith(if (files.size == 1) "Sending to ${pairing.name}…" else "Sending ${files.size} files to ${pairing.name}…")
            }
            shared.isEmpty() && files.isEmpty() -> finishWith("Nothing to send.")
            pairing == null -> {
                startActivity(Intent(this, MainActivity::class.java))
                finishWith("Pair with your laptop first.")
            }
            else -> lifecycleScope.launch {
                val url = URL_PATTERN.find(shared)?.value
                val (outcome, _) = Sender.run(applicationContext, store, pairing) { target ->
                    withContext(Dispatchers.IO) {
                        if (url != null) EcozClient.send(target, "url", url)
                        else EcozClient.send(target, "text", shared)
                    }
                }
                finishWith(outcome.message)
            }
        }
    }

    /** Files shared from another app (one, or several), as readable content URIs. */
    private fun sharedFiles(): List<Uri> {
        val data = intent ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (data.action == Intent.ACTION_SEND_MULTIPLE) data.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else listOfNotNull(data.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        } else @Suppress("DEPRECATION") {
            if (data.action == Intent.ACTION_SEND_MULTIPLE) data.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(data.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        }
    }

    private fun finishWith(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        val URL_PATTERN = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    }
}
