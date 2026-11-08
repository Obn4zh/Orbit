package app.orbit.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.orbit.tv.core.Protocol
import app.orbit.tv.core.ReceivedItem
import app.orbit.tv.core.SessionUi
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

@Composable
fun ApprovalDialog(s: SessionUi, onAccept: () -> Unit, onDecline: () -> Unit) {
    val accept = remember(s.id) { FocusRequester() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.id) { runCatching { accept.requestFocus() }; while (true) { delay(1000); now = System.currentTimeMillis() } }
    val left = ((Protocol.APPROVAL_TIMEOUT_MS - (now - s.createdAt)) / 1000).coerceAtLeast(0)

    Dialog(onDismissRequest = onDecline, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(560.dp).background(Orbit.Surface, Orbit.RadiusLg).padding(36.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Orbit.Gradient), contentAlignment = Alignment.Center) {
                Text("↓", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(20.dp))
            if (s.adHoc && s.fileCount == 0) {
                Text("${s.sender} wants to send files", style = MaterialTheme.typography.headlineSmall, color = Orbit.Text)
                Text("From the iPhone Share Sheet. Accepting receives the items of this share; a later share asks again.",
                    style = MaterialTheme.typography.bodyLarge, color = Orbit.Text2)
            } else {
                Text("${s.sender} wants to send ${if (s.fileCount == 1) "1 item" else "${s.fileCount} items"}",
                    style = MaterialTheme.typography.headlineSmall, color = Orbit.Text)
                Text(formatBytes(s.totalBytes.toDouble()), style = MaterialTheme.typography.bodyLarge, color = Orbit.Text2)
            }
            Spacer(Modifier.height(12.dp))
            s.names.take(4).forEach { Text("•  $it", color = Orbit.Text2, style = MaterialTheme.typography.bodyLarge, maxLines = 1) }
            if (s.fileCount > 4) Text("   and ${s.fileCount - 4} more", color = Orbit.Text3)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton("Accept", onAccept, Modifier.focusRequester(accept), icon = Icons.Filled.Check)
                SecondaryButton("Decline", onDecline, icon = Icons.Filled.Close)
                Spacer(Modifier.weight(1f))
                Text("${left}s", color = Orbit.Text3, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
fun OnboardingDialog(onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.width(640.dp).background(Orbit.Surface, Orbit.RadiusLg).padding(40.dp)) {
            Box(Modifier.size(56.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp)).background(Orbit.Gradient))
            Spacer(Modifier.height(20.dp))
            Text("Welcome to Orbit", style = MaterialTheme.typography.displaySmall, color = Orbit.Text)
            Text("Put photos and videos from your iPhone on the big screen — over Wi-Fi, no cables, no cloud.",
                style = MaterialTheme.typography.bodyLarge, color = Orbit.Text2, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(24.dp))
            Bullet("Same Wi-Fi", "Keep the iPhone and this TV on the same network.")
            Bullet("Scan the code", "Point the iPhone Camera at the QR code on the home screen.")
            Bullet("You decide", "Only phones that scanned the code can send, and you accept each transfer with the remote.")
            Spacer(Modifier.height(28.dp))
            PrimaryButton("Get started", onDone, Modifier.focusRequester(focus))
        }
    }
}

@Composable
private fun Bullet(title: String, text: String) = Row(Modifier.padding(bottom = 14.dp)) {
    Box(Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(Orbit.Cyan))
    Spacer(Modifier.width(16.dp))
    Column {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Orbit.Text)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Orbit.Text2)
    }
}

/** Fullscreen photo viewer: Left/Right to browse, Back to exit. */
@Composable
fun ImageViewer(images: List<ReceivedItem>, startIndex: Int, onBack: () -> Unit) {
    var index by remember { mutableIntStateOf(startIndex.coerceIn(0, (images.size - 1).coerceAtLeast(0))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    if (images.isEmpty()) { onBack(); return }
    val item = images[index]
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { if (index < images.size - 1) index++; true }
                    Key.DirectionLeft -> { if (index > 0) index--; true }
                    else -> false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(model = item.uri, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).clip(Orbit.RadiusMd).background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.ArrowBack, null, tint = if (index > 0) Color.White else Orbit.Text3, modifier = Modifier.size(20.dp))
            Text("${item.name}  ·  ${index + 1} / ${images.size}", color = Color.White, style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Filled.ArrowForward, null, tint = if (index < images.size - 1) Color.White else Orbit.Text3, modifier = Modifier.size(20.dp))
        }
    }
}
