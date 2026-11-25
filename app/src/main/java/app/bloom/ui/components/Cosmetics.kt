package app.bloom.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bloom.core.Fill
import app.bloom.core.User
import app.bloom.ui.theme.Ui
import kotlin.math.cos
import kotlin.math.sin

/*
 * Root cosmetics (Root 0.9.46+): name effects are colour/gradient fills that other people
 * see on your name; profile backgrounds tint your profile card. Bloom draws both natively.
 */

/** Gradient across the drawn size at Root's angle (degrees, 0 = left → right). [phase] slides it. */
private class AngleGradient(val colors: List<Color>, val angle: Float, val phase: Float) : ShaderBrush() {
    override fun createShader(size: Size): Shader {
        val rad = Math.toRadians(angle.toDouble())
        val dx = cos(rad).toFloat(); val dy = sin(rad).toFloat()
        val half = (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        val shift = Offset(dx, dy) * (phase * half * 2f)
        // mirror the colours so a sliding shimmer loops without a seam
        val cs = if (phase != 0f) colors + colors.reversed().drop(1) else colors
        return LinearGradientShader(c - Offset(dx, dy) * half + shift, c + Offset(dx, dy) * half + shift, cs, tileMode = if (phase != 0f) TileMode.Mirror else TileMode.Clamp)
    }
    override fun equals(other: Any?) = other is AngleGradient && other.colors == colors && other.angle == angle && other.phase == phase
    override fun hashCode() = (colors.hashCode() * 31 + angle.hashCode()) * 31 + phase.hashCode()
}

fun Fill.colorsAsCompose(): List<Color> = colors.map { Color(it) }

/** A brush for a fill; null for no fill. Solid fills become a one-colour brush. */
fun fillBrush(f: Fill?, phase: Float = 0f): Brush? = when {
    f == null || f.colors.isEmpty() -> null
    f.solid -> androidx.compose.ui.graphics.SolidColor(Color(f.colors[0]))
    else -> AngleGradient(f.colorsAsCompose(), f.angle, phase)
}

/**
 * Someone's name, drawn with their Root name effect when they wear one.
 * [animated] makes gradients drift slowly (profile only — lists stay static and cheap).
 */
@Composable
fun UserNameText(user: User, style: TextStyle, fallback: Color, modifier: Modifier = Modifier, animated: Boolean = false, maxLines: Int = 1) {
    val effect = user.effectId?.let { CosmeticStore.builtInEffects[it] }
    // prism = moving rainbow, whatever colours were picked
    val fill = if (effect == "prism") Fill(listOf(0xFFFF6B6B, 0xFFFFD66B, 0xFF9DFF6B, 0xFF6BD5FF, 0xFFB18CFF).map { it.toInt() }, 0f) else user.nameFill
    val moving = (animated || effect == "prism") && fill != null && !fill.solid && Ui.animations && Ui.cosmeticFrames
    val phase = if (moving) {
        val t = rememberInfiniteTransition(label = "nameFx")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween((6000 / Ui.speed).toInt(), easing = LinearEasing), RepeatMode.Restart), label = "nameFxPhase").value
    } else 0f
    val brush = remember(fill, phase) { fillBrush(fill, phase) }
    val base = fill?.colors?.firstOrNull()?.let { Color(it) } ?: fallback
    // Root name font cosmetic (downloaded TTF/OTF)
    val fontAsset = if (Ui.cosmeticFrames) user.fontId?.let { CosmeticStore.assets[it] } else null
    val family = remember(fontAsset?.font) {
        fontAsset?.font?.let { f -> runCatching { androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Font(f, androidx.compose.ui.text.font.FontWeight(fontAsset.fontWeight))) }.getOrNull() }
    }
    var st = if (brush != null) style.copy(brush = brush) else style.copy(color = fallback)
    if (family != null) st = st.copy(fontFamily = family, fontSize = style.fontSize * fontAsset!!.sizeScale)
    if (Ui.cosmeticFrames) st = when (effect) {
        "neon" -> st.copy(shadow = androidx.compose.ui.graphics.Shadow(base.copy(alpha = 0.9f), Offset.Zero, 14f))
        "toon" -> st.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.85f), Offset(2.5f, 2.5f), 0f))
        "pop" -> st.copy(shadow = androidx.compose.ui.graphics.Shadow(base.copy(red = base.red * 0.45f, green = base.green * 0.45f, blue = base.blue * 0.45f), Offset(0f, 3.5f), 1f))
        "gummy" -> st.copy(shadow = androidx.compose.ui.graphics.Shadow(base.copy(alpha = 0.55f), Offset(0f, 2f), 7f))
        "prism" -> st.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = 0.35f), Offset.Zero, 8f))
        else -> st
    }
    Text(user.displayName, style = st, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * Root card-border cosmetic: the art is a 9-slice (corners keep their size, edges stretch).
 * Insets are in image pixels; Root's art is drawn at @3x, so 3 px = 1 dp.
 */
fun Modifier.cosmeticBorder(asset: CosmeticAsset?): Modifier {
    val file = asset?.art ?: return this
    if (!Ui.cosmeticFrames) return this
    return this.then(Modifier.drawWithCache {
        val bmp = BorderCache.get(file)
        onDrawWithContent {
            drawContent()
            if (bmp == null) return@onDrawWithContent
            val w = bmp.width; val h = bmp.height
            val (l, t, r, b) = asset.insets.map { it.toInt().coerceAtLeast(0) }.let { if (it.all { v -> v == 0 }) listOf(w / 3, h / 3, w / 3, h / 3) else it }
            val k = density / 3f
            val dl = l * k; val dt = t * k; val dr = r * k; val db = b * k
            val xs = floatArrayOf(0f, dl, size.width - dr, size.width)
            val ys = floatArrayOf(0f, dt, size.height - db, size.height)
            val sx = intArrayOf(0, l, w - r, w); val sy = intArrayOf(0, t, h - b, h)
            for (i in 0..2) for (j in 0..2) {
                val sw = sx[i + 1] - sx[i]; val sh = sy[j + 1] - sy[j]
                val dw = xs[i + 1] - xs[i]; val dh = ys[j + 1] - ys[j]
                if (sw <= 0 || sh <= 0 || dw <= 0f || dh <= 0f) continue
                if (i == 1 && j == 1) continue // the middle stays see-through
                drawImage(bmp, androidx.compose.ui.unit.IntOffset(sx[i], sy[j]), androidx.compose.ui.unit.IntSize(sw, sh),
                    androidx.compose.ui.unit.IntOffset(xs[i].toInt(), ys[j].toInt()), androidx.compose.ui.unit.IntSize(dw.toInt().coerceAtLeast(1), dh.toInt().coerceAtLeast(1)))
            }
        }
    })
}

private object BorderCache {
    private val map = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()
    fun get(f: java.io.File) = map.getOrPut(f.path) { runCatching { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
}

/** Chips for the cosmetics someone wears (avatar frames etc. Bloom can't draw yet are still listed). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WornCosmetics(worn: List<String>, modifier: Modifier = Modifier) {
    if (worn.isEmpty()) return
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        worn.forEach { w -> SuggestionChip(onClick = {}, label = { Text(w, style = MaterialTheme.typography.labelMedium) }) }
    }
}

/** Java-style entry point for [cosmeticBorder] (handy where the extension isn't imported). */
fun cosmeticBorderOf(m: Modifier, asset: CosmeticAsset?): Modifier = m.cosmeticBorder(asset)
