package com.mux.video.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Bg = Color(0xFF03070B)
val Mono = FontFamily.Monospace

class AccentTheme(val name: String, val main: Color, val dim: Color)

val ACCENTS = listOf(
    AccentTheme("CYAN", Color(0xFF00E5FF), Color(0xFF0A5C68)),
    AccentTheme("GOLD", Color(0xFFFFB300), Color(0xFF6B4A00)),
    AccentTheme("GREEN", Color(0xFF00FF66), Color(0xFF05703A)),
    AccentTheme("RED", Color(0xFFFF3D3D), Color(0xFF6B1414))
)
private val theme: AccentTheme get() = ACCENTS[Store.accent.coerceIn(0, ACCENTS.lastIndex)]
val cMain: Color get() = theme.main
val cDim: Color get() = theme.dim
val cSub: Color get() = theme.main.copy(alpha = 0.6f)

@Composable
fun T(
    text: String, size: Float = 14f, color: Color = cMain, bold: Boolean = false,
    mod: Modifier = Modifier, lines: Int = 1, align: TextAlign = TextAlign.Start
) = Text(
    text, modifier = mod, color = color, fontFamily = Mono, fontSize = size.sp,
    fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    maxLines = lines, overflow = TextOverflow.Ellipsis, textAlign = align
)

@Composable
fun HudBackground(content: @Composable BoxScope.() -> Unit) {
    val line = cDim.copy(alpha = 0.22f)
    Box(
        Modifier.fillMaxSize().background(Bg).drawBehind {
            val step = 36.dp.toPx()
            var x = 0f
            while (x < size.width) { drawLine(line, Offset(x, 0f), Offset(x, size.height)); x += step }
            var y = 0f
            while (y < size.height) { drawLine(line, Offset(0f, y), Offset(size.width, y)); y += step }
        },
        content = content
    )
}

@Composable
fun Chip(label: String, on: Boolean, mod: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        mod.border(1.dp, if (on) cMain else cDim)
            .background(if (on) cDim.copy(alpha = 0.5f) else Color.Transparent)
            .clickable(onClick = onClick).padding(vertical = 10.dp),
        Alignment.Center
    ) { T(label, 11f, if (on) cMain else cSub) }
}

/** HUD corner brackets */
fun Modifier.brackets(c: Color, len: Dp = 8.dp): Modifier = drawBehind {
    val l = len.toPx()
    val s = 1.5.dp.toPx()
    val w = size.width
    val h = size.height
    fun ln(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(c, Offset(x1, y1), Offset(x2, y2), s)
    ln(0f, 0f, l, 0f); ln(0f, 0f, 0f, l); ln(w, 0f, w - l, 0f); ln(w, 0f, w, l)
    ln(0f, h, l, h); ln(0f, h, 0f, h - l); ln(w, h, w - l, h); ln(w, h, w, h - l)
}

enum class G { PLAY, PAUSE, NEXT, PREV }

@Composable
fun Glyph(g: G, sz: Dp, color: Color, onClick: () -> Unit) {
    Canvas(Modifier.size(sz).clickable(onClick = onClick)) {
        val w = this.size.width
        val h = this.size.height
        fun tri(a: Float, b: Float) = drawPath(Path().apply {
            moveTo(w * a, h * .25f); lineTo(w * b, h * .5f); lineTo(w * a, h * .75f); close()
        }, color)
        when (g) {
            G.PLAY -> drawPath(Path().apply {
                moveTo(w * .28f, h * .2f); lineTo(w * .8f, h * .5f); lineTo(w * .28f, h * .8f); close()
            }, color)
            G.PAUSE -> {
                drawRect(color, Offset(w * .27f, h * .2f), Size(w * .17f, h * .6f))
                drawRect(color, Offset(w * .56f, h * .2f), Size(w * .17f, h * .6f))
            }
            G.NEXT -> {
                tri(.2f, .65f)
                drawRect(color, Offset(w * .7f, h * .25f), Size(w * .1f, h * .5f))
            }
            G.PREV -> {
                drawPath(Path().apply {
                    moveTo(w * .8f, h * .25f); lineTo(w * .35f, h * .5f); lineTo(w * .8f, h * .75f); close()
                }, color)
                drawRect(color, Offset(w * .2f, h * .25f), Size(w * .1f, h * .5f))
            }
        }
    }
}
