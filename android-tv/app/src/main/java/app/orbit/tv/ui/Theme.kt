package app.orbit.tv.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

/** Orbit design tokens — same palette as the Windows app and the iPhone page (dark, TV-first). */
object Orbit {
    val Indigo = Color(0xFF5B5BF6)
    val IndigoBright = Color(0xFF7B7BFF)
    val Cyan = Color(0xFF22C3EE)
    val Bg = Color(0xFF0E0F14)
    val Surface = Color(0xFF171922)
    val Surface2 = Color(0xFF20232F)
    val Stroke = Color(0x14FFFFFF)
    val Text = Color(0xFFF3F4F8)
    val Text2 = Color(0xFFA2A7B8)
    val Text3 = Color(0xFF6E7386)
    val Success = Color(0xFF34D399)
    val Danger = Color(0xFFF87171)
    val Warning = Color(0xFFFBBF24)
    val Gradient = Brush.linearGradient(listOf(Indigo, Cyan))
    val GradientH = Brush.horizontalGradient(listOf(Indigo, Cyan))

    val RadiusSm = RoundedCornerShape(10.dp)
    val RadiusMd = RoundedCornerShape(16.dp)
    val RadiusLg = RoundedCornerShape(24.dp)
}

private val typography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun OrbitTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Orbit.Indigo,
            onPrimary = Color.White,
            primaryContainer = Orbit.Indigo,
            onPrimaryContainer = Color.White,
            secondary = Orbit.Cyan,
            background = Orbit.Bg,
            onBackground = Orbit.Text,
            surface = Orbit.Surface,
            onSurface = Orbit.Text,
            surfaceVariant = Orbit.Surface2,
            onSurfaceVariant = Orbit.Text2,
            inverseSurface = Orbit.Text,
            inverseOnSurface = Orbit.Bg,
            border = Orbit.Stroke,
            borderVariant = Orbit.Stroke,
            error = Orbit.Danger,
        ),
        typography = typography,
        content = content,
    )
}
