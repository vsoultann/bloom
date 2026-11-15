package app.bloom.ui.screens

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.bloom.AppViewModel
import app.bloom.core.Channel
import app.bloom.ui.components.*
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion
import app.bloom.voice.VoiceMember
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * The voice stage pinned above a voice channel's text chat: participant tiles with
 * a talking ring, camera/screen video, and the call controls.
 */
@Composable
fun VoiceStage(vm: AppViewModel, channel: Channel, modifier: Modifier = Modifier) {
    val v = vm.voice
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val inThis = v.channelId == channel.id
    val title = "#${channel.name}"
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) v.join(channel.id, title) }
    val askCam = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) v.toggleCamera(title) }
    val askScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) v.startScreen(r.data!!, title)
    }
    fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    Column(
        modifier.fillMaxWidth().height(236.dp).padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(LocalLook.current.cardRadius)).background(scheme.surfaceContainerHigh).padding(10.dp),
    ) {
        // participants (or who's here if you haven't joined)
        val people: List<VoiceMember> = if (inThis) v.members.values.sortedBy { it.userId != vm.me?.id } else emptyList()
        if (!inThis) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.GraphicEq, null, Modifier.size(40.dp), tint = scheme.primary)
                    Text(channel.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (v.channelId != null) "You're in another voice channel" else "Voice channel", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        } else {
            LazyRow(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                // video tiles first (screen shares, then cameras)
                val videos: List<Pair<String, VideoTrack>> = buildList {
                    val local: VideoTrack? = v.localVideo
                    if (local != null) add("You" to local)
                    for (m in people) for (id in listOfNotNull(m.screenTrack, m.videoTrack)) {
                        val t: VideoTrack = v.videoTracks[id] ?: continue
                        add((vm.users[m.userId]?.displayName ?: "") to t)
                    }
                }
                items(videos, key = { it.second.id() }) { (name, track) -> VideoTile(v.egl, track, name) }
                items(people, key = { it.userId }) { m -> MemberTile(vm, m) }
            }
        }
        v.error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.labelMedium, maxLines = 2) }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (!inThis) {
                Button(
                    onClick = { if (has(android.Manifest.permission.RECORD_AUDIO)) v.join(channel.id, title) else askMic.launch(android.Manifest.permission.RECORD_AUDIO) },
                    enabled = !v.connecting,
                ) {
                    if (v.connecting) MorphingLoader(size = 20.dp, color = scheme.onPrimary) else { Icon(Icons.Rounded.Call, null); Spacer(Modifier.width(6.dp)); Text("Join voice") }
                }
            } else {
                CallButton(if (v.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, "Mute", v.muted) { v.toggleMute() }
                CallButton(if (v.deafened) Icons.Rounded.HeadsetOff else Icons.Rounded.Headset, "Deafen", v.deafened) { v.toggleDeafen() }
                CallButton(if (v.cameraOn) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff, "Camera", v.cameraOn) {
                    if (v.cameraOn || has(android.Manifest.permission.CAMERA)) v.toggleCamera(title) else askCam.launch(android.Manifest.permission.CAMERA)
                }
                CallButton(if (v.screenOn) Icons.Rounded.StopScreenShare else Icons.Rounded.ScreenShare, "Share screen", v.screenOn) {
                    if (v.screenOn) v.stopScreen()
                    else askScreen.launch((ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent())
                }
                FilledIconButton(onClick = { v.leave() }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = scheme.error)) {
                    Icon(Icons.Rounded.CallEnd, "Leave", tint = scheme.onError)
                }
            }
        }
    }
}

@Composable
private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = if (active) scheme.primary else scheme.surfaceContainerHighest),
    ) { Icon(icon, label, tint = if (active) scheme.onPrimary else scheme.onSurface) }
}

/** Avatar with a ring that glows and grows while the person is talking. */
@Composable
private fun MemberTile(vm: AppViewModel, m: VoiceMember) {
    val scheme = MaterialTheme.colorScheme
    val u = vm.users[m.userId]
    val talking = m.level > 0.02f && !m.muted
    val ring by animateFloatAsState(if (talking) (0.5f + m.level.coerceAtMost(0.5f)) else 0f, Motion.spatialFast(), label = "talk")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(78.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(64.dp).graphicsLayer { scaleX = 1f + 0.12f * ring; scaleY = scaleX }
                    .softGlow(CircleShape, Color(0xFF23A55A), 12.dp, 0.7f * ring)
                    .border((3 * ring).dp.coerceAtLeast(0.dp), Color(0xFF23A55A), CircleShape),
            )
            Avatar(u?.displayName ?: "?", u?.hue ?: 0, 56.dp, userId = m.userId)
            if (m.muted || m.deafened) Icon(
                if (m.deafened) Icons.Rounded.HeadsetOff else Icons.Rounded.MicOff, null,
                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(CircleShape).background(scheme.error).padding(3.dp), tint = scheme.onError,
            )
        }
        Text(u?.displayName ?: "…", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Renders a WebRTC video track (camera or screen share). */
@Composable
private fun VideoTile(egl: org.webrtc.EglBase, track: VideoTrack, name: String) {
    Box(Modifier.width(220.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
        AndroidView(
            factory = { c -> SurfaceViewRenderer(c).apply { init(egl.eglBaseContext, null); setEnableHardwareScaler(true); track.addSink(this) } },
            onRelease = { r -> track.removeSink(r); r.release() },
            modifier = Modifier.fillMaxSize(),
        )
        Text(name, color = Color.White, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
