package app.ecoz

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Applies clipboard text that arrived from the laptop. */
object ClipboardSync {
    fun apply(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("ecoz", text))
    }
}
