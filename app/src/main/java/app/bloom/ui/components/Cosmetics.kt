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
    val fill = user.nameFill
    val phase = if (animated && fill != null && !fill.solid && Ui.animations) {
        val t = rememberInfiniteTransition(label = "nameFx")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween((6000 / Ui.speed).toInt(), easing = LinearEasing), RepeatMode.Restart), label = "nameFxPhase").value
    } else 0f
    val brush = remember(fill, phase) { fillBrush(fill, phase) }
    Text(
        user.displayName,
        style = if (brush != null) style.copy(brush = brush) else style.copy(color = fallback),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
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
