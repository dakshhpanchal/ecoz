package app.ecoz

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** The Quick Settings tile: one tap turns ecoz's connection to the laptop on or off. */
class EcozTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null

    override fun onStartListening() {
        refresh()
        // keep the subtitle live (Connected, Connecting, ...) while the panel is open
        watcher = scope.launch { ListenerState.status.collect { refresh() } }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        val store = Store(applicationContext)
        when {
            store.listenEnabled -> {
                store.listenEnabled = false
                ListenerService.stop(this)
            }
            store.load() == null -> openApp() // nothing to connect to until the phone is paired
            else -> {
                store.listenEnabled = true
                ListenerService.start(this)
            }
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val store = Store(applicationContext)
        val on = store.listenEnabled && store.load() != null
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "ecoz"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_ecoz)
        tile.subtitle = when {
            store.load() == null -> "Not paired"
            !store.listenEnabled -> "Off"
            else -> ListenerState.status.value.takeUnless { it == "Off" } ?: "Starting…"
        }
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(intent)
        }
    }
}
