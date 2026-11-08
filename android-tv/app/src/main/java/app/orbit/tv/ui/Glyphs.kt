package app.orbit.tv.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Outline TV glyph (matches the web page's icon). */
val TvGlyph: ImageVector = ImageVector.Builder("tv", 24.dp, 24.dp, 24f, 24f).apply {
    path(stroke = SolidColor(Color.White), strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(5.5f, 5f); lineTo(18.5f, 5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 21f, 7.5f); lineTo(21f, 14.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 18.5f, 17f); lineTo(5.5f, 17f)
        arcTo(2.5f, 2.5f, 0f, false, true, 3f, 14.5f); lineTo(3f, 7.5f)
        arcTo(2.5f, 2.5f, 0f, false, true, 5.5f, 5f); close()
        moveTo(8f, 20f); lineTo(16f, 20f)
    }
}.build()
