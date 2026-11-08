package app.orbit.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.orbit.tv.core.ReceivedItem
import app.orbit.tv.core.SessionStatus
import app.orbit.tv.core.SessionUi
import app.orbit.tv.core.SettingsState
import coil.compose.AsyncImage

@Composable
fun HomeScreen(
    settings: SettingsState,
    address: String?,
    serverError: String?,
    shareUrl: String?,
    sessions: List<SessionUi>,
    history: List<ReceivedItem>,
    onOpenReceived: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenItem: (ReceivedItem) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onClear: () -> Unit,
    onToggleReceiving: () -> Unit,
    openWifi: Boolean = false,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
    val broadcasting = settings.receivingEnabled && address != null && serverError == null && !openWifi
    val active = sessions.firstOrNull { it.status == SessionStatus.ACTIVE || it.status == SessionStatus.ACCEPTED }
    val (statusText, statusColor) = when {
        serverError != null -> "Not available" to Orbit.Danger
        !settings.receivingEnabled -> "Receiving is off" to Orbit.Warning
        address == null -> "Connect the TV to Wi-Fi" to Orbit.Danger
        openWifi -> "Paused: Wi-Fi has no password" to Orbit.Danger
        active != null -> "Receiving from ${active.sender}" to Orbit.Cyan
        else -> "Ready to receive" to Orbit.Success
    }

    // TV safe area: 48 dp horizontal, 27 dp vertical overscan margins.
    Row(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {

        // ------------------------------------------------ left: device
        Column(Modifier.weight(0.4f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Orbit.Gradient))
                Spacer(Modifier.width(10.dp))
                Text("Orbit", style = MaterialTheme.typography.titleLarge, color = Orbit.Text)
            }
            Spacer(Modifier.weight(1f))
            // Animation pauses during transfers: on low-end TV SoCs redrawing competes with receiving for CPU.
            OrbitRadar(active = broadcasting, animate = active == null, size = 220.dp) {
                Icon(TvGlyph, contentDescription = null, tint = if (broadcasting) Color.White else Orbit.Text3, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text("This TV appears as", style = MaterialTheme.typography.bodyMedium, color = Orbit.Text2)
            Text(settings.deviceName, style = MaterialTheme.typography.displaySmall, color = Orbit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            StatusPill(statusText, statusColor)
            if (serverError != null) Text(serverError, color = Orbit.Danger, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SecondaryButton("Received", onOpenReceived, Modifier.focusRequester(firstFocus), icon = Icons.Filled.List)
                SecondaryButton("Settings", onOpenSettings, icon = Icons.Filled.Settings)
                SecondaryButton(if (settings.receivingEnabled) "Turn off" else "Turn on", onToggleReceiving)
            }
        }

        // ------------------------------------------------ right: send card + activity
        Column(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // The QR card shrinks while a transfer is running so progress gets the room.
            val qrSize = if (active != null) 132.dp else 188.dp
            Row(
                Modifier.fillMaxWidth().clip(Orbit.RadiusLg).background(Orbit.Surface).border(1.dp, Orbit.Stroke, Orbit.RadiusLg).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (shareUrl != null && settings.receivingEnabled && !openWifi) QrCode(shareUrl, Modifier.size(qrSize))
                else Box(Modifier.size(qrSize).clip(RoundedCornerShape(16.dp)).background(Orbit.Surface2).padding(12.dp), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            !settings.receivingEnabled -> "Receiving is off"
                            openWifi -> "Open Wi-Fi: use a network with a password"
                            else -> "No network"
                        },
                        color = Orbit.Text3, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Send from your iPhone", style = MaterialTheme.typography.headlineSmall, color = Orbit.Text)
                    if (active == null) {
                        Step(1, "Point the iPhone Camera at the code")
                        Step(2, "Choose photos, videos or files")
                        Step(3, "Tap Send — they appear here")
                    }
                    if (address != null) Text(address, color = Orbit.Text3, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
                    Text("Same Wi-Fi needed · nothing goes to the cloud", style = MaterialTheme.typography.labelMedium, color = Orbit.Text3)
                }
            }

            if (active != null) TransferCard(active, onPause, onResume, onCancel)
            else {
                val recentDone = sessions.firstOrNull { it.status.finished && System.currentTimeMillis() - it.finishedAt < 15_000 }
                if (recentDone != null) ResultBanner(recentDone, onClear)
                RecentRow(history, onOpenItem, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(26.dp).clip(CircleShape).background(Orbit.Indigo.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
        Text("$n", color = Orbit.IndigoBright, style = MaterialTheme.typography.labelLarge)
    }
    Spacer(Modifier.width(12.dp))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = Orbit.Text2)
}

@Composable
private fun TransferCard(s: SessionUi, onPause: (String) -> Unit, onResume: (String) -> Unit, onCancel: (String) -> Unit) {
    val speed = rememberSpeed(s.id, s.receivedBytes)
    Column(
        Modifier.fillMaxWidth().clip(Orbit.RadiusLg).background(Orbit.Surface).border(1.dp, Orbit.Stroke, Orbit.RadiusLg).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text(if (s.paused) "Paused" else "Receiving from ${s.sender}", style = MaterialTheme.typography.labelLarge, color = Orbit.Text2)
                Text(
                    if (s.fileCount == 1) (s.currentName ?: s.names.firstOrNull() ?: "") else "${s.doneCount + 1} of ${s.fileCount} · ${s.currentName ?: ""}",
                    style = MaterialTheme.typography.titleLarge, color = Orbit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            val pct = if (s.totalBytes > 0) (s.receivedBytes * 100 / s.totalBytes) else 0
            Text("$pct%", fontSize = 40.sp, color = Orbit.Text, style = MaterialTheme.typography.displaySmall)
        }
        GradientProgress(if (s.totalBytes > 0) s.receivedBytes.toFloat() / s.totalBytes else 0f, height = 10.dp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val eta = if (speed > 0) formatDuration((s.totalBytes - s.receivedBytes) / speed) + " left" else "—"
            Text(
                "${formatBytes(s.receivedBytes.toDouble())} of ${formatBytes(s.totalBytes.toDouble())}" +
                    (if (speed > 0 && !s.paused) " · ${formatBytes(speed)}/s · $eta" else ""),
                style = MaterialTheme.typography.bodyLarge, color = Orbit.Text2, modifier = Modifier.weight(1f),
            )
            if (s.paused) SecondaryButton("Resume", { onResume(s.id) }, icon = Icons.Filled.PlayArrow)
            else SecondaryButton("Pause", { onPause(s.id) })
            Spacer(Modifier.width(12.dp))
            SecondaryButton("Cancel", { onCancel(s.id) }, icon = Icons.Filled.Close)
        }
    }
}

@Composable
private fun ResultBanner(s: SessionUi, onClear: () -> Unit) {
    val (text, color) = when (s.status) {
        SessionStatus.COMPLETED -> "Received ${if (s.fileCount == 1) s.names.firstOrNull() ?: "1 item" else "${s.fileCount} items"} from ${s.sender}" to Orbit.Success
        SessionStatus.FAILED -> "Transfer failed: ${s.error ?: "unknown error"}" to Orbit.Danger
        SessionStatus.DECLINED -> "Declined transfer from ${s.sender}" to Orbit.Text2
        SessionStatus.EXPIRED -> "Request from ${s.sender} timed out" to Orbit.Warning
        else -> "Transfer canceled" to Orbit.Text2
    }
    Row(
        Modifier.fillMaxWidth().clip(Orbit.RadiusMd).background(color.copy(alpha = 0.12f)).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Text(text, color = Orbit.Text, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecentRow(history: List<ReceivedItem>, onOpen: (ReceivedItem) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text("Recently received", style = MaterialTheme.typography.titleMedium, color = Orbit.Text2)
        Spacer(Modifier.height(12.dp))
        if (history.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().fillMaxHeight().clip(Orbit.RadiusLg).border(1.dp, Orbit.Stroke, Orbit.RadiusLg).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text("Nothing here yet", style = MaterialTheme.typography.titleMedium, color = Orbit.Text)
                Text("Photos and videos you send will show up here, ready to view on the big screen.",
                    style = MaterialTheme.typography.bodyMedium, color = Orbit.Text3)
            }
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(history.take(20), key = { it.uri.toString() }) { item -> MediaCard(item, { onOpen(item) }, Modifier.width(196.dp)) }
            }
        }
    }
}

@Composable
fun MediaCard(item: ReceivedItem, onClick: () -> Unit, modifier: Modifier = Modifier, onLongClick: (() -> Unit)? = null) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = CardDefaults.shape(RoundedCornerShape(14.dp)),
        colors = CardDefaults.colors(containerColor = Orbit.Surface, focusedContainerColor = Orbit.Surface2),
        scale = CardDefaults.scale(focusedScale = 1.06f),
        border = CardDefaults.border(
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Orbit.Text), shape = RoundedCornerShape(14.dp)),
        ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Orbit.Surface2), contentAlignment = Alignment.Center) {
            if (item.mime.startsWith("image/") || item.mime.startsWith("video/")) {
                AsyncImage(model = item.uri, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (!item.mime.startsWith("image/")) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(if (item.mime.startsWith("video/")) "▶" else item.name.substringAfterLast('.', "FILE").uppercase().take(5),
                        color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(item.name, style = MaterialTheme.typography.labelLarge, color = Orbit.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${formatBytes(item.size.toDouble())} · ${item.sender}", style = MaterialTheme.typography.labelMedium, color = Orbit.Text3, maxLines = 1)
        }
    }
}

@Composable
private fun rememberSpeed(id: String, received: Long): Double {
    val samples = remember(id) { ArrayDeque<Pair<Long, Long>>() }
    val now = System.currentTimeMillis()
    samples.addLast(now to received)
    while (samples.size > 2 && now - samples.first().first > 3000) samples.removeFirst()
    val (t0, b0) = samples.first()
    return if (now - t0 > 300) (received - b0) * 1000.0 / (now - t0) else 0.0
}
