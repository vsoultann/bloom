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
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import app.bloom.AppViewModel
import app.bloom.ui.components.Avatar
import app.bloom.ui.theme.Ui
import kotlin.math.roundToInt

/**
 * Shared-element "flight" for profile pictures: tapping one somewhere (rail, chat, top
 * bar) flies a copy to where it lands (the You card, the profile sheet), moving and
 * scaling along a fluid spring, while the real ones hide until it lands.
 */
object AvatarFlight {
    var userId by mutableStateOf<Long?>(null); private set
    /** where the tap happened / where it should land, in window coordinates */
    var from by mutableStateOf<Rect?>(null); private set
    var to by mutableStateOf<Rect?>(null)
    val progress = Animatable(1f)
    /** true while a copy is in the air (the destination hides its own picture) */
    val flying get() = userId != null && progress.value < 1f

    fun launch(user: Long, source: Rect?) {
        if (source == null || !Ui.animations) { userId = null; return }
        userId = user; from = source; to = null
    }

    fun land() { userId = null; from = null; to = null }
}

/** Remembers where this picture is so a flight can start from it. */
fun Modifier.flightSource(onBounds: (Rect) -> Unit): Modifier = onGloballyPositioned { onBounds(it.boundsInWindow()) }

/** Marks a landing spot for [user]; it stays invisible while the copy is flying in. */
@Composable
fun Modifier.flightTarget(user: Long): Modifier {
    val active = AvatarFlight.userId == user
    return this
        .onGloballyPositioned { if (active && AvatarFlight.to == null) AvatarFlight.to = it.boundsInWindow() }
        .graphicsLayer { alpha = if (active && AvatarFlight.progress.value < 1f) 0f else 1f }
}

/** Draws the flying copy; put it last in the root Box so it's on top of everything. */
@Composable
fun AvatarFlightOverlay(vm: AppViewModel) {
    val user = AvatarFlight.userId?.let { vm.users[it] ?: vm.me?.takeIf { m -> m.id == it } } ?: return
    val from = AvatarFlight.from ?: return
    val to = AvatarFlight.to
    LaunchedEffect(user.id, to) {
        if (to == null) return@LaunchedEffect
        AvatarFlight.progress.snapTo(0f)
        // a soft, slightly lively spring: moves fast, settles without wobbling about
        AvatarFlight.progress.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 260f))
        AvatarFlight.land()
    }
    if (to == null) return
    val p = AvatarFlight.progress.value
    val density = LocalDensity.current
    // the arc: x eases in a little later than y, so the path curves instead of a straight line
    val px = (p * p * (3 - 2 * p))
    val left = from.left + (to.left - from.left) * px
    val top = from.top + (to.top - from.top) * p
    val size = from.width + (to.width - from.width) * p
    Box(
        Modifier.offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .graphicsLayer { shadowElevation = 18f * (1f - kotlin.math.abs(p - 0.5f) * 2f); shape = CircleShape; clip = false },
    ) {
        Avatar(user.displayName, user.hue, with(density) { size.toDp() }, Modifier.size(with(density) { size.toDp() }), userId = user.id, shape = CircleShape)
    }
}
