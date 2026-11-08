package app.orbit.tv.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import app.orbit.tv.OrbitApp
import app.orbit.tv.core.ReceivedItem
import app.orbit.tv.core.Receiver
import app.orbit.tv.core.SessionStatus
import app.orbit.tv.core.SessionUi
import app.orbit.tv.core.StorageMode
import app.orbit.tv.service.ReceiverService
import kotlinx.coroutines.delay

enum class Screen { HOME, RECEIVED, SETTINGS, VIEWER }

class MainActivity : ComponentActivity() {
    private val receiver: Receiver get() = (application as OrbitApp).receiver

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val label = DocumentFile.fromTreeUri(this, uri)?.name ?: "Custom folder"
            receiver.settings.setStorage(StorageMode.CUSTOM, uri.toString(), label)
        }.onFailure { Toast.makeText(this, "This folder can't be used", Toast.LENGTH_LONG).show() }
    }

    private val requestLegacyStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread { receiver.refreshNetwork() }
        override fun onLost(network: Network) = runOnUiThread { receiver.refreshNetwork() }
        override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) = runOnUiThread { receiver.refreshNetwork() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestLegacyStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        setContent { OrbitTheme { App(receiver) } }
    }

    override fun onStart() {
        super.onStart()
        receiver.start()
        if (receiver.settings.current.receivingEnabled) ReceiverService.start(this)
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback) }
        receiver.history.retainExisting { receiver.storage.exists(it) }
    }

    override fun onStop() {
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        super.onStop()
    }

    fun chooseCustomFolder() = try { pickFolder.launch(null) } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, "This TV has no folder picker", Toast.LENGTH_LONG).show()
    }

    fun setReceiving(on: Boolean) {
        receiver.settings.setReceiving(on)
        if (on) ReceiverService.start(this) else ReceiverService.stop(this)
    }

    /** Opens a received file in another app; returns false when nothing on the TV can open it. */
    fun openExternally(item: ReceivedItem): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, item.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        true
    } catch (e: Exception) { false }
}

@Composable
private fun App(receiver: Receiver) {
    val activity = androidx.compose.ui.platform.LocalContext.current as MainActivity
    val settings by receiver.settings.state.collectAsState()
    val address by receiver.address.collectAsState()
    val serverError by receiver.serverError.collectAsState()
    val history by receiver.history.items.collectAsState()
    val openWifi by receiver.openWifi.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var viewerIndex by remember { mutableStateOf(0) }

    // Progress is polled: transfers update thousands of times per second, the screen needs ~2 fps.
    val sessions by produceState(emptyList<SessionUi>()) {
        while (true) { value = receiver.manager.snapshot(); delay(500) }
    }
    val pending = sessions.lastOrNull { it.status == SessionStatus.PENDING }

    BackHandler(enabled = screen != Screen.HOME) { screen = if (screen == Screen.VIEWER) Screen.RECEIVED else Screen.HOME }

    Box(Modifier.fillMaxSize().background(Orbit.Bg)) {
        when (screen) {
            Screen.HOME -> HomeScreen(
                settings = settings, address = address, serverError = serverError,
                shareUrl = receiver.shareUrl(), sessions = sessions, history = history,
                onOpenReceived = { screen = Screen.RECEIVED },
                onOpenSettings = { screen = Screen.SETTINGS },
                onOpenItem = { item -> openItem(activity, history, item) { viewerIndex = it; screen = Screen.VIEWER } },
                onPause = receiver.manager::pause, onResume = receiver.manager::resume, onCancel = receiver.manager::cancel,
                onClear = receiver.manager::clearFinished,
                onToggleReceiving = { activity.setReceiving(!settings.receivingEnabled) },
                openWifi = openWifi,
            )
            Screen.RECEIVED -> ReceivedScreen(
                items = history,
                onOpen = { item -> openItem(activity, history, item) { viewerIndex = it; screen = Screen.VIEWER } },
                onDelete = { item -> receiver.storage.delete(item.uri); receiver.history.remove(item.uri) },
                onBack = { screen = Screen.HOME },
            )
            Screen.SETTINGS -> SettingsScreen(
                settings = settings, address = address, storageLabel = receiver.storage.describe(settings),
                folderPickerAvailable = receiver.storage.isFolderPickerAvailable(),
                onReceiving = activity::setReceiving,
                onApproval = receiver.settings::setRequireApproval,
                onRename = receiver.settings::setDeviceName,
                onStorage = { mode -> if (mode == StorageMode.CUSTOM) activity.chooseCustomFolder() else receiver.settings.setStorage(mode) },
                onNewKey = receiver.settings::regeneratePairingKey,
                shortcutUrl = receiver.shortcutUrl(),
                onBack = { screen = Screen.HOME },
            )
            Screen.VIEWER -> ImageViewer(
                images = history.filter { it.mime.startsWith("image/") }, startIndex = viewerIndex, onBack = { screen = Screen.RECEIVED },
            )
        }

        if (!settings.onboardingDone) OnboardingDialog(onDone = receiver.settings::setOnboardingDone)
        else if (pending != null) ApprovalDialog(pending, onAccept = { receiver.manager.accept(pending.id) }, onDecline = { receiver.manager.decline(pending.id) })
    }
}

private fun openItem(activity: MainActivity, history: List<ReceivedItem>, item: ReceivedItem, showImage: (Int) -> Unit) {
    if (item.mime.startsWith("image/")) {
        showImage(history.filter { it.mime.startsWith("image/") }.indexOf(item).coerceAtLeast(0))
    } else if (!activity.openExternally(item)) {
        Toast.makeText(activity, "No app on this TV can open ${item.name.substringAfterLast('.', "this file").uppercase()} files", Toast.LENGTH_LONG).show()
    }
}
