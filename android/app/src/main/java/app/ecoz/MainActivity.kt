package app.ecoz

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.core.app.NotificationManagerCompat
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private val resumeTick = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumeTick.intValue++ // grants happen in system screens; re-check when we come back
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(this)
        if (store.listenEnabled && store.load() != null) ListenerService.start(this)
        setContent { EcozTheme { EcozScreen(store, resumeTick.intValue) } }
    }
}

@Composable
private fun EcozTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EcozScreen(store: Store, resumeTick: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pairing by remember { mutableStateOf(store.load()) }
    var code by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Android blocks LAN access for apps targeting recent SDKs until this is granted.
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) { localNetwork.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) }

    var receiving by remember { mutableStateOf(store.listenEnabled) }
    val listenerStatus by ListenerState.status.collectAsState()
    fun setReceiving(on: Boolean) {
        receiving = on
        store.listenEnabled = on
        if (on) ListenerService.start(context) else ListenerService.stop(context)
    }
    var clipboardSync by remember { mutableStateOf(store.clipboardSync) }
    var mirror by remember { mutableStateOf(store.mirrorNotifications) }
    var confirmFiles by remember { mutableStateOf(store.confirmIncomingFiles) }
    var proximity by remember { mutableStateOf(store.proximityEnabled) }
    var unlock by remember { mutableStateOf(store.unlockEnabled) }
    var texts by remember { mutableStateOf(store.smsEnabled) }
    var hotspotHelp by remember { mutableStateOf(store.hotspotHelp) }
    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted[Manifest.permission.READ_SMS] == true
        texts = ok
        store.smsEnabled = ok
    }
    val hotspotPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        hotspotHelp = ok
        store.hotspotHelp = ok
    }
    var unlockNote by remember { mutableStateOf<String?>(null) }
    var unlockBusy by remember { mutableStateOf(false) }
    val nearbyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val ok = granted[Manifest.permission.BLUETOOTH_ADVERTISE] == true
        proximity = ok
        store.proximityEnabled = ok
    }
    val notificationAccess = remember(resumeTick) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    // Notifications are the only way the phone can show what the laptop sends.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        setReceiving(true)
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = { LargeTopAppBar(title = { Text("ecoz") }, scrollBehavior = scroll) },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val paired = pairing
            if (paired == null) {
                PairCard(
                    code = code,
                    onCodeChange = { code = it },
                    onPaste = { code = readClipboard(context).orEmpty() },
                    onPair = {
                        val parsed = Pairing.parse(code)
                        if (parsed == null) {
                            message = "That isn't a ecoz pairing code."
                        } else {
                            store.save(parsed)
                            pairing = parsed
                            code = ""
                            message = "Paired with ${parsed.name}."
                        }
                    },
                )
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Paired", style = MaterialTheme.typography.labelLarge)
                        Text(paired.name, style = MaterialTheme.typography.headlineSmall)
                        Text("${paired.host}:${paired.port}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("How to use", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "In any app, tap Share and choose “Send to laptop”. A link opens on your laptop; " +
                                "a photo, video or file goes to the laptop's Downloads/phone folder, and files you send from the laptop arrive in Downloads/laptop here. To send from the laptop, " +
                                "use  ecoz send-file  or right-click a file and choose Send to Phone.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Receive from laptop", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (receiving) listenerStatus else "Off",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Switch(checked = receiving, onCheckedChange = { on ->
                                if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else setReceiving(on)
                            })
                        }
                        if (receiving) {
                            Text(
                                "To keep this connected, set Battery to “Unrestricted” for ecoz.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                )
                            }) { Text("Open app settings") }
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Sync clipboard from laptop", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (clipboardSync) "Text you copy on the laptop lands here" else "Off",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Switch(checked = clipboardSync, onCheckedChange = {
                                clipboardSync = it
                                store.clipboardSync = it
                            })
                        }
                        Text(
                            "To send what you copied here to the laptop, tap “Send clipboard” in the ecoz " +
                                "notification. Android doesn't let apps watch the clipboard in the background.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Tell the laptop when I'm close", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (proximity) "Broadcasting a private Bluetooth beacon" else "Off",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Switch(checked = proximity, onCheckedChange = { on ->
                                if (on) nearbyPermission.launch(
                                    arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN)
                                ) else {
                                    proximity = false
                                    store.proximityEnabled = false
                                }
                            })
                        }
                        Text(
                            "Used for locking the laptop when you walk away. The beacon is recognisable only by " +
                                "your laptop, changes every 30 seconds, and carries no name or location. " +
                                "It's also switched on separately on the laptop: run  ecoz proximity on .",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Texts on the laptop", style = MaterialTheme.typography.titleMedium)
                                Text(if (texts) "On: the laptop can read and send texts" else "Off", style = MaterialTheme.typography.bodyMedium)
                            }
                            Switch(checked = texts, onCheckedChange = { on ->
                                if (on) smsPermission.launch(
                                    arrayOf(Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.READ_CONTACTS)
                                ) else {
                                    texts = false
                                    store.smsEnabled = false
                                }
                            })
                        }
                        Text(
                            "Open them on the laptop with  ecoz sms . Only plain SMS works: chats using RCS " +
                                "(“Chat features”) are not readable by any app. Codes sent by banks and sites " +
                                "will be visible on the laptop too, so turn this off on a shared computer.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Hotspot for the laptop", style = MaterialTheme.typography.titleMedium)
                                Text(if (hotspotHelp) "On: I'll offer the hotspot switch when it's needed" else "Off", style = MaterialTheme.typography.bodyMedium)
                            }
                            Switch(checked = hotspotHelp, onCheckedChange = { on ->
                                if (on) hotspotPermission.launch(Manifest.permission.BLUETOOTH_SCAN) else {
                                    hotspotHelp = false
                                    store.hotspotHelp = false
                                }
                            })
                        }
                        Text(
                            "When the laptop is close and has no internet, a notification opens the hotspot " +
                                "switch in one tap (Android doesn't let apps turn it on themselves). Also switch " +
                                "it on at the laptop in  ecoz settings .",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Let my phone unlock the laptop", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        unlockBusy -> "Waiting for you to approve on the laptop…"
                                        unlock -> "On: unlocks when this phone is near and unlocked"
                                        else -> "Off"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Switch(checked = unlock, enabled = !unlockBusy, onCheckedChange = { on ->
                                if (!on) {
                                    unlock = false
                                    store.unlockEnabled = false
                                    UnlockKey.delete()
                                    unlockNote = "Turned off. Run  ecoz unlock forget  on the laptop to remove this phone there too."
                                } else if (!proximity) {
                                    unlockNote = "Turn on “Tell the laptop when I'm close” first."
                                } else {
                                    unlockBusy = true
                                    unlockNote = "A question is on the laptop screen. Accept it there."
                                    scope.launch {
                                        val (outcome, accepted) = withContext(Dispatchers.IO) {
                                            EcozClient.registerUnlockKey(paired, UnlockKey.publicKey())
                                        }
                                        unlockBusy = false
                                        unlock = accepted
                                        store.unlockEnabled = accepted
                                        if (!accepted) withContext(Dispatchers.IO) { UnlockKey.delete() }
                                        unlockNote = when {
                                            accepted -> "Done. Lock the laptop, wake it and keep this phone close and unlocked."
                                            outcome.ok -> "The laptop didn't approve it, so nothing was turned on."
                                            else -> outcome.message
                                        }
                                    }
                                }
                            })
                        }
                        Text(
                            "The laptop unlocks only when this phone is close, the phone itself is unlocked, and " +
                                "it proves itself with a key kept in its secure hardware, which Android won't use " +
                                "while the phone is locked. You must also be at the laptop.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        unlockNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Ask before receiving files", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (confirmFiles) "A notification asks you to accept or decline"
                                    else "Files from the laptop are accepted automatically",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Switch(checked = confirmFiles, onCheckedChange = {
                                confirmFiles = it
                                store.confirmIncomingFiles = it
                            })
                        }
                        Text(
                            "This is only for files sent to the phone. Whether the laptop asks before " +
                                "accepting files from the phone is a separate setting: run  ecoz settings  on the laptop.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Mirror notifications", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    when {
                                        !mirror -> "Off"
                                        !notificationAccess -> "Needs notification access"
                                        else -> "Phone notifications appear on the laptop"
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Switch(checked = mirror, onCheckedChange = {
                                mirror = it
                                store.mirrorNotifications = it
                            })
                        }
                        if (mirror && !notificationAccess) {
                            Text(
                                "Android needs you to allow ecoz to read notifications. Ongoing and " +
                                    "silent ones, and anything marked private, are never sent.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }) { Text("Allow notification access") }
                        }
                    }
                }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Find my phone", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "On the laptop run  ecoz ring  and this phone sounds an alarm at full volume, " +
                                "even on silent, until you tap Stop (it stops by itself after a minute).",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val (outcome, current) = Sender.run(context, store, paired) { target ->
                                    withContext(Dispatchers.IO) { EcozClient.ping(target) }
                                }
                                pairing = current
                                message = if (outcome.ok) "Connected to ${current.name} at ${current.host}." else outcome.message
                                busy = false
                            }
                        },
                    ) { Text("Test connection") }
                    OutlinedButton(onClick = {
                        ListenerService.stop(context)
                        receiving = false
                        store.clear()
                        pairing = null
                        message = null
                    }) { Text("Unpair") }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun PairCard(
    code: String,
    onCodeChange: (String) -> Unit,
    onPaste: () -> Unit,
    onPair: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Pair with your laptop", style = MaterialTheme.typography.titleMedium)
            Text(
                "On the laptop run  ecoz_daemon.py pair  and copy the code. " +
                    "KDE Connect clipboard sync will bring it here. Then tap Paste.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                label = { Text("Pairing code") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 3,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPaste) { Text("Paste") }
                Button(onClick = onPair, enabled = code.isNotBlank()) { Text("Pair") }
            }
        }
    }
}

/** Only readable while the app is in the foreground (Android clipboard rules). */
private fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java)
    return manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
}
