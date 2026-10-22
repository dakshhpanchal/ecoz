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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(this)
        if (store.listenEnabled && store.load() != null) ListenerService.start(this)
        setContent { EcozTheme { EcozScreen(store) } }
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
private fun EcozScreen(store: Store) {
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
                            "In Chrome, YouTube, Maps or any app, tap Share and choose “Send to laptop”. " +
                                "The link opens on your laptop.",
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
