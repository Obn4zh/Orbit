package app.orbit.tv.core

import android.content.Context
import android.os.Build
import android.provider.Settings as AndroidSettings
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class StorageMode { DOWNLOADS, BY_TYPE, CUSTOM }

data class SettingsState(
    val deviceId: String,
    val deviceName: String,
    val receivingEnabled: Boolean,
    val requireApproval: Boolean,
    val pairingKey: String,
    val storageMode: StorageMode,
    val treeUri: String?,
    val treeLabel: String?,
    val onboardingDone: Boolean,
)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("orbit", Context.MODE_PRIVATE)
    private val _state: MutableStateFlow<SettingsState>
    val state: StateFlow<SettingsState>

    init {
        if (!prefs.contains("deviceId")) {
            val systemName = runCatching { AndroidSettings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
            prefs.edit()
                .putString("deviceId", UUID.randomUUID().toString().replace("-", ""))
                .putString("deviceName", (systemName ?: Build.MODEL ?: "Android TV").take(40))
                .putString("pairingKey", newPairingKey())
                .apply()
        }
        _state = MutableStateFlow(read())
        state = _state.asStateFlow()
    }

    val current: SettingsState get() = _state.value

    private fun read() = SettingsState(
        deviceId = prefs.getString("deviceId", "")!!,
        deviceName = prefs.getString("deviceName", "Android TV")!!,
        receivingEnabled = prefs.getBoolean("receiving", true),
        requireApproval = prefs.getBoolean("requireApproval", true),
        pairingKey = prefs.getString("pairingKey", null) ?: newPairingKey().also { prefs.edit().putString("pairingKey", it).apply() },
        storageMode = runCatching { StorageMode.valueOf(prefs.getString("storageMode", "DOWNLOADS")!!) }.getOrDefault(StorageMode.DOWNLOADS),
        treeUri = prefs.getString("treeUri", null),
        treeLabel = prefs.getString("treeLabel", null),
        onboardingDone = prefs.getBoolean("onboardingDone", false),
    )

    private fun edit(block: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).apply()
        _state.value = read()
    }

    fun setDeviceName(name: String) = edit { putString("deviceName", name.trim().take(40)) }
    fun setReceiving(on: Boolean) = edit { putBoolean("receiving", on) }
    fun setRequireApproval(on: Boolean) = edit { putBoolean("requireApproval", on) }
    fun regeneratePairingKey() = edit { putString("pairingKey", newPairingKey()) }
    fun setOnboardingDone() = edit { putBoolean("onboardingDone", true) }
    fun setStorage(mode: StorageMode, treeUri: String? = null, treeLabel: String? = null) = edit {
        putString("storageMode", mode.name)
        putString("treeUri", treeUri)
        putString("treeLabel", treeLabel)
    }

    companion object {
        private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        private val random = SecureRandom()
        fun newPairingKey(): String = (1..8).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }
}
