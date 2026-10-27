package com.nuvio.tv.ui.screens.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Quality button icon: a rounded screen with two nested brackets inside, the inner one filled. */
internal val QualityIcon: ImageVector by lazy {
    val stroke = SolidColor(Color.White)
    ImageVector.Builder(
        name = "PlayerQuality",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Screen
        path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(4.5f, 4.9f)
            horizontalLineTo(19.5f)
            quadTo(21.6f, 4.9f, 21.6f, 7f)
            verticalLineTo(17f)
            quadTo(21.6f, 19.1f, 19.5f, 19.1f)
            horizontalLineTo(4.5f)
            quadTo(2.4f, 19.1f, 2.4f, 17f)
            verticalLineTo(7f)
            quadTo(2.4f, 4.9f, 4.5f, 4.9f)
            close()
        }
        // Outer bracket: down the left side and across the top
        path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Square) {
            moveTo(6.3f, 16f)
            verticalLineTo(8.4f)
            horizontalLineTo(17.9f)
        }
        // Inner bracket
        path(stroke = stroke, strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Square) {
            moveTo(9.8f, 16f)
            verticalLineTo(11.8f)
            horizontalLineTo(17.9f)
        }
        // Filled block in the bottom-right corner
        path(fill = stroke) {
            moveTo(13f, 13.6f)
            horizontalLineTo(18.8f)
            verticalLineTo(16.9f)
            horizontalLineTo(13f)
            close()
        }
    }.build()
}
