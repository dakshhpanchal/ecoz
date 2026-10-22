package app.ecoz

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sends the phone's clipboard to the laptop. Android only lets an app read the clipboard
 * while it has focus, so this invisible activity reads it the moment its window is focused
 * and then finishes. It is opened from a notification button, never started in the background.
 */
class ClipboardSendActivity : ComponentActivity() {
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true

        val store = Store(this)
        val pairing = store.load()
        val text = readClipboard(this)?.trim().orEmpty()
        when {
            pairing == null -> finishWith("Pair with your laptop first.")
            text.isEmpty() -> finishWith("Clipboard is empty.")
            else -> lifecycleScope.launch {
                // Always plain text: a copied link should land on the clipboard, not open a tab.
                val (outcome, _) = Sender.run(applicationContext, store, pairing) { target ->
                    withContext(Dispatchers.IO) { EcozClient.send(target, "text", text) }
                }
                finishWith(if (outcome.ok) "Clipboard sent to ${pairing.name}" else outcome.message)
            }
        }
    }

    private fun readClipboard(context: Context): String? {
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        return clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    private fun finishWith(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }
}
