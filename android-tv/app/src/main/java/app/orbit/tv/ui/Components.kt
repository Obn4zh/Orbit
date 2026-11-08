package app.orbit.tv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.OutlinedButtonDefaults
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.max

/** Three rings expanding from the device — the "discoverable" signal, shared with Windows and the web page. */
@Composable
fun OrbitRadar(active: Boolean, size: Dp, modifier: Modifier = Modifier, animate: Boolean = true, glyph: @Composable () -> Unit) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (active && animate) RadarRings() else Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            drawCircle(Color.White.copy(alpha = 0.06f), radius = r * 0.66f, style = Stroke(1.dp.toPx()))
            if (active) for (i in 1..2) drawCircle(Orbit.IndigoBright.copy(alpha = 0.25f / i), radius = r * (0.55f + 0.2f * i), style = Stroke(2.dp.toPx()))
        }
        Box(
            Modifier.size(size * 0.4f).clip(CircleShape)
                .background(if (active) Orbit.Gradient else androidx.compose.ui.graphics.SolidColor(Orbit.Surface2)),
            contentAlignment = Alignment.Center,
        ) { glyph() }
    }
}

/** The animated part lives in its own composable so only this canvas redraws each frame. */
@Composable
private fun RadarRings() {
    val transition = rememberInfiniteTransition(label = "radar")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing), RepeatMode.Restart), label = "t")
    run {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            drawCircle(Color.White.copy(alpha = 0.06f), radius = r * 0.66f, style = Stroke(1.dp.toPx()))
            for (i in 0 until 3) {
                val p = (t + i / 3f) % 1f
                val eased = 1f - (1f - p) * (1f - p) * (1f - p)
                val alpha = if (p < 0.15f) p / 0.15f * 0.6f else 0.6f * (1f - (p - 0.15f) / 0.85f)
                drawCircle(Orbit.IndigoBright.copy(alpha = alpha), radius = r * (0.42f + 0.58f * eased), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/** QR code drawn as vector modules on a white rounded tile. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        ))
    }
    Box(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = this.size.minDimension / max(matrix.width, matrix.height)
            for (y in 0 until matrix.height) {
                var x = 0
                while (x < matrix.width) {
                    if (matrix[x, y]) {
                        val start = x
                        while (x + 1 < matrix.width && matrix[x + 1, y]) x++
                        drawRect(Color(0xFF12131A), Offset(start * cell, y * cell), Size((x - start + 1) * cell + 0.5f, cell + 0.5f))
                    }
                    x++
                }
            }
        }
    }
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(Orbit.Surface2).border(1.dp, Orbit.Stroke, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = MaterialTheme.typography.labelLarge, color = Orbit.Text2)
    }
}

@Composable
fun GradientProgress(progress: Float, modifier: Modifier = Modifier, height: Dp = 8.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(Orbit.GradientH))
    }
}

/** Primary action: brand gradient-ish fill, scales and glows on focus (TV focus must be obvious from 3 m). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ButtonDefaults.colors(
            containerColor = Orbit.Indigo, contentColor = Color.White,
            focusedContainerColor = Orbit.IndigoBright, focusedContentColor = Color.White,
            pressedContainerColor = Orbit.Indigo, pressedContentColor = Color.White,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.06f),
    ) { ButtonContent(text, icon) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = OutlinedButtonDefaults.shape(RoundedCornerShape(14.dp)),
        colors = OutlinedButtonDefaults.colors(
            containerColor = Orbit.Surface2, contentColor = Orbit.Text,
            focusedContainerColor = Orbit.Text, focusedContentColor = Orbit.Bg,
        ),
        border = OutlinedButtonDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(1.dp, Orbit.Stroke)),
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Orbit.Text)),
        ),
        scale = OutlinedButtonDefaults.scale(focusedScale = 1.06f),
    ) { ButtonContent(text, icon) }
}

@Composable
private fun RowScope.ButtonContent(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
    }
    Text(text, style = MaterialTheme.typography.labelLarge)
}

fun formatBytes(bytes: Double): String {
    if (bytes < 1024) return "${bytes.toLong()} B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var b = bytes; var i = -1
    do { b /= 1024; i++ } while (b >= 1024 && i < units.size - 1)
    val n = when { b >= 100 -> "%.0f"; b >= 10 -> "%.1f"; else -> "%.2f" }.format(b)
    return "$n ${units[i]}"
}

fun formatDuration(seconds: Double): String {
    if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return "—"
    val s = seconds.toLong()
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${"%02d".format(s % 60)}s"
        else -> "${s / 3600}h ${"%02d".format(s % 3600 / 60)}m"
    }
}
