package app.bloom.ui.screens

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import app.bloom.AppViewModel
import app.bloom.PendingAttachment
import app.bloom.core.Attachment
import app.bloom.core.AttachmentKind
import app.bloom.ui.components.*
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.roundToInt

/** Which panel is open under the message box. */
enum class ComposerPanel { NONE, ATTACH, GIFS, EMOJI }

// ---------------------------------------------------------------- attach sheet

/**
 * The "+" sheet: four big options (Photos & library, GIFs, Camera, Files) and a
 * grid of recent photos/videos you can tap to add (multi-select).
 */
@Composable
fun AttachSheet(vm: AppViewModel, onGifs: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> vm.addAttachments(uris); if (uris.isNotEmpty()) onClose() }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addAttachments(uris); if (uris.isNotEmpty()) onClose() }
    var cameraUri by remember { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) cameraUri?.let { vm.addAttachments(listOf(it)); onClose() } }

    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            SheetOption(Icons.Rounded.PhotoLibrary, "Photos") { photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
            SheetOption(Icons.Rounded.Gif, "GIFs", onGifs)
            SheetOption(Icons.Rounded.PhotoCamera, "Camera") {
                val dir = File(ctx.cacheDir, "captures").apply { mkdirs() }
                val f = File(dir, "photo-${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                cameraUri = uri
                camera.launch(uri)
            }
            SheetOption(Icons.Rounded.AttachFile, "Files") { files.launch(arrayOf("*/*")) }
        }
        Spacer(Modifier.height(10.dp))
        RecentMedia(vm)
    }
}

@Composable
private fun SheetOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pressScale(src).clickable(src, null, onClick = onClick).padding(6.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(LocalLook.current.cardRadius * 0.7f)).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = scheme.onSecondaryContainer)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** Grid of the newest photos/videos on the device (asks for permission once). */
@Composable
private fun RecentMedia(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val perms = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    fun granted() = perms.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var allowed by remember { mutableStateOf(granted()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { allowed = granted() }
    if (!allowed) {
        OutlinedButton(onClick = { ask.launch(perms) }, modifier = Modifier.fillMaxWidth()) { Text("Show recent photos here") }
        return
    }
    val items by produceState(emptyList<Pair<Uri, Boolean>>()) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val out = mutableListOf<Pair<Uri, Boolean>>()
            val col = MediaStore.Files.getContentUri("external")
            val proj = arrayOf(MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE)
            val sel = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}, ${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
            runCatching {
                ctx.contentResolver.query(col, proj, sel, null, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC")?.use { c ->
                    while (c.moveToNext() && out.size < 60) {
                        val id = c.getLong(0)
                        val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                        out += ContentUris.withAppendedId(base, id) to video
                    }
                }
            }
            out
        }
    }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items(items, key = { it.first.toString() }) { (uri, video) ->
            val picked = vm.pending.any { it.uri == uri }
            val ring by animateFloatAsState(if (picked) 1f else 0f, Motion.spatialFast(), label = "pick")
            Box(
                Modifier.aspectRatio(1f).graphicsLayer { scaleX = 1f - 0.08f * ring; scaleY = scaleX }
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { if (picked) vm.pending.firstOrNull { it.uri == uri }?.let { vm.removeAttachment(it) } else vm.addAttachments(listOf(uri)) },
            ) {
                AsyncImage(uri, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (video) Icon(Icons.Rounded.PlayCircle, null, Modifier.align(Alignment.Center).size(28.dp), tint = Color.White)
                if (picked) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = scheme.onPrimary)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- preview tray

/** Picked attachments shown above the message box before sending (tap = preview, × = remove). */
@Composable
fun PendingTray(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    AnimatedVisibility(vm.pending.isNotEmpty(), enter = expandVertically(Motion.spatial()) + fadeIn(Motion.effects()), exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects())) {
        LazyRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.pending, key = { it.uri.toString() }) { p ->
                Box(Modifier.size(84.dp).animateItem(placementSpec = Motion.spatial())) {
                    Box(
                        Modifier.matchParentSize().clip(app.bloom.ui.theme.Radii.tile).background(scheme.surfaceContainerHighest)
                            .clickable(enabled = p.isImage || p.isVideo) {
                                vm.viewer = Attachment(if (p.isVideo) AttachmentKind.VIDEO else AttachmentKind.IMAGE, p.name, p.uri.toString(), null, 0, 0)
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (p.isImage || p.isVideo) AsyncImage(p.uri, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                            Icon(Icons.Rounded.Description, null, tint = scheme.primary)
                            Text(p.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (p.isVideo) Icon(Icons.Rounded.PlayCircle, null, Modifier.size(26.dp), tint = Color.White)
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).background(scheme.inverseSurface.copy(alpha = 0.85f))
                            .clickable { vm.removeAttachment(p) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Rounded.Close, "Remove", Modifier.size(16.dp), tint = scheme.inverseOnSurface) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- GIFs

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GifPanel(vm: AppViewModel, onSent: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    LaunchedEffect(query) { delay(350); vm.loadGifs(query) }
    Column(Modifier.fillMaxWidth().heightIn(max = 380.dp).fillMaxHeight().padding(horizontal = 10.dp)) {
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("Search GIFs") }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        vm.gifError?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(4.dp)) }
        val favs = vm.gifFavorites
        LazyVerticalGrid(GridCells.Fixed(2), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (query.isBlank() && favs.isNotEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { Text("Favourites", style = MaterialTheme.typography.labelLarge, color = scheme.primary) }
                items(favs, key = { "fav-$it" }) { url -> GifTile(url, url, true, { vm.sendGif(url); onSent() }, { vm.toggleGifFavorite(url) }) }
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) { Text("Trending", style = MaterialTheme.typography.labelLarge, color = scheme.primary) }
            }
            items(vm.gifResults, key = { it.url }) { g -> GifTile(g.preview, g.url, g.url in favs, { vm.sendGif(g.url); onSent() }, { vm.toggleGifFavorite(g.url) }) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GifTile(preview: String, url: String, fav: Boolean, onSend: () -> Unit, onFav: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().aspectRatio(1.3f).clip(app.bloom.ui.theme.Radii.tile).background(scheme.surfaceContainerHighest).combinedClickable(onClick = onSend, onLongClick = onFav)) {
        AsyncImage(preview, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Icon(
            if (fav) Icons.Rounded.Star else Icons.Rounded.StarOutline, if (fav) "Unfavourite" else "Favourite",
            Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f)).clickable(onClick = onFav).padding(4.dp),
            tint = if (fav) Color(0xFFFFD54F) else Color.White,
        )
    }
}

// ---------------------------------------------------------------- emoji

/** Emoji panel: your custom (incl. animated) emoji first, then unicode by category, with search. */
@Composable
fun EmojiPanel(vm: AppViewModel, onPick: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { vm.loadEmoji() }
    var query by remember { mutableStateOf("") }
    val cats = remember(vm.emojiCatalog) { vm.emojiCatalog.groupBy { it.category } }
    // frequently used first (like Root's own panel), when there are any
    var cat by remember { mutableStateOf(if (vm.recentEmoji.isNotEmpty()) "Recent" else "Custom") }
    val pick: (String) -> Unit = { e -> vm.noteEmoji(e); onPick(e) }
    Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).fillMaxHeight().padding(horizontal = 10.dp)) {
        OutlinedTextField(
            query, { query = it }, placeholder = { Text("Search emoji") }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(50),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        )
        if (query.isBlank()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                items((if (vm.recentEmoji.isNotEmpty()) listOf("Recent") else emptyList()) + listOf("Custom") + cats.keys.toList()) { c ->
                    FilterChip(cat == c, { cat = c }, { Text(c.substringBefore(" &"), maxLines = 1) })
                }
            }
        }
        val q = query.trim().lowercase()
        LazyVerticalGrid(GridCells.Adaptive(44.dp)) {
            if (q.isEmpty() && cat == "Recent") items(vm.recentEmoji, key = { "r-$it" }) { e ->
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { pick(e) }, contentAlignment = Alignment.Center) {
                    if (app.bloom.ui.theme.Ui.emojiPack != "system") EmojiGlyph(e, Modifier.size(30.dp)) else
                    Text(e, style = MaterialTheme.typography.headlineSmall)
                }
            }
            val custom = vm.customEmoji.filter { q.isEmpty() || it.shortcode.lowercase().contains(q) }
            if (q.isNotEmpty() || cat == "Custom") items(custom, key = { "c-" + it.id }) { e ->
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { onPick(vm.emojiChar(vm.customEmoji.indexOf(e))) }.padding(6.dp)) {
                    AsyncImage(e.url, e.shortcode, Modifier.fillMaxSize()) // animated GIF/WebP play here too
                }
            }
            val list = if (q.isNotEmpty()) vm.emojiCatalog.filter { e -> e.names.any { it.contains(q) } } else cats[cat].orEmpty()
            items(list, key = { "u-" + it.emoji }) { e ->
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).clickable { pick(e.emoji) }, contentAlignment = Alignment.Center) {
                    if (app.bloom.ui.theme.Ui.emojiPack != "system") EmojiGlyph(e.emoji, Modifier.size(30.dp)) else
                    Text(e.emoji, style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (cat == "Custom" && q.isEmpty() && custom.isEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text("No custom emoji in your servers yet", color = scheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- voice messages

/** Records AAC audio into the cache dir while the send button is held. */
class VoiceRecorder(private val ctx: android.content.Context) {
    private var rec: MediaRecorder? = null
    var file: File? = null; private set
    private var started = 0L

    fun start(): Boolean = runCatching {
        val f = File(File(ctx.cacheDir, "captures").apply { mkdirs() }, "voice-${System.currentTimeMillis()}.m4a")
        rec = MediaRecorder(ctx).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(64_000)
            setAudioSamplingRate(44_100)
            setOutputFile(f.absolutePath)
            prepare()
            start()
        }
        file = f
        started = System.currentTimeMillis()
        true
    }.getOrDefault(false)

    fun amplitude(): Float = runCatching { (rec?.maxAmplitude ?: 0) / 32767f }.getOrDefault(0f)

    /** Stops; returns duration in ms (file is kept unless cancelled). */
    fun stop(cancel: Boolean): Long {
        val d = System.currentTimeMillis() - started
        runCatching { rec?.stop() }
        rec?.release()
        rec = null
        if (cancel) { file?.delete(); file = null }
        return d
    }
}

/**
 * Send button that also records voice messages: hold to record, slide left to
 * cancel, release to send. Tapping sends normally when there's something to send.
 */
@Composable
fun SendOrRecordButton(vm: AppViewModel, canSend: Boolean, onSend: () -> Unit, content: @Composable (recording: Boolean, level: Float) -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val ctx = LocalContext.current
    val recorder = remember { VoiceRecorder(ctx) }
    var recording by remember { mutableStateOf(false) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var elapsed by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val cancelling = dragX < -140f
    // loudness samples for the waveform Root shows on voice messages
    val samples = remember { mutableListOf<Float>() }
    LaunchedEffect(recording) {
        val t0 = System.currentTimeMillis()
        if (recording) samples.clear()
        while (recording) { elapsed = System.currentTimeMillis() - t0; level = recorder.amplitude(); samples.add(level); delay(60) }
    }
    Box(contentAlignment = Alignment.CenterEnd) {
        if (recording) {
            val scheme = MaterialTheme.colorScheme
            Row(
                Modifier.offset { IntOffset(-64.dp.roundToPx(), 0) }.clip(RoundedCornerShape(50)).background(scheme.errorContainer).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size((10 + 10 * level).dp).clip(CircleShape).background(scheme.error))
                Spacer(Modifier.width(8.dp))
                Text("%d:%02d".format(elapsed / 60000, elapsed / 1000 % 60), color = scheme.onErrorContainer)
                Spacer(Modifier.width(10.dp))
                Text(if (cancelling) "Release to cancel" else "‹ Slide to cancel", color = scheme.onErrorContainer, style = MaterialTheme.typography.labelMedium)
            }
        }
        val grow by animateFloatAsState(if (recording) 1.28f else 1f, Motion.spatial(), label = "recGrow")
        val currentCanSend by rememberUpdatedState(canSend)
        val currentOnSend by rememberUpdatedState(onSend)
        val viewConfig = androidx.compose.ui.platform.LocalViewConfiguration.current
        Box(
            Modifier
                .graphicsLayer { translationX = if (recording) dragX.coerceAtMost(0f) else 0f; scaleX = grow; scaleY = grow }
                // One gesture from finger down to finger up: a tap sends; holding records,
                // sliding left cancels, letting go sends the voice message.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val longPress = withTimeoutOrNull(viewConfig.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        }
                        if (longPress != null) {
                            // released before the long-press time: a plain tap
                            if (currentCanSend) currentOnSend()
                            return@awaitEachGesture
                        }
                        if (currentCanSend) { waitForUpOrCancellation(); return@awaitEachGesture }
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            askMic.launch(Manifest.permission.RECORD_AUDIO); return@awaitEachGesture
                        }
                        dragX = 0f
                        recording = recorder.start()
                        if (!recording) { vm.error = "Couldn't start recording"; return@awaitEachGesture }
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                        var cancelled = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) break
                            dragX += ch.positionChange().x
                            ch.consume()
                        }
                        cancelled = dragX < -140f
                        val dur = recorder.stop(cancelled)
                        recording = false
                        if (!cancelled) recorder.file?.let { vm.sendVoice(it, dur, samples.toList()) }
                    }
                },
        ) { content(recording, level) }
    }
    if (recording) BackHandler { recorder.stop(true); recording = false }
}

// ---------------------------------------------------------------- viewers

/** Full-screen image (pinch/double-tap zoom, drag down to close) or video player. */
@Composable
fun MediaViewer(vm: AppViewModel) {
    val a = vm.viewer
    AnimatedVisibility(a != null, enter = fadeIn(Motion.effects()) + scaleIn(Motion.spatial(), initialScale = 0.92f), exit = fadeOut(Motion.effects())) {
        val item = remember(a) { a } ?: return@AnimatedVisibility
        BackHandler { vm.viewer = null }
        if (item.kind == AttachmentKind.VIDEO) VideoPlayer(item.url ?: "", onClose = { vm.viewer = null }, onSave = { vm.saveMedia(item) }, saving = vm.saving)
        else ImageViewer(item, onClose = { vm.viewer = null }, onSave = { vm.saveMedia(item) }, saving = vm.saving)
    }
}

@Composable
private fun ImageViewer(a: Attachment, onClose: () -> Unit, onSave: () -> Unit, saving: Boolean) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dismissDrag by remember { mutableFloatStateOf(0f) }
    // tap anywhere hides / shows the buttons and the vignette so the image is unobstructed
    var ui by remember { mutableStateOf(true) }
    val uiAlpha by animateFloatAsState(if (ui) 1f else 0f, Motion.effects(), label = "ui")
    val bg = (1f - (kotlin.math.abs(dismissDrag) / 900f)).coerceIn(0.3f, 1f)
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = bg))
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { scale = if (scale > 1.2f) 1f else 2.5f; offset = Offset.Zero }, onTap = { ui = !ui })
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale > 1.01f) offset += pan else dismissDrag += pan.y
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.changes.all { !it.pressed }) {
                            if (kotlin.math.abs(dismissDrag) > 250f) onClose() else dismissDrag = 0f
                        }
                    }
                }
            },
    ) {
        AsyncImage(
            a.url, a.name,
            Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y + dismissDrag },
            contentScale = ContentScale.Fit,
        )
        // top + bottom vignette only (no sides)
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = uiAlpha }.background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.7f), 0.18f to Color.Transparent, 0.82f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f),
                ),
            ),
        )
        Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp).graphicsLayer { alpha = uiAlpha }) {
            if (a.url?.startsWith("http") == true) IconButton(onClick = onSave, enabled = !saving && ui) {
                if (saving) MorphingLoader(size = 22.dp, color = Color.White) else Icon(Icons.Rounded.Download, "Save", tint = Color.White)
            }
            IconButton(onClick = onClose, enabled = ui) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
        }
    }
}

/**
 * Video player: play/pause, seek, volume, speed. Tapping empty space hides the
 * controls; tapping again (or the video ending) brings them back.
 */
@Composable
fun VideoPlayer(url: String, onClose: () -> Unit, onSave: (() -> Unit)? = null, saving: Boolean = false) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(url)); prepare(); playWhenReady = true }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    var controls by remember { mutableStateOf(true) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var volume by remember { mutableFloatStateOf(1f) }
    var speed by remember { mutableFloatStateOf(1f) }
    var ended by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    var speedMenu by remember { mutableStateOf(false) }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                if (ended) controls = true // controls come back when the video ends
            }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }
    LaunchedEffect(player) { while (true) { position = player.currentPosition; duration = player.duration.coerceAtLeast(0); delay(200) } }
    // auto-hide while playing (not while the speed menu is open)
    LaunchedEffect(controls, playing, speedMenu) { if (controls && playing && !speedMenu) { delay(3000); controls = false } }

    Box(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { controls = !controls } },
        contentAlignment = Alignment.Center,
    ) {
        // keep the video's own shape: letterbox instead of stretching
        PlayerSurface(player, Modifier.fillMaxWidth().aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f))
        AnimatedVisibility(controls, enter = fadeIn(Motion.effects()), exit = fadeOut(Motion.effects()), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))) {
                Row(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) {
                    if (onSave != null) IconButton(onClick = onSave, enabled = !saving) {
                        if (saving) MorphingLoader(size = 22.dp, color = Color.White) else Icon(Icons.Rounded.Download, "Save video", tint = Color.White)
                    }
                    IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                }
                IconButton(
                    onClick = { if (ended) { player.seekTo(0); player.play() } else if (playing) player.pause() else player.play() },
                    Modifier.align(Alignment.Center).size(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f)),
                ) { Icon(if (ended) Icons.Rounded.Replay else if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(44.dp), tint = Color.White) }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Slider(
                            value = if (duration > 0) position.toFloat() / duration else 0f,
                            onValueChange = { player.seekTo((it * duration).toLong()); position = player.currentPosition },
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        Text(fmt(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (volume == 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp, "Volume", tint = Color.White,
                            modifier = Modifier.clickable { volume = if (volume == 0f) 1f else 0f; player.volume = volume })
                        Slider(volume, { volume = it; player.volume = it }, Modifier.weight(1f).padding(horizontal = 8.dp))
                        Spacer(Modifier.weight(1f))
                        // one compact speed button with a menu (the chip row overflowed narrow screens)
                        Box {
                            TextButton(onClick = { speedMenu = true }) { Text(speedLabel(speed), color = Color.White, style = MaterialTheme.typography.titleSmall) }
                            DropdownMenu(speedMenu, onDismissRequest = { speedMenu = false }) {
                                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { sp ->
                                    DropdownMenuItem(
                                        { Text(speedLabel(sp)) },
                                        {
                                            speed = sp
                                            player.playbackParameters = androidx.media3.common.PlaybackParameters(sp)
                                            speedMenu = false
                                        },
                                        trailingIcon = { if (sp == speed) Icon(Icons.Rounded.Check, null) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Inline player for voice messages and audio files. */
/** Root's voice-message file name: voice-message-<ms>-<48 hex peaks>.m4a */
object VoiceNames {
    private val re = Regex("^voice-message-(\\d{1,6})-([0-9a-f]{48})\\.m4a$", RegexOption.IGNORE_CASE)
    fun encode(durationMs: Long, samples: List<Float>): String {
        val peaks = (0 until 48).map { i ->
            if (samples.isEmpty()) 0f else {
                val a = i * samples.size / 48
                val b = maxOf(a + 1, (i + 1) * samples.size / 48)
                samples.subList(a.coerceAtMost(samples.size - 1), b.coerceAtMost(samples.size)).maxOrNull() ?: 0f
            }
        }
        // perceptual scale: quiet speech still shows
        val hex = peaks.joinToString("") { p -> Integer.toHexString((kotlin.math.sqrt(p.coerceIn(0f, 1f)) * 15f).toInt().coerceIn(0, 15)) }
        return "voice-message-${durationMs.coerceIn(0, 300_000)}-$hex.m4a"
    }
    /** (duration ms, 48 peaks 0..1) or null if this isn't a voice message */
    fun decode(name: String): Pair<Long, List<Float>>? {
        val m = re.find(name) ?: return null
        return m.groupValues[1].toLong() to m.groupValues[2].map { it.digitToInt(16) / 15f }
    }
}

/** A voice message: play/pause, waveform that fills as it plays, time. */
@Composable
fun VoiceMessage(url: String, durationMs: Long, peaks: List<Float>) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val player = remember(url) { androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply { setMediaItem(androidx.media3.common.MediaItem.fromUri(url)); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            pos = player.currentPosition
            if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) { player.seekTo(0); player.pause() }
            delay(if (playing) 50 else 300)
        }
    }
    val dur = if (player.duration > 0) player.duration else durationMs
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.padding(top = 6.dp).widthIn(max = 300.dp).clip(RoundedCornerShape(50)).background(scheme.secondaryContainer).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(scheme.primary).clickable { if (player.isPlaying) player.pause() else player.play() },
            contentAlignment = Alignment.Center,
        ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = scheme.onPrimary) }
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.weight(1f).height(32.dp).pointerInput(dur) { detectTapGestures { o -> player.seekTo((o.x / size.width * dur).toLong()) } }) {
            val n = peaks.size.coerceAtLeast(1)
            val gap = size.width / n
            val bar = (gap * 0.55f).coerceAtLeast(2f)
            peaks.forEachIndexed { i, p ->
                val h = (size.height * (0.15f + 0.85f * p)).coerceAtLeast(bar)
                val x = i * gap + gap / 2
                val played = (i + 0.5f) / n <= frac
                drawLine(if (played) scheme.primary else scheme.onSecondaryContainer.copy(alpha = 0.35f), androidx.compose.ui.geometry.Offset(x, (size.height - h) / 2), androidx.compose.ui.geometry.Offset(x, (size.height + h) / 2), bar, androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(10.dp))
        val shown = if (playing || pos > 0) pos else dur
        Text("%d:%02d".format(shown / 60000, shown / 1000 % 60), style = MaterialTheme.typography.labelMedium, color = scheme.onSecondaryContainer, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
fun AudioAttachment(url: String, name: String) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var duration by remember { mutableLongStateOf(0L) }
    DisposableEffect(url) { onDispose { player?.release() } }
    LaunchedEffect(playing) {
        while (playing) {
            player?.let { p -> duration = p.duration.coerceAtLeast(0); progress = if (duration > 0) p.currentPosition.toFloat() / duration else 0f }
            delay(100)
        }
    }
    Row(
        Modifier.padding(top = 6.dp).widthIn(max = 300.dp).clip(RoundedCornerShape(50)).background(scheme.surfaceContainerHigh).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = {
            val p = player ?: ExoPlayer.Builder(ctx).build().also { p ->
                p.setMediaItem(MediaItem.fromUri(url)); p.prepare()
                p.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) { p.seekTo(0); p.pause(); progress = 0f } }
                })
                player = p
            }
            if (p.isPlaying) p.pause() else p.play()
        }, Modifier.size(40.dp).clip(CircleShape).background(scheme.primary)) {
            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = scheme.onPrimary)
        }
        Spacer(Modifier.width(10.dp))
        // simple pseudo-waveform that fills as it plays
        Row(Modifier.weight(1f).height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val bars = 28
            repeat(bars) { i ->
                val h = 0.3f + 0.7f * ((name.hashCode() xor (i * 2654435761L).toInt()).ushr(3) % 100) / 100f
                Box(Modifier.weight(1f).fillMaxHeight(h).clip(RoundedCornerShape(2.dp)).background(if (i < progress * bars) scheme.primary else scheme.outlineVariant))
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(if (duration > 0) fmt(duration) else "voice", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
    }
}

private fun speedLabel(s: Float) = (if (s % 1f == 0f) "${s.toInt()}" else "$s") + "×"

private fun fmt(ms: Long): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
