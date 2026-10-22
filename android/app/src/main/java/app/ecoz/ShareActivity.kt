package app.ecoz

import android.content.Intent
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

        when {
            shared.isEmpty() -> finishWith("Nothing to send.")
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

    private fun finishWith(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        val URL_PATTERN = Regex("""https?://[^\s<>"]+""", RegexOption.IGNORE_CASE)
    }
}
