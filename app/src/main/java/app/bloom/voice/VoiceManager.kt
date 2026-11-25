package app.bloom.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.bloom.core.Core
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** One participant's state in the call. */
data class VoiceMember(
    val userId: Long,
    val muted: Boolean = false,
    val deafened: Boolean = false,
    val audioTrack: String? = null,
    val videoTrack: String? = null,
    val screenTrack: String? = null,
    /** 0..1 speaking level (talking indicator). */
    val level: Float = 0f,
) {
    companion object {
        fun from(o: JSONObject) = VoiceMember(
            o.getLong("user_id"), o.optBoolean("muted"), o.optBoolean("deafened"),
            o.optString("audio_track").takeIf { it.isNotEmpty() && it != "null" },
            o.optString("video_track").takeIf { it.isNotEmpty() && it != "null" },
            o.optString("screen_track").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}

/**
 * Runs a voice/video call against Root's SFU.
 *  join: ICE → offer → SessionCreate → answer → publish mic (TracksCreate local)
 *  remote tracks: TracksCreate(remote) → server offer → answer → Renegotiate
 * State is exposed as Compose state for the UI.
 */
class VoiceManager(private val app: Context) {
    var channelId by mutableStateOf<Long?>(null); private set
    var connecting by mutableStateOf(false); private set
    var muted by mutableStateOf(false); private set
    var deafened by mutableStateOf(false); private set
    var cameraOn by mutableStateOf(false); private set
    var screenOn by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    /** when someone else first joined (call timer starts here); null while ringing / alone */
    var connectedAt by mutableStateOf<Long?>(null); private set
    val members = mutableStateMapOf<Long, VoiceMember>()
    /** trackId → video track, for rendering tiles. */
    val videoTracks = mutableStateMapOf<String, VideoTrack>()
    var localVideo by mutableStateOf<VideoTrack?>(null); private set
    var myId: Long = 0
    var myLevel by mutableStateOf(0f); private set

    /** Per-person playback volume (0 = silent, 1 = normal, up to 2 = boosted); remembered across calls. */
    val userVolume = mutableStateMapOf<Long, Float>().apply {
        app.getSharedPreferences("voice", Context.MODE_PRIVATE).all.forEach { (k, v) -> k.removePrefix("vol_").toLongOrNull()?.let { id -> (v as? Float)?.let { put(id, it) } } }
    }
    fun setUserVolume(user: Long, v: Float) {
        val vol = v.coerceIn(0f, 2f)
        if (vol == 1f) userVolume.remove(user) else userVolume[user] = vol
        app.getSharedPreferences("voice", Context.MODE_PRIVATE).edit().apply { if (vol == 1f) remove("vol_$user") else putFloat("vol_$user", vol) }.apply()
        applyVolumes()
    }
    /** WebRTC's AudioTrack volume is a gain (0..10); 1 = as received. */
    private fun applyVolumes() {
        pc?.transceivers?.forEach { t ->
            val track = t.receiver.track() as? AudioTrack ?: return@forEach
            val user = t.mid?.let { midToTrack[it] }?.let { trackToUser[it] } ?: return@forEach
            runCatching { track.setVolume((userVolume[user] ?: 1f).toDouble()) }
        }
    }

    val egl: EglBase by lazy { EglBase.create() }
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var camCapturer: CameraVideoCapturer? = null
    private var screenCapturer: VideoCapturer? = null
    private var surfaceHelpers = mutableListOf<SurfaceTextureHelper>()
    private val subscribed = mutableSetOf<String>()
    private val midToTrack = mutableMapOf<String, String>()
    private val trackToUser = mutableMapOf<String, Long>()
    private val sig = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var statsJob: Job? = null

    private fun ensureFactory(): PeerConnectionFactory = factory ?: run {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        val adm = org.webrtc.audio.JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory().also { factory = it }
    }

    /** Set when a call needs the microphone permission first; MainActivity asks, then calls [retryJoin]. */
    var needsMic by mutableStateOf<Pair<Long, String>?>(null)
    fun retryJoin() { needsMic?.let { (c, t) -> needsMic = null; join(c, t) } }

    /** Step-by-step call trace in Settings → Logs & crashes (so a failed call says where). */
    private fun trace(step: String) { scope.launch { runCatching { Core.log("call", step) } }; android.util.Log.i("BloomCall", step) }

    fun join(channel: Long, title: String) {
        trace("join requested ($channel) · in=$channelId connecting=$connecting")
        if (channelId == channel || connecting) return
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needsMic = channel to title
            return
        }
        if (channelId != null) leave()
        connecting = true
        error = null
        scope.launch {
            runCatching {
                VoiceService.start(app, title)
                val ice = Core.voice("ice") as JSONObject
                trace("ice servers: ${ice.optJSONArray("urls")?.length() ?: 0}")
                val urls = ice.getJSONArray("urls").let { a -> List(a.length()) { a.getString(it) } }
                val servers = listOf(PeerConnection.IceServer.builder(urls).setUsername(ice.optString("username")).setPassword(ice.optString("credential")).createIceServer())
                val cfg = PeerConnection.RTCConfiguration(servers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                }
                val f = ensureFactory()
                val conn = f.createPeerConnection(cfg, observer) ?: error("Couldn't create the call connection")
                pc = conn
                audioSource = f.createAudioSource(MediaConstraints())
                val local = f.createAudioTrack("mic-${System.nanoTime()}", audioSource)
                audioTrack = local
                val tx = conn.addTransceiver(local, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
                speakerOn(true)

                // 1) session
                val offer = conn.offer()
                conn.setLocal(offer)
                trace("offer ready (${offer.description.length} chars), creating session")
                val res = Core.voice("join", JSONObject().put("channel", channel).put("sdp", offer.description).put("sdp_type", "offer")) as JSONObject
                trace("session: answer=${res.optString("sdp").length} chars, id=${res.optString("session_id").take(8)}…, members=${res.optJSONArray("members")?.length() ?: 0}")
                res.optString("sdp").takeIf { it.isNotEmpty() && it != "null" }?.let { conn.setRemote(SessionDescription(SessionDescription.Type.ANSWER, it)) }
                channelId = channel
                members.clear()
                res.optJSONArray("members")?.let { a -> repeat(a.length()) { i -> VoiceMember.from(a.getJSONObject(i)).let { members[it.userId] = it } } }
                members[myId] = VoiceMember(myId)
                if (members.keys.any { it != myId }) connectedAt = System.currentTimeMillis()

                // 2) publish the mic
                publish(channel, tx.mid ?: "0", local.id(), "audio")
                trace("mic published")
                // 3) subscribe to everyone already here
                subscribeAll()
                statsJob = scope.launch { pollLevels() }
                trace("joined · subscribed to ${subscribed.size} tracks")
            }.onFailure { e ->
                trace("FAILED: ${e::class.simpleName}: ${e.message}")
                error = e.message ?: "Couldn't join voice"
                cleanup()
            }
            connecting = false
        }
    }

    /** Publishes a local transceiver as a track. */
    private suspend fun publish(channel: Long, mid: String, trackId: String, kind: String) = sig.withLock {
        val conn = pc ?: return@withLock
        val offer = conn.offer()
        conn.setLocal(offer)
        val r = Core.voice("tracks", JSONObject().put("channel", channel).put("sdp", offer.description).put("sdp_type", "offer")
            .put("tracks", JSONArray().put(JSONObject().put("location", "local").put("mid", mid).put("track_id", trackId).put("kind", kind)))) as JSONObject
        r.optString("sdp").takeIf { it.isNotEmpty() && it != "null" }?.let { conn.setRemote(SessionDescription(SessionDescription.Type.ANSWER, it)) }
    }

    /** Subscribes to any participant tracks we don't have yet. */
    private suspend fun subscribeAll() = sig.withLock {
        val conn = pc ?: return@withLock
        val channel = channelId ?: return@withLock
        val want = members.values.filter { it.userId != myId }.flatMap { m ->
            listOfNotNull(m.audioTrack?.let { Triple(it, "audio", m.userId) }, m.videoTrack?.let { Triple(it, "video", m.userId) }, m.screenTrack?.let { Triple(it, "screen", m.userId) })
        }.filter { it.first !in subscribed }
        if (want.isEmpty()) return@withLock
        val tracks = JSONArray()
        want.forEach { (id, kind, user) -> tracks.put(JSONObject().put("location", "remote").put("track_id", id).put("kind", kind)); trackToUser[id] = user }
        val r = Core.voice("tracks", JSONObject().put("channel", channel).put("tracks", tracks)) as JSONObject
        trace("subscribe ${want.size} → ${r.optJSONArray("tracks")?.length() ?: 0} tracks, offer=${r.optString("sdp").length} chars")
        r.optJSONArray("tracks")?.let { a -> repeat(a.length()) { i -> val t = a.getJSONObject(i); if (t.optString("error").let { it.isEmpty() || it == "null" }) { midToTrack[t.getString("mid")] = t.getString("track_id"); subscribed += t.getString("track_id") } } }
        // the SFU answers with an offer containing the new remote tracks
        val sdp = r.optString("sdp").takeIf { it.isNotEmpty() && it != "null" } ?: return@withLock
        conn.setRemote(SessionDescription(SessionDescription.Type.OFFER, sdp))
        val answer = conn.answer()
        conn.setLocal(answer)
        Core.voice("renegotiate", JSONObject().put("channel", channel).put("sdp", answer.description).put("sdp_type", "answer"))
    }

    /** Live participant update from the hub. */
    fun onMember(channel: Long, o: JSONObject) {
        if (channel == channelId && o.optLong("user_id") != myId && connectedAt == null) connectedAt = System.currentTimeMillis()
        if (channel != channelId) return
        val m = VoiceMember.from(o)
        members[m.userId] = m.copy(level = members[m.userId]?.level ?: 0f)
        scope.launch { runCatching { subscribeAll() } }
    }

    fun onLeft(channel: Long, user: Long) { if (channel == channelId) members.remove(user) }

    fun toggleMute() {
        val ch = channelId ?: return
        muted = !muted
        audioTrack?.setEnabled(!muted)
        members[myId]?.let { members[myId] = it.copy(muted = muted) }
        scope.launch { runCatching { Core.voice("mute", JSONObject().put("channel", ch).put("muted", muted)) } }
    }

    fun toggleDeafen() {
        val ch = channelId ?: return
        deafened = !deafened
        // deafening also mutes, like everywhere else
        if (deafened && !muted) { muted = true; audioTrack?.setEnabled(false) }
        pc?.receivers?.forEach { (it.track() as? AudioTrack)?.setEnabled(!deafened) }
        members[myId]?.let { members[myId] = it.copy(muted = muted, deafened = deafened) }
        scope.launch { runCatching { Core.voice("mute", JSONObject().put("channel", ch).put("muted", muted).put("deafened", deafened)) } }
    }

    fun toggleCamera(title: String) {
        val ch = channelId ?: return
        if (cameraOn) {
            runCatching { camCapturer?.stopCapture() }
            camCapturer?.dispose(); camCapturer = null
            localVideo?.setEnabled(false); localVideo = null
            cameraOn = false
            return
        }
        scope.launch {
            runCatching {
                VoiceService.start(app, title, camera = true, screen = screenOn)
                val f = ensureFactory()
                val en = Camera2Enumerator(app)
                val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.first()
                val cap = en.createCapturer(name, null)
                val helper = SurfaceTextureHelper.create("cam", egl.eglBaseContext).also { surfaceHelpers += it }
                val src = f.createVideoSource(false)
                cap.initialize(helper, app, src.capturerObserver)
                cap.startCapture(1280, 720, 30)
                camCapturer = cap
                val track = f.createVideoTrack("cam-${System.nanoTime()}", src)
                localVideo = track
                val tx = pc!!.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
                cameraOn = true
                publish(ch, tx.mid ?: "1", track.id(), "video")
            }.onFailure { error = "Camera: ${it.message}"; cameraOn = false }
        }
    }

    /** Start screen sharing with the MediaProjection permission result. */
    fun startScreen(data: Intent, title: String) {
        val ch = channelId ?: return
        scope.launch {
            runCatching {
                VoiceService.start(app, title, camera = cameraOn, screen = true)
                delay(300) // the service must be in the foreground before projection starts
                val f = ensureFactory()
                val cap = ScreenCapturerAndroid(data, object : android.media.projection.MediaProjection.Callback() {
                    override fun onStop() { screenOn = false }
                })
                val helper = SurfaceTextureHelper.create("screen", egl.eglBaseContext).also { surfaceHelpers += it }
                val src = f.createVideoSource(true)
                cap.initialize(helper, app, src.capturerObserver)
                val dm = app.resources.displayMetrics
                cap.startCapture(dm.widthPixels / 2, dm.heightPixels / 2, 15)
                screenCapturer = cap
                val track = f.createVideoTrack("screen-${System.nanoTime()}", src)
                val tx = pc!!.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY))
                screenOn = true
                publish(ch, tx.mid ?: "2", track.id(), "screen")
            }.onFailure { error = "Screen share: ${it.message}"; screenOn = false }
        }
    }

    fun stopScreen() {
        runCatching { screenCapturer?.stopCapture() }
        screenCapturer?.dispose(); screenCapturer = null
        screenOn = false
    }

    fun leave() {
        val ch = channelId
        if (ch != null) scope.launch { runCatching { Core.voice("leave", JSONObject().put("channel", ch)) } }
        cleanup()
    }

    private fun cleanup() {
        connectedAt = null
        statsJob?.cancel()
        runCatching { camCapturer?.stopCapture() }; camCapturer?.dispose(); camCapturer = null
        runCatching { screenCapturer?.stopCapture() }; screenCapturer?.dispose(); screenCapturer = null
        surfaceHelpers.forEach { it.dispose() }; surfaceHelpers.clear()
        pc?.close(); pc?.dispose(); pc = null
        audioTrack?.dispose(); audioTrack = null
        audioSource?.dispose(); audioSource = null
        videoTracks.clear(); localVideo = null
        subscribed.clear(); midToTrack.clear(); trackToUser.clear()
        members.clear()
        channelId = null
        muted = false; deafened = false; cameraOn = false; screenOn = false
        speakerOn(false)
        VoiceService.stop(app)
    }

    /** Talking indicator: audio levels from WebRTC stats, ~6 times a second. */
    private suspend fun pollLevels() {
        while (currentCoroutineContext().isActive) {
            val conn = pc ?: break
            val report = suspendCoroutine { c -> conn.getStats { c.resume(it) } }
            for (s in report.statsMap.values) {
                val level = (s.members["audioLevel"] as? Number)?.toFloat() ?: continue
                when (s.type) {
                    "inbound-rtp" -> {
                        val mid = s.members["mid"] as? String ?: continue
                        val user = midToTrack[mid]?.let { trackToUser[it] } ?: continue
                        members[user]?.let { members[user] = it.copy(level = level) }
                    }
                    "media-source" -> if (s.members["kind"] == "audio") {
                        myLevel = if (muted) 0f else level
                        members[myId]?.let { members[myId] = it.copy(level = myLevel) }
                    }
                }
            }
            delay(160)
        }
    }

    private fun speakerOn(on: Boolean) {
        val am = app.getSystemService(AudioManager::class.java)
        am.mode = if (on) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        am.isSpeakerphoneOn = on
    }

    private val observer = object : PeerConnection.Observer {
        override fun onTrack(t: RtpTransceiver) {
            val track = t.receiver.track()
            val mid = t.mid ?: return
            val id = midToTrack[mid] ?: return
            if (track is VideoTrack) videoTracks[id] = track
            if (track is AudioTrack) {
                track.setEnabled(!deafened)
                trackToUser[id]?.let { u -> userVolume[u]?.let { runCatching { track.setVolume(it.toDouble()) } } }
            }
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            trace("ice: $s")
            if (s == PeerConnection.IceConnectionState.FAILED) error = "Couldn't connect the audio (network)"
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState) { trace("connection: $s") }
        override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidate(c: IceCandidate?) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
        override fun onAddStream(s: MediaStream?) {}
        override fun onRemoveStream(s: MediaStream?) {}
        override fun onDataChannel(d: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }
}

// --- suspend helpers around the callback-based WebRTC API ---

private suspend fun PeerConnection.offer(): SessionDescription = suspendCoroutine { c ->
    createOffer(object : SdpAdapter() {
        override fun onCreateSuccess(d: SessionDescription) = c.resume(d)
        override fun onCreateFailure(e: String?) = c.resumeWith(Result.failure(IllegalStateException(e)))
    }, MediaConstraints())
}

private suspend fun PeerConnection.answer(): SessionDescription = suspendCoroutine { c ->
    createAnswer(object : SdpAdapter() {
        override fun onCreateSuccess(d: SessionDescription) = c.resume(d)
        override fun onCreateFailure(e: String?) = c.resumeWith(Result.failure(IllegalStateException(e)))
    }, MediaConstraints())
}

private suspend fun PeerConnection.setLocal(d: SessionDescription): Unit = suspendCoroutine { c ->
    setLocalDescription(object : SdpAdapter() {
        override fun onSetSuccess() = c.resume(Unit)
        override fun onSetFailure(e: String?) = c.resumeWith(Result.failure(IllegalStateException(e)))
    }, d)
}

private suspend fun PeerConnection.setRemote(d: SessionDescription): Unit = suspendCoroutine { c ->
    setRemoteDescription(object : SdpAdapter() {
        override fun onSetSuccess() = c.resume(Unit)
        override fun onSetFailure(e: String?) = c.resumeWith(Result.failure(IllegalStateException(e)))
    }, d)
}

private open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(d: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(e: String?) {}
    override fun onSetFailure(e: String?) {}
}
