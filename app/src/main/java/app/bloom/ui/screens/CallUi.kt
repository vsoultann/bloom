package app.bloom.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.bloom.AppViewModel
import app.bloom.core.Channel
import app.bloom.ui.components.Avatar
import app.bloom.ui.components.rememberLivingShape
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion
import app.bloom.voice.VoiceMember
import kotlinx.coroutines.delay
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

private val Talking = Color(0xFF2FD47A)

/**
 * The call, pinned above the chat: a live status line (connecting / ringing with ripples /
 * timer), everyone as a living shape that swells with their voice, and a frosted dock of
 * morphing controls. Tap the header to tuck it into a slim bar.
 */
@Composable
fun CallStage(vm: AppViewModel, channel: Channel, isDm: Boolean, modifier: Modifier = Modifier) {
    val v = vm.voice
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val inThis = v.channelId == channel.id
    val dm = if (isDm) vm.dms.firstOrNull { it.id == channel.id } else null
    val title = dm?.let { vm.dmName(it) } ?: "#${channel.name}"
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) v.join(channel.id, title) }
    val askCam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) v.toggleCamera(title) }
    val askScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) v.startScreen(r.data!!, title)
    }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    var small by rememberSaveable(channel.id) { mutableStateOf(false) }

    val others = v.members.keys.count { it != vm.me?.id }
    val phase = when {
        !inThis && v.connecting -> "Connecting…"
        !inThis -> if (isDm) "Call" else "Voice channel"
        isDm && others == 0 -> "Ringing…"
        others == 0 -> "Just you here"
        else -> "live"
    }
    // ticking timer once someone else is here
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(v.connectedAt) { while (v.connectedAt != null) { now = System.currentTimeMillis(); delay(500) } }
    val elapsed = v.connectedAt?.let { (now - it) / 1000 }

    Column(
        modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(LocalLook.current.cardRadius + 4.dp))
            .background(Brush.verticalGradient(listOf(scheme.primaryContainer.copy(alpha = 0.55f), scheme.surfaceContainerHigh)))
            .animateContentSize(spring(dampingRatio = 1f, stiffness = 420f)),
    ) {
        // header: title + live status; tap to tuck away
        Row(
            Modifier.fillMaxWidth().clickable { small = !small }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDot(inThis && others > 0)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                AnimatedContent(if (elapsed != null) "%d:%02d".format(elapsed / 60, elapsed % 60) else phase,
                    transitionSpec = { (fadeIn() + slideInVertically { it / 2 }) togetherWith (fadeOut() + slideOutVertically { -it / 2 }) }, label = "status") { s ->
                    Text(s, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            if (small && inThis) {
                // the essentials stay reachable when tucked away
                DockButton(if (v.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Mute", v.muted, size = 40.dp) { v.toggleMute() }
                Spacer(Modifier.width(6.dp))
                HangUp(size = 40.dp) { v.leave() }
                Spacer(Modifier.width(6.dp))
            }
            Icon(if (small) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, if (small) "Expand call" else "Tuck call away", tint = scheme.onSurfaceVariant)
        }
        if (!small) {
            // the stage
            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                val people: List<VoiceMember> = if (inThis) v.members.values.sortedBy { it.userId != vm.me?.id } else emptyList()
                when {
                    // ringing someone: their picture with ripples
                    inThis && isDm && others == 0 -> {
                        val callee = dm?.userIds?.firstOrNull()?.let { vm.users[it] }
                        Ripples(scheme.primary) { if (callee != null) Avatar(callee.displayName, callee.hue, 84.dp, userId = callee.id) }
                    }
                    inThis -> {
                        val videos: List<Pair<String, VideoTrack>> = buildList {
                            v.localVideo?.let { add("You" to it) }
                            for (m in people) for (id in listOfNotNull(m.screenTrack, m.videoTrack)) {
                                val t = v.videoTracks[id] ?: continue
                                add((vm.users[m.userId]?.displayName ?: "") to t)
                            }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically, contentPadding = PaddingValues(horizontal = 16.dp)) {
                            items(videos, key = { it.second.id() }) { (name, track) -> VideoTile(v.egl, track, name) }
                            items(people, key = { it.userId }) { m -> LivingMember(vm, m) }
                        }
                    }
                    else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (isDm) Icons.Rounded.Call else Icons.Rounded.GraphicEq, null, Modifier.size(40.dp), tint = scheme.primary)
                        Spacer(Modifier.height(6.dp))
                        Text(if (v.channelId != null && !inThis) "You're in another call" else if (isDm) "Not in a call" else "No one's talking yet", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
            // the dock
            Row(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(50)).background(scheme.surfaceContainerHighest.copy(alpha = 0.7f)).padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!inThis) {
                    Button(
                        onClick = { if (has(android.Manifest.permission.RECORD_AUDIO)) v.join(channel.id, title) else askMic.launch(android.Manifest.permission.RECORD_AUDIO) },
                        enabled = !v.connecting, modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Talking, contentColor = Color.Black),
                    ) {
                        if (v.connecting) app.bloom.ui.components.MorphingLoader(size = 22.dp, color = Color.Black)
                        else { Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(8.dp)); Text(if (isDm) "Start call" else "Join voice", fontWeight = FontWeight.Bold) }
                    }
                } else {
                    DockButton(if (v.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Mute", v.muted) { v.toggleMute() }
                    DockButton(if (v.deafened) Icons.Rounded.HeadsetOff else Icons.Rounded.Headset, "Deafen", v.deafened) { v.toggleDeafen() }
                    DockButton(if (v.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, "Camera", v.cameraOn) {
                        if (v.cameraOn || has(android.Manifest.permission.CAMERA)) v.toggleCamera(title) else askCam.launch(android.Manifest.permission.CAMERA)
                    }
                    DockButton(if (v.screenOn) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare, "Share screen", v.screenOn) {
                        if (v.screenOn) v.stopScreen()
                        else askScreen.launch((ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
                    }
                    HangUp { v.leave() }
                }
            }
        }
    }
}

/** A dot that breathes while the call is live. */
@Composable
private fun LiveDot(live: Boolean) {
    val t = rememberInfiniteTransition(label = "live")
    val k by t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "k")
    Box(Modifier.size(10.dp).graphicsLayer { if (live) { scaleX = k; scaleY = k } }.clip(CircleShape).background(if (live) Talking else MaterialTheme.colorScheme.outline))
}

/** Concentric rings spreading out from the middle (an outgoing ring). */
@Composable
private fun Ripples(color: Color, center: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "ripple")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "p")
    Box(
        Modifier.size(150.dp).drawBehind {
            for (i in 0 until 3) {
                val q = (p + i / 3f) % 1f
                drawCircle(color.copy(alpha = (1f - q) * 0.45f), radius = size.minDimension / 2 * (0.45f + 0.55f * q), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            }
        },
        contentAlignment = Alignment.Center,
    ) { center() }
}

/** A participant: their picture inside a living shape that swells, glows and turns with their voice. */
@Composable
private fun LivingMember(vm: AppViewModel, m: VoiceMember) {
    val scheme = MaterialTheme.colorScheme
    val u = vm.users[m.userId]
    val talking = m.level > 0.02f && !m.muted
    val level by animateFloatAsState(if (talking) m.level.coerceIn(0f, 1f) else 0f, spring(dampingRatio = 0.7f, stiffness = 500f), label = "lvl")
    val living = rememberLivingShape(seed = (m.userId % 997).toInt(), selected = !talking)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(96.dp)) {
            // glow behind, sized by how loud they are
            Box(Modifier.size(80.dp).graphicsLayer { val k = 1f + 0.22f * level; scaleX = k; scaleY = k; alpha = (level * 2.2f).coerceAtMost(0.9f); shape = living.shape(); clip = true }.background(Talking))
            Box(Modifier.size(72.dp).graphicsLayer { val k = 1f + 0.08f * level; scaleX = k; scaleY = k; shape = living.shape(); clip = true }) {
                if (u != null) Avatar(u.displayName, u.hue, 72.dp, userId = u.id, shape = androidx.compose.ui.graphics.RectangleShape)
            }
            if (m.muted || m.deafened) Icon(
                if (m.deafened) Icons.Rounded.HeadsetOff else Icons.Rounded.MicOff, null,
                Modifier.align(Alignment.BottomEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(scheme.error).padding(3.dp), tint = scheme.onError,
            )
        }
        Text(if (m.userId == vm.me?.id) "You" else u?.displayName ?: "…", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (talking) Talking else scheme.onSurface)
    }
}

/** Dock button: circle when off, rounded square in the accent colour when on, squishes when pressed. */
@Composable
private fun DockButton(icon: ImageVector, label: String, active: Boolean, size: androidx.compose.ui.unit.Dp = 52.dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val corner by animateDpAsState(if (active) size * 0.3f else size / 2, spring(dampingRatio = 0.6f, stiffness = 500f), label = "corner")
    val bg by animateColorAsState(if (active) scheme.primary else scheme.surfaceContainerHigh, Motion.effects(), label = "bg")
    val fg by animateColorAsState(if (active) scheme.onPrimary else scheme.onSurface, Motion.effects(), label = "fg")
    val k by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.5f, stiffness = 700f), label = "press")
    Box(
        Modifier.size(size).graphicsLayer { scaleX = k; scaleY = k }.clip(RoundedCornerShape(corner)).background(bg)
            .clickable(src, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = fg) }
}

@Composable
private fun HangUp(size: androidx.compose.ui.unit.Dp = 52.dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val turn by animateFloatAsState(if (pressed) 135f else 0f, spring(dampingRatio = 0.55f, stiffness = 500f), label = "turn")
    Box(
        Modifier.height(size).width(size * 1.5f).clip(RoundedCornerShape(50)).background(scheme.error).clickable(src, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.CallEnd, "Hang up", Modifier.graphicsLayer { rotationZ = turn }, tint = scheme.onError) }
}

/** Camera or screen share. */
@Composable
private fun VideoTile(egl: org.webrtc.EglBase, track: VideoTrack, name: String) {
    Box(Modifier.width(200.dp).height(130.dp).clip(RoundedCornerShape(18.dp)).background(Color.Black)) {
        AndroidView(
            factory = { c -> SurfaceViewRenderer(c).apply { init(egl.eglBaseContext, null); setEnableHardwareScaler(true); track.addSink(this) } },
            onRelease = { r -> track.removeSink(r); r.release() },
            modifier = Modifier.fillMaxSize(),
        )
        Text(name, color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/**
 * Incoming call: slides down from the top over everything, with the caller in ripples,
 * the phone's ringtone and vibration, Accept / Decline; gives up after 35 s.
 */
@Composable
fun IncomingCallOverlay(vm: AppViewModel) {
    val call = vm.incomingCall
    val ctx = LocalContext.current
    // ring + vibrate while it's showing
    DisposableEffect(call?.channelId) {
        if (call == null) return@DisposableEffect onDispose { }
        val tone = runCatching {
            android.media.RingtoneManager.getRingtone(ctx, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE))?.apply { isLooping = true; play() }
        }.getOrNull()
        val vib = ctx.getSystemService(android.os.Vibrator::class.java)
        runCatching { vib?.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 600, 900), 0)) }
        onDispose { runCatching { tone?.stop() }; runCatching { vib?.cancel() } }
    }
    LaunchedEffect(call?.at) { if (call != null) { delay(35_000); if (vm.incomingCall?.at == call.at) vm.incomingCall = null } }
    AnimatedVisibility(
        call != null,
        enter = slideInVertically(spring(dampingRatio = 0.8f, stiffness = 380f)) { -it } + fadeIn(),
        exit = slideOutVertically(spring(dampingRatio = 1f, stiffness = 500f)) { -it } + fadeOut(),
    ) {
        val c = call ?: vm.incomingCall ?: return@AnimatedVisibility
        val caller = vm.users[c.callerId]
        val dm = vm.dms.firstOrNull { it.id == c.channelId }
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.statusBarsPadding().padding(10.dp).fillMaxWidth().clip(RoundedCornerShape(32.dp))
                .background(Brush.horizontalGradient(listOf(scheme.primaryContainer, scheme.surfaceContainerHighest)))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.requiredSize(110.dp)) { Ripples(Talking) { } }
                if (caller != null) Avatar(caller.displayName, caller.hue, 52.dp, userId = caller.id)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(caller?.displayName ?: "Someone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if ((dm?.userIds?.size ?: 1) > 1) "Group call · ${vm.dmName(dm!!)}" else "is calling you", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            Box(Modifier.size(52.dp).clip(CircleShape).background(scheme.error).clickable { vm.declineCall() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CallEnd, "Decline", tint = scheme.onError)
            }
            Spacer(Modifier.width(10.dp))
            val t = rememberInfiniteTransition(label = "accept")
            val wob by t.animateFloat(-12f, 12f, infiniteRepeatable(tween(260), RepeatMode.Reverse), label = "w")
            Box(Modifier.size(52.dp).clip(CircleShape).background(Talking).clickable { vm.acceptCall() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Call, "Accept", Modifier.graphicsLayer { rotationZ = wob }, tint = Color.Black)
            }
        }
    }
}
