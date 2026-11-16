package app.bloom.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.star
import androidx.graphics.shapes.rectangle
import app.bloom.ui.theme.LocalBgShapes
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.IconMode
import app.bloom.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Seconds since first composition (read it in draw lambdas only). [fps] caps how often
 * it ticks: the slow, blurred background doesn't need 120 updates a second, and every
 * tick makes the whole window redraw.
 */
@Composable
fun rememberFrameTime(fps: Int = 0): State<Float> = produceState(0f) {
    var start = 0L
    val gapMs = if (fps > 0) 1000L / fps else 0L
    while (true) {
        withFrameNanos { t ->
            if (start == 0L) start = t
            value = (t - start) / 1e9f
        }
        if (gapMs > 0) kotlinx.coroutines.delay(gapMs)
    }
}

private fun smooth(t: Float) = t * t * (3 - 2 * t)

/**
 * Material You background: big soft shapes that drift, rotate and morph,
 * blurred into a glow. Driven by [LocalBgShapes] (count / speeds / blur / opacity).
 */
@Composable
fun BackgroundShapes(modifier: Modifier = Modifier) {
    val cfg = LocalBgShapes.current
    if (!cfg.enabled || cfg.count <= 0) return
    val scheme = MaterialTheme.colorScheme
    val palette = listOf(scheme.primary, scheme.tertiary, scheme.secondary, scheme.primaryContainer, scheme.tertiaryContainer)
    val time by if (app.bloom.ui.theme.Ui.effectsOn) rememberFrameTime(fps = 30) else remember { mutableStateOf(0f) }
    val shapes = remember(cfg.count) {
        val r = Random(42)
        List(cfg.count) {
            BgShape(
                x = r.nextFloat(), y = r.nextFloat(),
                size = 0.28f + r.nextFloat() * 0.32f,
                dir = if (r.nextBoolean()) 1f else -1f,
                phase = r.nextFloat() * 6f,
                start = r.nextInt(BloomShapes.cycle.size),
                colorIndex = it,
            )
        }
    }
    // Drawn at quarter resolution and scaled up: the blur hides the difference and
    // costs 1/16th of the pixels. Static (no frame loop) in battery saver.
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxSize()) {
    val w = maxWidth; val h = maxHeight
    Canvas(
        Modifier.size(w / 4, h / 4)
            .graphicsLayer { scaleX = 4f; scaleY = 4f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f) }
            .blur((cfg.blur / 4f).dp, BlurredEdgeTreatment.Unbounded),
    ) {
        val n = BloomShapes.cycle.size
        val m = Matrix()
        shapes.forEach { s ->
            val t = time
            // gentle drift so nothing feels pinned
            val cx = (s.x + 0.04f * sin(t * 0.07f + s.phase)) * size.width
            val cy = (s.y + 0.04f * cos(t * 0.05f + s.phase)) * size.height
            val d = s.size * size.minDimension
            // morph: hold each shape, then flow into the next
            val cyc = t * cfg.morphSpeed / 60f + s.phase
            val i = cyc.toInt()
            val frac = cyc - i
            val p = smooth(((frac - 0.55f) / 0.45f).coerceIn(0f, 1f))
            val a = BloomShapes.cycle[(s.start + i) % n]
            val b = BloomShapes.cycle[(s.start + i + 1) % n]
            val path = BloomShapes.morph(a, b).toPath(p).asComposePath()
            m.reset()
            m.translate(cx - d / 2, cy - d / 2)
            m.translate(d / 2, d / 2)
            m.rotateZ(s.dir * cfg.rotateSpeed * t + s.phase * 60f)
            m.translate(-d / 2, -d / 2)
            m.scale(d, d)
            path.transform(m)
            drawPath(path, palette[s.colorIndex % palette.size].copy(alpha = cfg.opacity))
        }
    }
    }
}

private class BgShape(val x: Float, val y: Float, val size: Float, val dir: Float, val phase: Float, val start: Int, val colorIndex: Int)

/**
 * Bloom's mark: a soft eight-lobed bloom with a four-leaf core cut out of it
 * and a centre dot. In Material You the outer bloom slowly turns and breathes
 * between two lobe counts while the core counter-rotates; elsewhere it is still.
 */
@Composable
fun BloomLogo(size: Dp, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val living = LocalLook.current.iconMode == IconMode.LIVING
    val time by rememberFrameTime()
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val pop by animateFloatAsState(if (shown) 1f else 0f, app.bloom.ui.theme.Motion.spring(0.55f, 260f), label = "logoPop")
    val outerMorph = remember { BloomShapes.morph(LogoShapes.bloom8, LogoShapes.bloom12) }
    val core = remember { LogoShapes.clover.toPath().asComposePath() }
    Canvas(modifier.size(size)) {
        val t = if (living) time else 0f
        val d = this.size.minDimension
        val breathe = if (living) (0.5f + 0.5f * sin(t * 0.9f)) else 0f
        scale(pop.coerceAtLeast(0f)) {
            // soft halo
            drawCircle(Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.28f), Color.Transparent)), radius = d * 0.62f)
            val outer = outerMorph.toPath(breathe).asComposePath()
            outer.transform(Matrix().apply {
                translate(center.x, center.y); rotateZ(t * 8f + (1 - pop) * -90f); scale(d * 0.92f, d * 0.92f); translate(-0.5f, -0.5f)
            })
            drawPath(outer, Brush.linearGradient(listOf(scheme.primary, scheme.tertiary), Offset.Zero, Offset(d, d)))
            val inner = Path().apply { addPath(core) }
            inner.transform(Matrix().apply {
                translate(center.x, center.y); rotateZ(-t * 14f + 45f); scale(d * 0.42f, d * 0.42f); translate(-0.5f, -0.5f)
            })
            drawPath(inner, scheme.primaryContainer)
            drawCircle(scheme.onPrimaryContainer, radius = d * 0.07f)
        }
    }
}

/** Small, high-contrast silhouettes for password glyphs (round-ish shapes read as dots at 14dp). */
private object GlyphShapes {
    private fun r(c: Float) = androidx.graphics.shapes.CornerRounding(c)
    val set = listOf(
        androidx.graphics.shapes.RoundedPolygon.star(4, innerRadius = 0.45f, rounding = r(0.22f)),          // clover
        androidx.graphics.shapes.RoundedPolygon.star(5, innerRadius = 0.48f, rounding = r(0.1f)),           // star
        androidx.graphics.shapes.RoundedPolygon(3, rounding = r(0.22f)),                                     // triangle
        androidx.graphics.shapes.RoundedPolygon.rectangle(2f, 2f, rounding = r(0.35f)),                      // square
        androidx.graphics.shapes.RoundedPolygon(5, rounding = r(0.2f)),                                      // pentagon
        androidx.graphics.shapes.RoundedPolygon.star(8, innerRadius = 0.6f, rounding = r(0.08f)),           // burst
        androidx.graphics.shapes.RoundedPolygon(4, rounding = r(0.25f)),                                     // diamond
        androidx.graphics.shapes.RoundedPolygon.star(6, innerRadius = 0.55f, rounding = r(0.15f)),          // asterisk-ish
    ).map { it.normalized() }
}

private object LogoShapes {
    val bloom8 = androidx.graphics.shapes.RoundedPolygon.star(8, innerRadius = 0.78f, rounding = androidx.graphics.shapes.CornerRounding(0.28f, 0.7f)).normalized()
    val bloom12 = androidx.graphics.shapes.RoundedPolygon.star(12, innerRadius = 0.86f, rounding = androidx.graphics.shapes.CornerRounding(0.2f, 0.7f)).normalized()
    val clover = androidx.graphics.shapes.RoundedPolygon.star(4, innerRadius = 0.48f, rounding = androidx.graphics.shapes.CornerRounding(0.45f, 0.4f)).normalized()
}

/**
 * Eye toggle that morphs: the lids close into a lash line, the pupil shrinks
 * away and a slash draws itself across; reversing reopens with a bounce.
 */
@Composable
fun MorphingEye(revealed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val open by animateFloatAsState(if (revealed) 1f else 0f, app.bloom.ui.theme.Motion.spring(0.55f, 380f), label = "eyeOpen")
    val slash by animateFloatAsState(if (revealed) 0f else 1f, app.bloom.ui.theme.Motion.spring(1f, 500f), label = "eyeSlash")
    val src = remember { MutableInteractionSource() }
    Box(
        modifier.size(48.dp).pressScale(src, 0.8f).clickable(src, null, onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(26.dp)) {
            val w = size.width
            val h = size.height
            val o = open.coerceIn(-0.1f, 1.15f)
            val mid = h / 2
            val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            rotate(-14f * (1 - o.coerceIn(0f, 1f))) {
                // upper lid
                val up = Path().apply {
                    moveTo(w * 0.06f, mid)
                    quadraticTo(w / 2, mid - h * 0.62f * o.coerceAtLeast(0.08f), w * 0.94f, mid)
                }
                drawPath(up, tint, style = stroke)
                // lower lid folds up into the upper one as the eye closes
                val low = Path().apply {
                    moveTo(w * 0.06f, mid)
                    quadraticTo(w / 2, mid + h * 0.62f * o.coerceAtLeast(0f) + h * 0.18f * (1 - o.coerceIn(0f, 1f)), w * 0.94f, mid)
                }
                drawPath(low, tint.copy(alpha = 0.35f + 0.65f * o.coerceIn(0f, 1f)), style = stroke)
                // pupil
                val pr = w * 0.15f * o.coerceIn(0f, 1.15f)
                if (pr > 0.5f) drawCircle(tint, pr, Offset(w / 2, mid))
                // lashes appear when closed
                val l = 1 - o.coerceIn(0f, 1f)
                if (l > 0.05f) for (k in -1..1) {
                    val x = w / 2 + k * w * 0.22f
                    drawLine(tint.copy(alpha = l), Offset(x, mid + h * 0.12f), Offset(x + k * w * 0.05f, mid + h * 0.12f + h * 0.16f * l), stroke.width * 0.8f, StrokeCap.Round)
                }
            }
            // slash draws itself in
            if (slash > 0.01f) {
                val s = slash.coerceIn(0f, 1f)
                drawLine(tint, Offset(w * 0.1f, h * 0.1f), Offset(w * 0.1f + w * 0.8f * s, h * 0.1f + h * 0.8f * s), stroke.width, StrokeCap.Round)
            }
        }
    }
}

/** One typed character: its shape, and animation state read only while drawing. */
private class PwGlyph(val id: Long, val ch: Char, val shape: Int) {
    val life = Animatable(0f)
    val reveal = Animatable(0f)
    var leaving = false
}

/**
 * Password field where each typed character pops in as its own Material shape
 * (a spring pop with a spin while it morphs out of a dot). Deleting shrinks the
 * last one away. Revealing morphs each shape, one after another, into its letter.
 * Everything is drawn on one canvas, so animations never trigger layout work.
 */
@Composable
fun ShapePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onDone: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    label: String = "Password",
) {
    val scheme = MaterialTheme.colorScheme
    val spec = LocalLook.current
    val scope = rememberCoroutineScope()
    val glyphs = remember { mutableStateListOf<PwGlyph>() }
    var nextId by remember { mutableLongStateOf(0L) }
    val rng = remember { Random(System.nanoTime()) }
    var focused by remember { mutableStateOf(false) }
    val revealNow by rememberUpdatedState(revealed)

    fun add(c: Char) {
        // never the same shape twice in a row
        var shape = rng.nextInt(GlyphShapes.set.size)
        if (glyphs.lastOrNull()?.shape == shape) shape = (shape + 1) % GlyphShapes.set.size
        val g = PwGlyph(nextId++, c, shape)
        glyphs.add(g)
        scope.launch {
            if (revealNow) g.reveal.snapTo(1f)
            g.life.animateTo(1f, app.bloom.ui.theme.Motion.spring(0.5f, 520f))
        }
    }

    // Keep glyphs in sync with the text: append/pop at the end, rebuild on paste or replace.
    LaunchedEffect(value) {
        val live = glyphs.filter { !it.leaving }
        val liveText = live.joinToString("") { it.ch.toString() }
        when {
            value == liveText -> {}
            value.startsWith(liveText) -> value.substring(liveText.length).forEach(::add)
            liveText.startsWith(value) -> live.drop(value.length).forEach { g ->
                g.leaving = true
                scope.launch { g.life.animateTo(0f, app.bloom.ui.theme.Motion.spring(1f, 900f)); glyphs.remove(g) }
            }
            else -> { glyphs.clear(); value.forEach(::add) }
        }
    }
    // Reveal ripples along the word.
    LaunchedEffect(revealed) {
        glyphs.toList().forEachIndexed { i, g ->
            launch { delay(i * 22L); g.reveal.animateTo(if (revealed) 1f else 0f, app.bloom.ui.theme.Motion.spring(0.72f, 420f)) }
        }
    }

    val up by animateFloatAsState(if (focused || value.isNotEmpty()) 1f else 0f, app.bloom.ui.theme.Motion.spring(0.85f, 520f), label = "labelUp")
    val borderColor by androidx.compose.animation.animateColorAsState(if (focused) scheme.primary else scheme.outline, Motion.effects(), label = "pwb")
    val labelColor by androidx.compose.animation.animateColorAsState(if (focused) scheme.primary else scheme.onSurfaceVariant, Motion.effects(), label = "pwl")
    val borderW by animateFloatAsState(if (focused) 2f else 1f, Motion.effects(), label = "pww")
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodyLarge
    val labelLayout = remember(label, labelStyle) { measurer.measure(label, labelStyle) }
    val letterStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
    val letterCache = remember(letterStyle) { HashMap<Char, androidx.compose.ui.text.TextLayoutResult>() }
    val gray = scheme.onSurfaceVariant
    val shapePaths = remember { GlyphShapes.set.map { it.toPath().asComposePath() } }
    val time by rememberFrameTime()
    val radius = spec.rowRadius + 4.dp

    // 10dp of headroom above the border so the floating label isn't clipped.
    Box(modifier.fillMaxWidth().height(70.dp), contentAlignment = Alignment.CenterStart) {
        // Border with a notch for the floating label, plus the label itself.
        Canvas(Modifier.matchParentSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
            val sw = borderW.dp.toPx()
            val r = radius.toPx()
            val top = 10.dp.toPx()
            drawRoundRect(borderColor, Offset(sw / 2, top + sw / 2), Size(size.width - sw, size.height - top - sw), CornerRadius(r), style = Stroke(sw))
            val scaleL = 1f - 0.25f * up
            val startX = lerpF(52.dp.toPx(), 16.dp.toPx(), up)
            val lw = labelLayout.size.width * scaleL
            if (up > 0.01f) {
                val pad = 4.dp.toPx()
                drawRect(Color.Black, Offset(startX - pad, top - sw), Size((lw + pad * 2) * up, sw * 3), blendMode = BlendMode.Clear)
            }
            val lh = labelLayout.size.height * scaleL
            val cy = lerpF(top + (size.height - top) / 2, top, up)
            if (value.isEmpty() || up > 0.01f) {
                translate(startX, cy - lh / 2) {
                    scale(scaleL, scaleL, pivot = Offset.Zero) { drawText(labelLayout, color = labelColor) }
                }
            }
        }
        // The real (invisible) text field handles input, IME and accessibility.
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.Transparent),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxSize().padding(start = 52.dp, end = 52.dp, top = 10.dp).onFocusChanged { focused = it.isFocused },
        )
        Icon(Icons.Rounded.Lock, null, Modifier.padding(start = 16.dp, top = 10.dp).size(22.dp), tint = scheme.onSurfaceVariant)
        // Glyphs + caret.
        Canvas(Modifier.matchParentSize().padding(start = 52.dp, end = 52.dp, top = 10.dp).clipToBounds()) {
            val cell = 15.dp.toPx()
            val gap = 3.dp.toPx()
            val widths = glyphs.map { g ->
                val lt = letterCache.getOrPut(g.ch) { measurer.measure(g.ch.toString(), letterStyle) }
                val w = lerpF(cell, lt.size.width.toFloat(), g.reveal.value.coerceIn(0f, 1f))
                w * (if (g.leaving) g.life.value.coerceIn(0f, 1f) else 1f)
            }
            val letterGap = 0.5.dp.toPx()
            // shapes sit a little apart; revealed letters close up like normal text
            val gaps = glyphs.map { lerpF(gap, letterGap, it.reveal.value.coerceIn(0f, 1f)) }
            val caretW = if (focused) 2.dp.toPx() + gap else 0f
            val total = widths.sum() + gaps.dropLast(1).sum() + caretW
            var x = minOf(0f, size.width - total)
            val cy = size.height / 2
            glyphs.forEachIndexed { i, g ->
                val w = widths[i]
                val l = g.life.value
                val rv = g.reveal.value.coerceIn(0f, 1f)
                val cx = x + w / 2
                val shapeScale = (l * (1f - rv)).coerceAtLeast(0f)
                if (shapeScale > 0.01f) {
                    val path = if (!g.leaving && l < 0.999f)
                        BloomShapes.morph(BloomShapes.circle, GlyphShapes.set[g.shape]).toPath(l.coerceIn(0f, 1f)).asComposePath()
                    else shapePaths[g.shape]
                    withTransform({
                        translate(cx, cy)
                        rotate((1 - l) * -120f + rv * 90f + g.shape * 17f, pivot = Offset.Zero)
                        scale(cell * shapeScale, cell * shapeScale, pivot = Offset.Zero)
                        translate(-0.5f, -0.5f)
                    }) { drawPath(path, gray) }
                }
                if (rv > 0.01f) {
                    val lt = letterCache.getValue(g.ch)
                    val k = (rv * l).coerceIn(0f, 1.15f)
                    withTransform({
                        translate(cx, cy)
                        rotate((1 - rv) * -30f, pivot = Offset.Zero)
                        scale(k, k, pivot = Offset.Zero)
                    }) { drawText(lt, color = gray, alpha = k.coerceIn(0f, 1f), topLeft = Offset(-lt.size.width / 2f, -lt.size.height / 2f)) }
                }
                x += w + gaps[i]
            }
            if (focused) {
                val a = (0.5f + 0.5f * cos(time * 2 * PI.toFloat() / 1.1f)).coerceIn(0f, 1f)
                drawRect(scheme.primary.copy(alpha = a), Offset(x, cy - 11.dp.toPx()), Size(2.dp.toPx(), 22.dp.toPx()))
            }
        }
        MorphingEye(revealed, onToggleReveal, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp, top = 10.dp))
    }
}

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Panel colour: slightly translucent when background shapes are on, so they glow through. */
@Composable
fun panel(c: Color): Color = if (LocalBgShapes.current.enabled) c.copy(alpha = c.alpha * 0.74f) else c
