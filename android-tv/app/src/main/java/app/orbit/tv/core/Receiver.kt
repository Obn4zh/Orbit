package app.orbit.tv.core

import android.content.Context
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Process-wide receiver: HTTP server, mDNS advertisement, transfer manager and received-files history. */
class Receiver(private val context: Context) {
    val settings = Settings(context)
    val storage = Storage(context, settings)
    val manager = TransferManager(settings, storage)
    private val server = ReceiverServer(manager, settings, context.assets) { !_openWifi.value }

    private val _openWifi = MutableStateFlow(false)
    /** True when connected to a Wi-Fi without a password: anyone nearby can read the traffic, so receiving pauses. */
    val openWifi: StateFlow<Boolean> = _openWifi.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var nsdListener: NsdManager.RegistrationListener? = null

    private val _address = MutableStateFlow<String?>(null)
    /** "192.168.1.20:47717" or null when not on a LAN. */
    val address: StateFlow<String?> = _address.asStateFlow()

    private val _serverError = MutableStateFlow<String?>(null)
    val serverError: StateFlow<String?> = _serverError.asStateFlow()

    val history = History(File(context.filesDir, "history.json"))

    init {
        scope.launch { manager.completedFiles.collect { history.add(it) } }
        scope.launch { settings.state.map { it.deviceName }.distinctUntilChanged().collect { if (server.isRunning) advertise() } }
    }

    @Synchronized
    fun start() {
        if (!server.isRunning) {
            try {
                server.start(Protocol.DEFAULT_PORT)
                _serverError.value = null
            } catch (e: Exception) {
                _serverError.value = "Could not start the receiver: ${e.message}"
            }
        }
        refreshNetwork()
    }

    fun refreshNetwork() {
        _openWifi.value = NetUtil.isOpenWifi(context)
        val ip = NetUtil.lanAddress(context)
        _address.value = if (ip != null && server.isRunning) "$ip:${server.port}" else null
        if (server.isRunning) advertise()
    }

    fun shareUrl(): String? = address.value?.let { "http://$it/?k=${settings.current.pairingKey}" }
    fun shortcutUrl(): String? = address.value?.let { "http://$it/api/v1/upload?k=${settings.current.pairingKey}" }

    private fun advertise() {
        unadvertise()
        val info = NsdServiceInfo().apply {
            serviceName = settings.current.deviceName
            serviceType = Protocol.SERVICE_TYPE
            port = server.port
            setAttribute("id", settings.current.deviceId)
            setAttribute("v", Protocol.VERSION.toString())
            setAttribute("platform", "android-tv")
            setAttribute("path", "/")
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); nsdListener = listener }
    }

    private fun unadvertise() {
        nsdListener?.let { runCatching { nsd?.unregisterService(it) } }
        nsdListener = null
    }
}

/** Persisted list of received files (newest first). Items deleted elsewhere are pruned on load. */
class History(private val file: File) {
    @Serializable
    private data class Entry(val uri: String, val name: String, val size: Long, val mime: String, val sender: String, val time: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<ReceivedItem>> = _items.asStateFlow()

    private fun load(): List<ReceivedItem> = runCatching {
        json.decodeFromString<List<Entry>>(file.readText()).map { ReceivedItem(Uri.parse(it.uri), it.name, it.size, it.mime, it.sender, it.time) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(item: ReceivedItem) { _items.value = (listOf(item) + _items.value).take(500); save() }

    @Synchronized
    fun remove(uri: Uri) { _items.value = _items.value.filterNot { it.uri == uri }; save() }

    @Synchronized
    fun retainExisting(exists: (Uri) -> Boolean) {
        val kept = _items.value.filter { exists(it.uri) }
        if (kept.size != _items.value.size) { _items.value = kept; save() }
    }

    private fun save() {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(_items.value.map { Entry(it.uri.toString(), it.name, it.size, it.mime, it.sender, it.time) }))
            tmp.renameTo(file)
        }
    }
}
