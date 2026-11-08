package app.orbit.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import app.orbit.tv.core.SettingsState
import app.orbit.tv.core.StorageMode

@Composable
fun SettingsScreen(
    settings: SettingsState,
    address: String?,
    storageLabel: String,
    folderPickerAvailable: Boolean,
    onReceiving: (Boolean) -> Unit,
    onApproval: (Boolean) -> Unit,
    onRename: (String) -> Unit,
    onStorage: (StorageMode) -> Unit,
    onNewKey: () -> Unit,
    shortcutUrl: String?,
    onBack: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var choosingStorage by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(Modifier.weight(0.34f)) {
            SecondaryButton("Back", onBack, icon = Icons.Filled.ArrowBack)
            Spacer(Modifier.height(28.dp))
            Text("Settings", style = MaterialTheme.typography.displaySmall, color = Orbit.Text)
            Text("Orbit receives files from iPhones on the same Wi-Fi. Nothing is uploaded to the cloud.",
                style = MaterialTheme.typography.bodyLarge, color = Orbit.Text2, modifier = Modifier.padding(top = 8.dp))
        }
        LazyColumn(Modifier.weight(0.66f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                SettingRow("Receiving", if (settings.receivingEnabled) "Visible to iPhones on this network" else "Not accepting files",
                    { onReceiving(!settings.receivingEnabled) }, Modifier.focusRequester(firstFocus)) { Toggle(settings.receivingEnabled) }
            }
            item {
                SettingRow("Ask before accepting", "Confirm each transfer with the remote (keep Orbit open)", { onApproval(!settings.requireApproval) }) {
                    Toggle(settings.requireApproval)
                }
            }
            item { SettingRow("Device name", settings.deviceName, { renaming = true }) }
            item { SettingRow("Save to", storageLabel, { choosingStorage = true }) }
            item {
                SettingRow("Pairing code", "${settings.pairingKey.take(4)} ${settings.pairingKey.drop(4)} · select to generate a new one (old QR codes stop working)", onNewKey)
            }
            item { SettingRow("Network address", address ?: "Not connected", {}) }
            if (shortcutUrl != null) item {
                SettingRow("iPhone Share Sheet shortcut URL", shortcutUrl, {})
            }
            item { SettingRow("About", "Orbit 0.1 · files keep their original names and quality", {}) }
        }
    }

    if (renaming) RenameDialog(settings.deviceName, onDone = { if (it.isNotBlank()) onRename(it); renaming = false })
    if (choosingStorage) StorageDialog(settings.storageMode, folderPickerAvailable, onPick = { onStorage(it); choosingStorage = false }, onDismiss = { choosingStorage = false })
}

@Composable
private fun SettingRow(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    ListItem(
        selected = false,
        onClick = onClick,
        modifier = modifier,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Orbit.Text3) },
        trailingContent = trailing,
        shape = ListItemDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Orbit.Surface, contentColor = Orbit.Text,
            focusedContainerColor = Orbit.Surface2, focusedContentColor = Orbit.Text,
        ),
        scale = ListItemDefaults.scale(focusedScale = 1.02f),
        border = ListItemDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Orbit.Text), shape = RoundedCornerShape(14.dp)),
        ),
    )
}

@Composable
private fun Toggle(checked: Boolean) = Switch(
    checked = checked, onCheckedChange = null,
    colors = SwitchDefaults.colors(checkedTrackColor = Orbit.Indigo, checkedThumbColor = androidx.compose.ui.graphics.Color.White),
)

@Composable
private fun RenameDialog(current: String, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = { onDone("") }) {
        Column(Modifier.background(Orbit.Surface, Orbit.RadiusLg).padding(32.dp).width(520.dp)) {
            Text("Device name", style = MaterialTheme.typography.titleLarge, color = Orbit.Text)
            Text("Shown on the iPhone when sending.", color = Orbit.Text2, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
            BasicTextField(
                value = text,
                onValueChange = { text = it.take(40) },
                singleLine = true,
                textStyle = TextStyle(color = Orbit.Text, fontSize = 22.sp),
                cursorBrush = SolidColor(Orbit.Cyan),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDone(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus)
                    .background(Orbit.Surface2, RoundedCornerShape(12.dp))
                    .border(2.dp, Orbit.Indigo, RoundedCornerShape(12.dp)).padding(16.dp),
            )
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Save", { onDone(text) }, icon = Icons.Filled.Check)
                SecondaryButton("Cancel", { onDone("") })
            }
        }
    }
}

@Composable
private fun StorageDialog(current: StorageMode, folderPickerAvailable: Boolean, onPick: (StorageMode) -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.background(Orbit.Surface, Orbit.RadiusLg).padding(28.dp).width(600.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Save received files to", style = MaterialTheme.typography.titleLarge, color = Orbit.Text, modifier = Modifier.padding(bottom = 8.dp))
            Option("Downloads › Orbit", "One folder for everything. Visible to file managers and media apps.", current == StorageMode.DOWNLOADS,
                { onPick(StorageMode.DOWNLOADS) }, Modifier.focusRequester(focus))
            Option("Sort by type", "Photos → Pictures/Orbit, videos → Movies/Orbit, music → Music/Orbit, rest → Downloads/Orbit. Media apps on the TV pick them up automatically.",
                current == StorageMode.BY_TYPE, { onPick(StorageMode.BY_TYPE) })
            Option(
                "Choose a folder…",
                if (folderPickerAvailable) "Pick any folder, e.g. on a USB drive." else "Not available: this TV doesn't include the Android folder picker.",
                current == StorageMode.CUSTOM, { if (folderPickerAvailable) onPick(StorageMode.CUSTOM) }, enabled = folderPickerAvailable,
            )
        }
    }
}

@Composable
private fun Option(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    ListItem(
        selected = selected, onClick = onClick, enabled = enabled, modifier = modifier,
        headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = if (selected) ({ Icon(Icons.Filled.Check, contentDescription = "Selected", tint = Orbit.Cyan) }) else null,
        shape = ListItemDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ListItemDefaults.colors(
            containerColor = Orbit.Surface2, contentColor = Orbit.Text,
            focusedContainerColor = Orbit.Text, focusedContentColor = Orbit.Bg,
            selectedContainerColor = Orbit.Indigo.copy(alpha = 0.25f), selectedContentColor = Orbit.Text,
            disabledContainerColor = Orbit.Surface2.copy(alpha = 0.5f), disabledContentColor = Orbit.Text3,
        ),
    )
}
