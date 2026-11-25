package app.bloom.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bloom.core.Platform
import app.bloom.core.Status
import app.bloom.ui.theme.IconMode
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion
import app.bloom.ui.theme.contentOn
import app.bloom.ui.theme.hueColor
import kotlin.random.Random

/** userId -> custom profile picture (e.g. the one you picked in Settings). */
val LocalAvatars = compositionLocalOf<Map<Long, ImageBitmap>> { emptyMap() }

/** userId -> real profile picture URL from the backend. */
val LocalAvatarUrls = compositionLocalOf<Map<Long, String>> { emptyMap() }
/** userId → Root avatar-frame cosmetic id */
val LocalAvatarFrames = compositionLocalOf<Map<Long, String>> { emptyMap() }

@Composable
fun statusColor(s: Status): Color = when (s) {
    Status.ONLINE -> Color(0xFF23A55A)
    Status.IDLE -> Color(0xFFF0B232)
    Status.DND -> Color(0xFFF23F43)
    Status.OFFLINE -> Color(0xFF80848E)
}

/**
 * Profile picture. Uses the user's image if there is one, otherwise a generated
 * picture unique to the user: a two-tone gradient with soft light blobs and their
 * initial (stable per [seed], so a person always looks the same).
 */
@Composable
fun Avatar(
    name: String,
    hue: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    userId: Long? = null,
    status: Status? = null,
    shape: Shape = CircleShape,
) {
    val image = userId?.let { LocalAvatars.current[it] }
    val url = userId?.let { LocalAvatarUrls.current[it] }
    Box(modifier.size(size)) {
        Box(Modifier.matchParentSize().clip(shape), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(image, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (url != null) {
                // generated art underneath doubles as the loading/error placeholder
                GeneratedAvatarArt(name, hue, (userId ?: 0L).toInt())
                coil3.compose.AsyncImage(url, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                GeneratedAvatarArt(name, hue, (userId ?: name.hashCode().toLong()).toInt())
                val fg = contentOn(hueColor(hue))
                Text(
                    name.take(1).uppercase(),
                    style = TextStyle(
                        color = fg,
                        fontWeight = FontWeight.Bold,
                        fontSize = (size.value * 0.42f).sp,
                        shadow = Shadow(Color.Black.copy(alpha = if (fg == Color.White) 0.35f else 0f), Offset(0f, 2f), 6f),
                    ),
                )
            }
        }
        // Root avatar frame: art on a 256 canvas where the picture fills the middle 168
        val frame = userId?.let { LocalAvatarFrames.current[it] }?.let { CosmeticStore.assets[it] }
        if (frame?.art != null && app.bloom.ui.theme.Ui.cosmeticFrames) {
            coil3.compose.AsyncImage(
                frame.art, null,
                Modifier.align(Alignment.Center).requiredSize(size * (frame.canvas / frame.avatarSize)),
                contentScale = ContentScale.Fit,
            )
        }
        if (status != null) {
            val c by animateColorAsState(statusColor(status), Motion.effects(), label = "status")
            val dot = size * 0.32f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(dot * 0.12f, dot * 0.12f)
                    .size(dot)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(dot * 0.18f)
                    .clip(CircleShape)
                    .background(c),
            )
        }
    }
}

@Composable
private fun GeneratedAvatarArt(name: String, hue: Int, seed: Int) {
    val a = hueColor(hue)
    val b = hueColor(hue + 40, tone = 0.42f)
    val c2 = hueColor(hue + 180).copy(alpha = 0.35f)
    val c3 = hueColor(hue + 270).copy(alpha = 0.35f)
    val blobs = remember(seed) {
        val r = Random(seed)
        List(3) { Triple(r.nextFloat(), r.nextFloat(), 0.35f + r.nextFloat() * 0.4f) }
    }
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.linearGradient(listOf(a, b), Offset.Zero, Offset(size.width, size.height)))
        blobs.forEachIndexed { i, (x, y, rr) ->
            val c = when (i) { 0 -> Color.White.copy(alpha = 0.28f); 1 -> c2; else -> c3 }
            val center = Offset(x * size.width, y * size.height)
            drawCircle(Brush.radialGradient(listOf(c, Color.Transparent), center, rr * size.minDimension), rr * size.minDimension, center)
        }
    }
}

@Composable
fun PlatformIcons(platforms: List<Platform>, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row {
        platforms.forEach {
            Icon(
                when (it) {
                    Platform.MOBILE -> Icons.Rounded.PhoneAndroid
                    Platform.DESKTOP -> Icons.Rounded.Computer
                    Platform.WEB -> Icons.Rounded.Language
                },
                contentDescription = it.name.lowercase(),
                tint = tint,
                modifier = Modifier.size(16.dp).padding(end = 2.dp),
            )
        }
    }
}

/**
 * Server icon, look-dependent:
 *  - Material You: a living shape that slowly rotates and morphs; selected = still rounded square + contour glow.
 *  - Discord style: circle that rounds into a square when selected.
 *  - Root style: squircle that tightens and gets an accent ring when selected.
 */
@Composable
fun ServerIcon(
    name: String,
    hue: Int,
    seed: Int,
    selected: Boolean,
    size: Dp = 50.dp,
    background: Color? = null,
    imageUrl: String? = null,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null,
) {
    val spec = LocalLook.current
    val scheme = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val bg = background ?: hueColor(hue)

    val shape: () -> Shape
    val glow: () -> Float
    var ring: Shape? = null
    when (spec.iconMode) {
        IconMode.LIVING -> {
            val living = rememberLivingShape(seed, selected)
            shape = { living.shape() }
            glow = { living.select }
        }
        IconMode.CIRCLE_TO_SQUARE -> {
            val p by animateFloatAsState(if (selected) 1f else 0f, Motion.spatial(), label = "dc")
            val morph = remember { BloomShapes.morph(BloomShapes.circle, BloomShapes.roundedSquare(spec.selectedCorner)) }
            shape = { MorphShape(morph, p) }
            glow = { 0f }
        }
        IconMode.SQUIRCLE -> {
            val p by animateFloatAsState(if (selected) 1f else 0f, Motion.spatial(), label = "rt")
            val morph = remember { BloomShapes.morph(BloomShapes.roundedSquare(0.42f), BloomShapes.roundedSquare(spec.selectedCorner)) }
            shape = { MorphShape(morph, p) }
            glow = { 0f }
            if (selected) ring = RoundedCornerShape(percent = (spec.selectedCorner * 100).toInt())
        }
    }

    Box(contentAlignment = Alignment.Center) {
        if (ring != null) Box(Modifier.size(size + 6.dp).border(2.dp, scheme.primary, ring))
        Box(
            Modifier
                .size(size)
                .pressScale(source)
                .contourGlow(shape, listOf(scheme.primary, scheme.tertiary, scheme.secondary), intensity = glow)
                .graphicsLayer { this.shape = shape(); clip = true }
                .background(bg)
                .clickable(source, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (content != null) content()
            else if (imageUrl != null) coil3.compose.AsyncImage(imageUrl, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(
                name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1).uppercase() },
                color = contentOn(bg),
                fontWeight = FontWeight.ExtraBold,
                fontSize = (size.value * 0.32f).sp,
            )
        }
    }
}


/** Cache key for remote images without the per-session signature query. */
class StableUrlKeyer : coil3.key.Keyer<coil3.Uri> {
    override fun key(data: coil3.Uri, options: coil3.request.Options): String? {
        val s = data.toString()
        if (!s.startsWith("http")) return null
        return if ("imagedelivery.net" in s || "rootapp" in s) s.substringBefore('?') else s
    }
}
