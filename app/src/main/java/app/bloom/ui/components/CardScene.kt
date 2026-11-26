package app.bloom.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.bloom.ui.theme.Ui
import coil3.compose.AsyncImage
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File

/*
 * Root card-border scenes (Root 0.9.46). A border can be drawn by the client ("art":
 * {"source":"client","id":"shimmer"}) and/or carry a "scene": image layers placed on the
 * card, each with timing. Layer keys seen in Root's bundles:
 *   image, place ("box"), box {left/top "%" or units, width, height}, fit ("fill"),
 *   clip ("card"), blend ("screen"), opacity, when {theme}, rest ("hide"),
 *   timing {mode "once", playOn ["hover"], delay, fadeIn, fadeOut, end "hide"}.
 * Layers without a box cover the whole card. Units are dp (scene.scale is the art's
 * pixel density). "hover" layers play when the card is touched (and once when it opens).
 */

private class Layer(
    val image: File, val box: JSONObject?, val opacity: Float, val screen: Boolean,
    val once: Boolean, val onHover: Boolean, val delay: Float, val fadeIn: Float, val fadeOut: Float,
    val hideAfter: Boolean, val restHidden: Boolean, val clip: Boolean, val durationMs: Long,
)

private fun parseLayers(asset: CosmeticAsset, dark: Boolean): List<Layer> {
    val dir = asset.dir ?: return emptyList()
    val scene = asset.scene?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
    val arr = scene.optJSONArray("layers") ?: return emptyList()
    val canon = dir.canonicalPath
    return (0 until arr.length()).mapNotNull { i ->
        val l = arr.optJSONObject(i) ?: return@mapNotNull null
        l.optJSONObject("when")?.optString("theme")?.takeIf { it.isNotEmpty() }?.let { t -> if ((t == "dark") != dark) return@mapNotNull null }
        val name = l.optString("image").takeIf { it.isNotEmpty() && !it.contains("..") } ?: return@mapNotNull null
        val f = File(dir, name).takeIf { it.isFile && it.canonicalPath.startsWith(canon) } ?: return@mapNotNull null
        val t = l.optJSONObject("timing") ?: JSONObject()
        val play = t.optJSONArray("playOn")
        Layer(
            image = f, box = l.optJSONObject("box"),
            opacity = l.optDouble("opacity", 1.0).toFloat().coerceIn(0f, 1f),
            screen = l.optString("blend") == "screen",
            once = t.optString("mode") == "once",
            onHover = play != null && (0 until play.length()).any { play.optString(it) == "hover" },
            delay = t.optDouble("delay", 0.0).toFloat(), fadeIn = t.optDouble("fadeIn", 0.0).toFloat(), fadeOut = t.optDouble("fadeOut", 0.0).toFloat(),
            hideAfter = t.optString("end") == "hide", restHidden = l.optString("rest") == "hide",
            clip = l.optString("clip") == "card",
            durationMs = webpDurationMs(f),
        )
    }
}

/** Total frame time of an animated WebP (0 for a still image). */
internal fun webpDurationMs(f: File): Long = runCatching {
    val d = f.readBytes()
    if (d.size < 16 || String(d, 0, 4) != "RIFF" || String(d, 8, 4) != "WEBP") return 0
    var i = 12; var total = 0L
    while (i + 8 <= d.size) {
        val id = String(d, i, 4)
        val sz = (d[i + 4].toInt() and 0xFF) or ((d[i + 5].toInt() and 0xFF) shl 8) or ((d[i + 6].toInt() and 0xFF) shl 16) or ((d[i + 7].toInt() and 0xFF) shl 24)
        if (id == "ANMF" && i + 8 + 15 <= d.size) total += (d[i + 20].toInt() and 0xFF) or ((d[i + 21].toInt() and 0xFF) shl 8) or ((d[i + 22].toInt() and 0xFF) shl 16)
        i += 8 + sz + (sz and 1)
    }
    total
}.getOrDefault(0L)

private fun dim(v: Any?, whole: Dp): Dp? = when (v) {
    is Number -> v.toFloat().dp
    is String -> v.trim().let { s -> if (s.endsWith("%")) whole * (s.dropLast(1).toFloatOrNull() ?: return null) / 100f else s.toFloatOrNull()?.dp }
    else -> null
}

/**
 * Draws a card-border cosmetic over its card. [hover] bumps when the card is touched so
 * "hover" layers play again. Put it in a Box above the card's content.
 */
@Composable
fun CardBorderScene(asset: CosmeticAsset, shape: Shape, dark: Boolean, hover: Int, modifier: Modifier = Modifier) {
    if (!Ui.cosmeticFrames) return
    val layers = remember(asset, dark) { parseLayers(asset, dark) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth; val h = maxHeight
        Box(Modifier.fillMaxSize().clip(shape)) {
            layers.filter { it.clip }.forEach { SceneLayer(it, w, h, hover) }
        }
        layers.filter { !it.clip }.forEach { SceneLayer(it, w, h, hover) }
        if (asset.clientArt == "shimmer") ShimmerOutline(shape)
    }
}

@Composable
private fun SceneLayer(l: Layer, cardW: Dp, cardH: Dp, hover: Int) {
    val ctx = LocalContext.current
    val alpha = remember(l) { Animatable(if (l.restHidden || l.delay > 0f || l.fadeIn > 0f) 0f else 1f) }
    // a fresh request per play, so one-shot animations start from the first frame
    var play by remember(l) { mutableIntStateOf(0) }
    var visible by remember(l) { mutableStateOf(!l.restHidden && !l.onHover) }
    val key = if (l.onHover) hover else 0
    LaunchedEffect(l, key) {
        if (l.onHover && hover == 0) return@LaunchedEffect   // hover layers wait for a touch
        if (l.onHover) { alpha.snapTo(0f); visible = false }
        delay((l.delay * 1000).toLong())
        play++; visible = true
        if (l.fadeIn > 0f && Ui.animations) alpha.animateTo(l.opacity, tween((l.fadeIn * 1000).toInt())) else alpha.snapTo(l.opacity)
        if (l.once && l.durationMs > 0) {
            val fade = (l.fadeOut * 1000).toLong()
            delay((l.durationMs - fade).coerceAtLeast(0))
            if (l.hideAfter || l.restHidden) {
                if (fade > 0 && Ui.animations) alpha.animateTo(0f, tween(fade.toInt())) else alpha.snapTo(0f)
                visible = false
            }
        }
    }
    if (!visible) return
    val req = remember(l, play) {
        ImageRequest.Builder(ctx).data(l.image).memoryCacheKey("${l.image.path}#$play")
            .apply { if (l.once) repeatCount(0) }.build()
    }
    val box = l.box
    val m = if (box != null) {
        val bw = dim(box.opt("width"), cardW) ?: 36.dp
        val bh = dim(box.opt("height"), cardH) ?: bw
        Modifier.offset(dim(box.opt("left"), cardW) ?: 0.dp, dim(box.opt("top"), cardH) ?: 0.dp).size(bw, bh)
    } else Modifier.fillMaxSize()
    AsyncImage(
        req, null,
        m.graphicsLayer {
            this.alpha = alpha.value
            if (l.screen) compositingStrategy = CompositingStrategy.Offscreen
        }.then(if (l.screen) Modifier.screenBlend() else Modifier),
        contentScale = ContentScale.FillBounds,
    )
}

/** Draws children with the "screen" blend over what's below (lightens, like light on glass). */
private fun Modifier.screenBlend(): Modifier = this.then(Modifier.graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
    blendMode = BlendMode.Screen
})

/** Root's client-drawn "shimmer" border: a soft light sweeping round the card's edge. */
@Composable
private fun ShimmerOutline(shape: Shape) {
    val t = if (Ui.animations) rememberInfiniteTransition(label = "shimmer").animateFloat(0f, 1f, infiniteRepeatable(tween((3200 / Ui.speed).toInt(), easing = LinearEasing), RepeatMode.Restart), label = "sh").value else 0.15f
    Canvas(Modifier.fillMaxSize()) {
        val r = 28.dp.toPx()
        val sw = 2.dp.toPx()
        val c = Offset(size.width / 2, size.height / 2)
        val ang = t * 2 * Math.PI
        val dx = kotlin.math.cos(ang).toFloat() * size.maxDimension; val dy = kotlin.math.sin(ang).toFloat() * size.maxDimension
        val sweep = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.08f), 0.42f to Color.White.copy(alpha = 0.08f), 0.5f to Color.White.copy(alpha = 0.85f),
            0.58f to Color.White.copy(alpha = 0.08f), 1f to Color.White.copy(alpha = 0.08f),
            start = c - Offset(dx, dy) / 2f, end = c + Offset(dx, dy) / 2f,
        )
        drawRoundRect(sweep, Offset(sw / 2, sw / 2), androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw), CornerRadius(r), style = Stroke(sw))
    }
}
