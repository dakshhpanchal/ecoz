package app.handoff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the laptop connection after a reboot or an app update, if the user turned it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = Store(context)
        if (store.listenEnabled && store.load() != null) ListenerService.start(context)
    }
}
