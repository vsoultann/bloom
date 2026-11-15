package app.bloom.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Material You "expressive" shape set, built with androidx.graphics.shapes.
 * All polygons are normalised to the unit square so any two can morph.
 */
object BloomShapes {
    val circle = RoundedPolygon.circle(numVertices = 12).normalized()
    fun roundedSquare(corner: Float) =
        RoundedPolygon.rectangle(width = 2f, height = 2f, rounding = CornerRounding(corner * 2f, 1f)).normalized()

    /** Selected state in the Material look. */
    val square = roundedSquare(0.3f)
    val cookie = RoundedPolygon.star(9, innerRadius = 0.84f, rounding = CornerRounding(0.32f, 0.6f)).normalized()
    val clover = RoundedPolygon.star(4, innerRadius = 0.5f, rounding = CornerRounding(0.4f, 0.4f)).normalized()
    val flower = RoundedPolygon.star(8, innerRadius = 0.74f, rounding = CornerRounding(0.25f, 0.5f)).normalized()
    val pentagon = RoundedPolygon(5, rounding = CornerRounding(0.4f, 0.8f)).normalized()
    val gem = RoundedPolygon(6, rounding = CornerRounding(0.3f, 0.6f)).normalized()
    val sunny = RoundedPolygon.star(12, innerRadius = 0.88f, rounding = CornerRounding(0.18f)).normalized()
    val pill = RoundedPolygon.star(2, innerRadius = 0.7f, rounding = CornerRounding(0.9f)).normalized()

    /** Order the living icons cycle through. */
    val cycle = listOf(cookie, clover, flower, pentagon, sunny, gem)
    val resting = cycle

    private val cache = HashMap<Pair<RoundedPolygon, RoundedPolygon>, Morph>()
    fun morph(a: RoundedPolygon, b: RoundedPolygon): Morph = cache.getOrPut(a to b) { Morph(a, b) }
}

/** A Compose [Shape] that is a [Morph] frozen at [progress], optionally rotated. */
class MorphShape(private val morph: Morph, private val progress: Float, private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(progress.coerceIn(0f, 1f)).asComposePath()
        val m = Matrix()
        if (rotation != 0f) {
            m.translate(size.width / 2, size.height / 2)
            m.rotateZ(rotation)
            m.translate(-size.width / 2, -size.height / 2)
        }
        m.scale(size.width, size.height)
        path.transform(m)
        return Outline.Generic(path)
    }
}

/** Static polygon as a Shape. */
class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = polygon.toPath().asComposePath()
        path.transform(Matrix().apply { scale(size.width, size.height) })
        return Outline.Generic(path)
    }
}

private fun smooth(t: Float) = t * t * (3 - 2 * t)

/**
 * State for a "living" shape: slowly rotates and morphs through [BloomShapes.cycle]
 * while idle; when [selected] it finishes its current morph, snaps its spin to a
 * square-symmetric angle, and morphs into a static rounded square.
 *
 * All values are snapshot floats read in the draw phase, so the animation costs
 * no recomposition — only a redraw of the icon's layer.
 */
@Stable
class LivingShapeState(seed: Int) {
    private val n = BloomShapes.cycle.size
    /** Position in the cycle; integer part = current shape, fraction = morph progress. */
    var clock by mutableFloatStateOf(((seed and 0x7fffffff) % n).toFloat())
    var rotation by mutableFloatStateOf(((seed and 0x7fffffff) % 360).toFloat())
    /** 0 = living, 1 = selected rounded square. */
    var select by mutableFloatStateOf(0f)
    val direction = if (seed and 1 == 0) 1f else -1f

    /** Seconds each shape holds before morphing, and how long the morph takes. */
    private val hold = 2.4f
    private val morphTime = 1.1f
    private val period = hold + morphTime

    private fun local(): Pair<Int, Float> {
        val i = clock.toInt()
        val frac = clock - i
        val t = ((frac * period - hold) / morphTime).coerceIn(0f, 1f)
        return (i % n) to smooth(t)
    }

    /** True while partway between two shapes. */
    val midMorph get() = local().second.let { it > 0f && it < 1f }

    fun advance(dtSec: Float, selected: Boolean) {
        if (!selected && select < 0.05f) {
            clock = (clock + dtSec / period) % n
            rotation = (rotation + direction * dtSec * 14f) % 360f // ~26s per turn: slow and calm
        } else {
            // Finish any in-progress morph quickly so we land on a clean shape.
            if (midMorph) {
                val i = clock.toInt()
                clock = minOf(clock + dtSec * 4f / period, i + 1f) % n
            }
            // Ease spin to the nearest right angle (a rounded square looks the same every 90°).
            val target = (rotation / 90f).roundToInt() * 90f
            val d = target - rotation
            rotation = if (abs(d) < 0.2f) target else rotation + d * minOf(1f, dtSec * 9f)
        }
    }

    fun shape(): Shape {
        val (i, t) = local()
        val cur = BloomShapes.cycle[i]
        val next = BloomShapes.cycle[(i + 1) % n]
        return if (select > 0.001f && !midMorph) {
            val base = if (t >= 1f) next else cur
            MorphShape(BloomShapes.morph(base, BloomShapes.square), select, rotation)
        } else {
            MorphShape(BloomShapes.morph(cur, next), t, rotation)
        }
    }
}

@Composable
fun rememberLivingShape(seed: Int, selected: Boolean, animate: Boolean = true): LivingShapeState {
    val state = remember(seed) { LivingShapeState(seed) }
    val sel by rememberUpdatedState(selected)
    // Customize → Living icons: "always" keeps every icon gently morphing; "selected"
    // only animates the open one (and its select/deselect); "off" never morphs.
    val mode = app.bloom.ui.theme.Ui.livingIcons
    val always = mode == "always" && app.bloom.ui.theme.Ui.effectsOn
    LaunchedEffect(state, animate, selected, always, mode) {
        if (!animate || mode == "off") { state.select = if (selected) 1f else 0f; return@LaunchedEffect }
        var last = 0L
        var running = true
        while (running) withFrameNanos { now ->
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            state.advance(dt, sel)
            // critically-damped-ish follow for the select amount (with a touch of overshoot)
            val target = if (sel && !state.midMorph) 1f else 0f
            state.select += (target - state.select) * minOf(1f, dt * 11f)
            if (abs(target - state.select) < 0.002f) state.select = target
            // idle and nothing to show: stop asking for frames (lets the screen rest at low Hz)
            if (!always && state.select == target && !state.midMorph && (!sel || state.select == 1f)) running = false
        }
    }
    return state
}

/**
 * Expressive loading indicator: the shape spins while morphing through the set.
 */
@Composable
fun MorphingLoader(modifier: Modifier = Modifier, size: Dp = 48.dp, color: Color = MaterialTheme.colorScheme.primary) {
    val shapes = BloomShapes.cycle
    val t = rememberInfiniteTransition(label = "loader")
    val phase by t.animateFloat(0f, shapes.size.toFloat(), infiniteRepeatable(tween(650 * shapes.size, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "spin")
    Canvas(modifier.size(size)) {
        val i = phase.toInt() % shapes.size
        val p = smooth(phase - phase.toInt())
        val path: Path = BloomShapes.morph(shapes[i], shapes[(i + 1) % shapes.size]).toPath(p).asComposePath()
        path.transform(Matrix().apply { scale(this@Canvas.size.width, this@Canvas.size.height) })
        rotate(spin) { drawPath(path, color) }
    }
}
