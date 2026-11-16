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

/** Marks the landing spot for [user]; it stays hidden until the copy arrives. */
fun Modifier.flightTarget(user: Long): Modifier =
    onGloballyPositioned { if (AvatarFlight.userId == user && AvatarFlight.inAir) AvatarFlight.target = it }
        .graphicsLayer { alpha = if (AvatarFlight.inAir && AvatarFlight.userId == user) 0f else 1f }

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
        AvatarFlight.progress.snapTo(0f)
        AvatarFlight.progress.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 230f))
        AvatarFlight.land()
    }
    // nothing to land on yet (or it never appears): give up quietly after a moment
    LaunchedEffect(uid) { kotlinx.coroutines.delay(1500); if (AvatarFlight.target == null) AvatarFlight.land() }
    val dest = to ?: return
    val p = AvatarFlight.progress.value
    val density = LocalDensity.current
    // a gentle arc: sideways motion trails the vertical a little
    val px = p * p * (3 - 2 * p)
    val left = from.left + (dest.left - from.left) * px
    val top = from.top + (dest.top - from.top) * p
    val size = from.width + (dest.width - from.width) * p
    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .graphicsLayer { shadowElevation = 22f * (1f - kotlin.math.abs(p - 0.5f) * 2f); shape = CircleShape },
    ) {
        Avatar(user.displayName, user.hue, with(density) { size.toDp() }, Modifier.size(with(density) { size.toDp() }), userId = user.id, shape = CircleShape)
    }
}
