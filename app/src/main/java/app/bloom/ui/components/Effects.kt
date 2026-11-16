package app.bloom.ui.components

import android.graphics.BlurMaskFilter
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.NativePaint
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion

// Blur mask filters are immutable and keyed only by radius; reusing them avoids allocating
// one per glow per frame (living icons tick at 30 Hz, the chat panel glow on every slide frame).
private val maskCache = HashMap<Int, BlurMaskFilter>()
private fun mask(radiusPx: Float): BlurMaskFilter = maskCache.getOrPut(radiusPx.toInt().coerceAtLeast(1)) {
    BlurMaskFilter(radiusPx.toInt().coerceAtLeast(1).toFloat(), BlurMaskFilter.Blur.NORMAL)
}

private fun drawOutlinePath(canvas: android.graphics.Canvas, outline: androidx.compose.ui.graphics.Outline, paint: NativePaint) {
    when (outline) {
        is androidx.compose.ui.graphics.Outline.Generic -> canvas.drawPath(outline.path.asAndroidPath(), paint)
        is androidx.compose.ui.graphics.Outline.Rounded -> {
            val r = outline.roundRect
            canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, paint)
        }
        is androidx.compose.ui.graphics.Outline.Rectangle -> {
            val r = outline.rect
            canvas.drawRect(r.left, r.top, r.right, r.bottom, paint)
        }
    }
}


/**
 * Contour glow: a rotating sweep-gradient light that traces the outline of the
 * shape, with a gaussian-blurred halo for the bloom. Only drawn in looks with glow.
 * [shape] is a lambda so living (animated) shapes are read in the draw phase.
 */
@Composable
fun Modifier.contourGlow(
    shape: () -> Shape,
    colors: List<Color>,
    intensity: () -> Float = { 1f },
    stroke: Dp = 2.dp,
    glowRadius: Dp = 14.dp,
    periodMs: Int = 3600,
): Modifier {
    if (!LocalLook.current.glow) return this
    // The halo turns slowly; it only ticks while it's visible (intensity > 0), at 30 Hz
    // (a blurred sweep looks identical), so idle screens don't redraw 120 times a second.
    val angleState = remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val currentIntensity by androidx.compose.runtime.rememberUpdatedState(intensity)
    val live = app.bloom.ui.theme.Ui.effectsOn
    LaunchedEffect(periodMs, live) {
        if (!live) return@LaunchedEffect
        var last = 0L
        while (true) {
            if (currentIntensity() <= 0.01f) {
                // sleep until it shows up again
                androidx.compose.runtime.snapshotFlow { currentIntensity() > 0.01f }.first { it }
                last = 0L
            }
            withFrameNanos { t ->
                if (last != 0L) angleState.floatValue = (angleState.floatValue + (t - last) / 1e6f / periodMs * 360f) % 360f
                last = t
            }
            kotlinx.coroutines.delay(33)
        }
    }
    val angle by angleState
    val argb = remember(colors) { (colors + colors.first()).map { it.toArgb() }.toIntArray() }
    val halo = remember { NativePaint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.STROKE } }
    val line = remember { NativePaint().apply { isAntiAlias = true; style = android.graphics.Paint.Style.STROKE } }
    val matrix = remember { android.graphics.Matrix() }
    return drawBehind {
        val k = intensity().coerceIn(0f, 1f)
        if (k <= 0.01f) return@drawBehind
        val outline = shape().createOutline(size, layoutDirection, this)
        val cx = size.width / 2
        val cy = size.height / 2
        val shader = SweepGradient(cx, cy, argb, null).apply { matrix.setRotate(angle, cx, cy); setLocalMatrix(matrix) }
        drawIntoCanvas { c ->
            halo.shader = shader
            halo.strokeWidth = stroke.toPx() * 3f
            halo.maskFilter = mask(glowRadius.toPx())
            halo.alpha = (k * 200).toInt()
            drawOutlinePath(c.nativeCanvas, outline, halo)
            line.shader = shader
            line.strokeWidth = stroke.toPx()
            line.alpha = (k * 255).toInt()
            drawOutlinePath(c.nativeCanvas, outline, line)
        }
    }
}

@Composable
fun Modifier.contourGlow(shape: Shape, colors: List<Color>, intensity: Float = 1f, stroke: Dp = 2.dp, glowRadius: Dp = 14.dp): Modifier =
    contourGlow({ shape }, colors, { intensity }, stroke, glowRadius)

/** Soft coloured halo behind a shape (looks with glow only). */
@Composable
fun Modifier.softGlow(shape: Shape, color: Color, radius: Dp = 18.dp, alpha: Float = 0.55f): Modifier {
    if (!LocalLook.current.glow) return this
    val paint = remember { NativePaint().apply { isAntiAlias = true } }
    return drawBehind {
        val outline = shape.createOutline(size, layoutDirection, this)
        paint.color = color.copy(alpha = alpha).toArgb()
        paint.maskFilter = mask(radius.toPx())
        drawIntoCanvas { drawOutlinePath(it.nativeCanvas, outline, paint) }
    }
}

/** Press feedback: springy squish in expressive looks, subtle dip in flat ones. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, pressed: Float = 0.9f): Modifier {
    val isPressed by source.collectIsPressedAsState()
    val amount = if (LocalLook.current.bouncy) pressed else 1f - (1f - pressed) * 0.4f
    val s by animateFloatAsState(if (isPressed) amount else 1f, Motion.spatialFast(), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}
