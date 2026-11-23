package org.lilyengine.harness

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small icons for the editing bars: the standard Material shapes for undo,
 * redo, delete, copy, cut, paste and "more" (24-unit paths, Apache 2.0), and
 * note values drawn as notes.
 */
object Glyphs {
    private fun icon(name: String, path: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
            .build()

    val Undo = icon("undo", "M12.5,8c-2.65,0 -5.05,0.99 -6.9,2.6L2,7v9h9l-3.62,-3.62c1.39,-1.16 3.16,-1.88 5.12,-1.88 3.54,0 6.55,2.31 7.6,5.5l2.37,-0.78C21.08,11.03 17.15,8 12.5,8z")
    val Redo = icon("redo", "M18.4,10.6C16.55,8.99 14.15,8 11.5,8c-4.65,0 -8.58,3.03 -9.96,7.22L3.9,16c1.05,-3.19 4.05,-5.5 7.6,-5.5 1.95,0 3.73,0.72 5.12,1.88L13,16h9V7l-3.6,3.6z")
    val Delete = icon("delete", "M6,19c0,1.1 0.9,2 2,2h8c1.1,0 2,-0.9 2,-2V7H6v12zM19,4h-3.5l-1,-1h-5l-1,1H5v2h14V4z")
    val Copy = icon("copy", "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 -0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z")
    val Cut = icon("cut", "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 1.14,-0.13 1.64,-0.36L10,12l-2.36,2.36C7.14,14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4c0,-0.59 -0.13,-1.14 -0.36,-1.64L12,14l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM6,20c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM12,12.5c-0.28,0 -0.5,-0.22 -0.5,-0.5s0.22,-0.5 0.5,-0.5 0.5,0.22 0.5,0.5 -0.22,0.5 -0.5,0.5zM19,3l-6,6 2,2 7,-7L22,3z")
    val Paste = icon("paste", "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 -0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z")
    val More = icon("more", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")
}

/**
 * A note value drawn as a note: "1" whole, "2" half, "4" quarter, "8"
 * eighth, "16", "32", with a dot if [dotted].
 */
@Composable
fun DurationGlyph(duration: String, color: Color, dotted: Boolean = false, size: Dp = 26.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        val hollow = duration == "1" || duration == "2"
        val head = Offset(9f * u, 18f * u)
        // A tilted oval head, as engraved.
        rotate(-20f, head) {
            val sz = Size(9f * u, 6.4f * u)
            val tl = Offset(head.x - sz.width / 2, head.y - sz.height / 2)
            if (hollow) drawOval(color, tl, sz, style = Stroke(1.6f * u)) else drawOval(color, tl, sz)
        }
        if (duration != "1") {
            val stemX = 13f * u
            drawLine(color, Offset(stemX, 17.5f * u), Offset(stemX, 3f * u), strokeWidth = 1.5f * u)
            val flags = when (duration) { "8" -> 1; "16" -> 2; "32" -> 3; else -> 0 }
            repeat(flags) { i ->
                val y = 3f * u + i * 3.6f * u
                val flag = Path().apply {
                    moveTo(stemX, y)
                    cubicTo(stemX + 1f * u, y + 3f * u, stemX + 6f * u, y + 4f * u, stemX + 4.5f * u, y + 9f * u)
                    cubicTo(stemX + 4.5f * u, y + 6f * u, stemX + 2f * u, y + 4.5f * u, stemX, y + 4.2f * u)
                    close()
                }
                drawPath(flag, color)
            }
        }
        if (dotted) drawCircle(color, 1.6f * u, Offset(17.5f * u, 18f * u))
    }
}
