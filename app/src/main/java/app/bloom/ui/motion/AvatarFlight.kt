package app.bloom.ui.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.bloom.AppViewModel
import app.bloom.ui.components.Avatar
import app.bloom.ui.theme.Ui
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Shared-element "flight" for a profile picture: the picture leaves where you tapped it
 * and travels — moving and scaling along a fluid spring — onto the spot where it belongs
 * on the next screen. The landing spot is tracked live every frame, so it lands exactly
 * even while that screen is still sliding/scaling into place.
 */
object AvatarFlight {
    var userId by mutableStateOf<Long?>(null); private set
    var from by mutableStateOf<Rect?>(null); private set
    /** the landing spot's live coordinates (read every frame) */
    var target by mutableStateOf<LayoutCoordinates?>(null)
    val progress = Animatable(1f)
    /** 0 → only the flying copy shows, 1 → only the real picture (cross-fade at landing) */
    val handoff = Animatable(0f)
    var inAir by mutableStateOf(false); private set

    fun launch(user: Long, source: Rect?) {
        if (source == null || !Ui.animations) { userId = null; return }
        userId = user; from = source; target = null; inAir = true
    }

    fun land() { inAir = false; userId = null; from = null; target = null }
}

/** Remembers where this picture is so a flight can start from it; hides it while its copy flies. */
fun Modifier.flightSource(user: Long, onBounds: (Rect) -> Unit): Modifier =
    onGloballyPositioned { onBounds(it.boundsInWindow()) }
        .graphicsLayer { alpha = if (AvatarFlight.inAir && AvatarFlight.userId == user) 0f else 1f }

/** Marks the landing spot for [user]; it fades in as the copy hands over. */
fun Modifier.flightTarget(user: Long): Modifier =
    onGloballyPositioned { if (AvatarFlight.userId == user && AvatarFlight.inAir) AvatarFlight.target = it }
        .graphicsLayer { alpha = if (AvatarFlight.inAir && AvatarFlight.userId == user) AvatarFlight.handoff.value else 1f }

/** Draws the flying copy; put it last in the root so it's above everything. */
@Composable
fun AvatarFlightOverlay(vm: AppViewModel) {
    val uid = AvatarFlight.userId ?: return
    val user = vm.users[uid] ?: vm.me?.takeIf { it.id == uid } ?: return
    val from = AvatarFlight.from ?: return
    val target = AvatarFlight.target
    // where the landing spot is right now (follows sliding / scaling parents)
    var to by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(target) {
        if (target == null) return@LaunchedEffect
        launch {
            while (AvatarFlight.inAir) {
                withFrameNanos { }
                if (target.isAttached) to = target.boundsInWindow()
            }
        }
        AvatarFlight.handoff.snapTo(0f)
        AvatarFlight.progress.snapTo(0f)
        // critically damped: glides in and settles exactly, no overshoot to correct
        AvatarFlight.progress.animateTo(1f, spring(dampingRatio = 1f, stiffness = 170f, visibilityThreshold = 0.0005f))
        // then a short cross-fade so the real picture takes over without a pop
        AvatarFlight.handoff.animateTo(1f, androidx.compose.animation.core.tween(220, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        AvatarFlight.land()
    }
    // nothing to land on yet (or it never appears): give up quietly after a moment
    LaunchedEffect(uid) { kotlinx.coroutines.delay(1500); if (AvatarFlight.target == null) AvatarFlight.land() }
    // until the landing spot is known, hold the copy where the original was (no blink)
    val dest = to ?: from
    val p = if (to == null) 0f else AvatarFlight.progress.value
    val density = LocalDensity.current
    // a curved path (quadratic Bézier), bowing upward between the two spots
    val c0 = androidx.compose.ui.geometry.Offset(from.center.x, from.center.y)
    val c2 = androidx.compose.ui.geometry.Offset(dest.center.x, dest.center.y)
    val c1 = androidx.compose.ui.geometry.Offset((c0.x + c2.x) / 2f, minOf(c0.y, c2.y) - kotlin.math.abs(c2.x - c0.x) * 0.25f)
    val q = 1f - p
    val cx = q * q * c0.x + 2 * q * p * c1.x + p * p * c2.x
    val cy = q * q * c0.y + 2 * q * p * c1.y + p * p * c2.y
    val size = from.width + (dest.width - from.width) * p
    // draw at the destination size and scale the layer (smooth sub-pixel motion, no re-layout per frame)
    val full = with(density) { dest.width.toDp() }
    Box(
        Modifier.offset { IntOffset((cx - dest.width / 2f).roundToInt(), (cy - dest.height / 2f).roundToInt()) }
            .size(full)
            .graphicsLayer {
                val k = if (dest.width > 0f) size / dest.width else 1f
                scaleX = k; scaleY = k
                translationX = (cx - dest.width / 2f) - (cx - dest.width / 2f).roundToInt()
                translationY = (cy - dest.height / 2f) - (cy - dest.height / 2f).roundToInt()
                alpha = 1f - AvatarFlight.handoff.value
                shadowElevation = 22f * (1f - kotlin.math.abs(p - 0.5f) * 2f)
                shape = CircleShape
            },
    ) {
        Avatar(user.displayName, user.hue, full, Modifier.size(full), userId = user.id, shape = CircleShape)
    }
}
