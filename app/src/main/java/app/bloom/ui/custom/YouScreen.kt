/*
 * The "You" page.
 *
 * Layout, the ID card, the dot grid, the giant vertical word, the big-number cards and the
 * version capsule are adapted from Mochy Music's profile page
 * (https://github.com/realidkroo/Mochy-Music: ui/screens/settings/ProfileScreen.kt and
 * ui/screens/setup/onboarding/ProfileIdCard.kt), Copyright (C) the Mochy Music contributors,
 * GNU GPL v3.0. Bloom is distributed under the same license.
 */
package app.bloom.ui.custom

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.bloom.AppViewModel
import app.bloom.core.Core
import app.bloom.ui.components.Avatar
import app.bloom.ui.components.BloomLogo
import app.bloom.ui.components.rememberFrameTime
import app.bloom.ui.theme.Motion
import app.bloom.ui.theme.Ui
import app.bloom.ui.motion.flightTarget
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val CardAspect = 1.89f
private val PageCardShape @androidx.compose.runtime.Composable get() = app.bloom.ui.theme.Radii.panel
private val CardPadding = PaddingValues(horizontal = 26.dp, vertical = 22.dp)
private const val Repo = "https://github.com/vsoultann/bloom"

/** A different hello each time you come back. */
private object Greeting {
    private val words = listOf("Hello again", "Hey", "Welcome back", "Good to see you")
    var current by mutableStateOf("Hello again"); private set
    private var lastShown = 0L
    fun refresh() {
        if (System.currentTimeMillis() - lastShown > 60_000) { current = words.random(); lastShown = System.currentTimeMillis() }
    }
}

@Composable
fun YouScreen(vm: AppViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val me = vm.me ?: return
    val haptics = LocalHapticFeedback.current
    val uri = LocalUriHandler.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { Greeting.refresh() }

    var joined by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(me.id) { joined = runCatching { (Core.social("joined", JSONObject().put("user_id", me.id)) as Number).toLong() }.getOrNull() }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    var latest by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { latest = latestRelease() }
    val update = latest?.let { isNewer(it, version) } == true

    // Pull the header down: it stretches, the card tilts with the finger, and past the
    // threshold the card flips over.
    val pull = remember { Animatable(0f) }
    val flip = remember { Animatable(0f) }
    var tilt by remember { mutableStateOf(Offset.Zero) }
    val threshold = 220f
    val list = rememberLazyListState()
    val collapsed by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > 420 } }

    Box(Modifier.fillMaxSize().background(scheme.surfaceContainerLowest)) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "header") {
                Header(
                    vm = vm, joined = joined, pull = { pull.value }, flip = { flip.value }, tilt = { tilt },
                    modifier = Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragEnd = {
                                scope.launch {
                                    if (pull.value > threshold) {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                        launch { flip.animateTo(if (flip.value < 0.5f) 1f else 0f, Motion.tween(900)) }
                                    }
                                    tilt = Offset.Zero
                                    pull.animateTo(0f, Motion.spring(0.55f, Spring.StiffnessLow))
                                }
                            },
                            onDragCancel = { tilt = Offset.Zero; scope.launch { pull.animateTo(0f, Motion.spring(0.55f, Spring.StiffnessLow)) } },
                        ) { change, dy ->
                            if (dy > 0 || pull.value > 0f) {
                                change.consume()
                                // rubber band: the further you pull, the harder it gets
                                val next = (pull.value + dy * (1f - pull.value / 600f).coerceAtLeast(0.15f)).coerceIn(0f, 520f)
                                if (pull.value <= threshold && next > threshold) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                scope.launch { pull.snapTo(next) }
                                tilt = Offset((change.position.x / size.width - 0.5f) * 2f, (next / threshold).coerceAtMost(1f))
                            }
                        }
                    },
                )
            }
            item(key = "stats") { Box(Modifier.padding(horizontal = 18.dp)) { StatsCard(vm) } }
            item(key = "version") {
                Box(Modifier.padding(horizontal = 18.dp)) {
                    VersionCard(version, if (update) "Bloom $latest is out" else null, onCard = { uri.openUri("$Repo/releases") }) {
                        uri.openUri(if (update) "$Repo/releases/latest" else Repo)
                    }
                }
            }
            item(key = "rows") {
                Column(Modifier.padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    PillRow(onClick = { uri.openUri(Repo) }, leading = {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Code, null) }
                    }, text = "Bloom on GitHub · star it :3")
                    Text("Settings", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.03).em), modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    PillRow(onClick = { vm.screen = app.bloom.Screen.Customize }, leading = { CircleIcon(Icons.Rounded.AutoAwesome) }, text = "Customize", big = true)
                    PillRow(onClick = { vm.screen = app.bloom.Screen.Settings }, leading = { CircleIcon(Icons.Rounded.Settings) }, text = "Open settings", big = true)
                }
            }
        }
        // slim bar that fades in once the header has scrolled away
        val barAlpha by animateFloatAsState(if (collapsed) 1f else 0f, Motion.smooth(), label = "bar")
        Row(
            Modifier.fillMaxWidth().graphicsLayer { alpha = barAlpha; translationY = (1f - barAlpha) * -30f }
                .clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
                .background(scheme.surfaceContainerLow.copy(alpha = 0.94f))
                .statusBarsPadding().padding(horizontal = 24.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BloomLogo(30.dp)
            Spacer(Modifier.width(14.dp))
            Text("${Greeting.current}, ${me.displayName}!", style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.03).em), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onBack, Modifier.statusBarsPadding().padding(4.dp).graphicsLayer { alpha = 1f - barAlpha }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
        }
    }
}

@Composable
private fun CircleIcon(icon: ImageVector) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(52.dp).clip(CircleShape).background(scheme.onSurface.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = scheme.surface)
    }
}

// ---------- header ----------

@Composable
private fun Header(vm: AppViewModel, joined: Long?, pull: () -> Float, flip: () -> Float, tilt: () -> Offset, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val me = vm.me ?: return
    val giantColor = scheme.onSurface.copy(alpha = 0.06f)
    val measurer = rememberTextMeasurer()
    // the giant word drifts down the right edge; slow, so 30 updates a second is plenty
    val time by if (Ui.effectsOn) rememberFrameTime(fps = 30) else remember { mutableStateOf(0f) }
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
            .background(scheme.surfaceContainerLow)
            .dotGrid(scheme.onSurface.copy(alpha = 0.07f))
            .drawWithCache {
                val style = TextStyle(fontWeight = FontWeight.Black, fontSize = (size.width * 0.42f / density / fontScale).sp, letterSpacing = (-0.03).em, color = giantColor)
                val word = measurer.measure("BLOOM", style)
                onDrawBehind {
                    // a column of the word turned on its side, sliding down forever
                    val pitch = word.size.width + 40.dp.toPx()
                    val shift = (time * 22.dp.toPx()) % pitch
                    val x = size.width * 0.86f
                    val cy = size.height / 2
                    rotate(90f, Offset(x, cy)) {
                        var y = -pitch * 2 + shift
                        while (y < size.height + pitch) {
                            drawText(word, topLeft = Offset(x - cy + y, cy - word.size.height / 2f))
                            y += pitch
                        }
                    }
                }
            }
            .statusBarsPadding()
            .padding(top = 44.dp, bottom = 18.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
            BloomLogo(56.dp)
            Spacer(Modifier.height(22.dp))
            Text(
                "${Greeting.current}, ${me.displayName}!",
                style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-0.035).em),
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(22.dp))
            // stretches with the pull
            Spacer(Modifier.height(with(LocalDensity.current) { (pull() * 0.55f).toDp() }))
            IdCard(vm, joined, flip, tilt)
            Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.KeyboardArrowDown, "Pull down to flip the card",
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.graphicsLayer { rotationZ = if (flip() > 0.5f) 180f else 0f },
                )
            }
        }
    }
}

private fun Modifier.dotGrid(color: Color): Modifier = drawWithCache {
    val step = 12.dp.toPx().toInt().coerceAtLeast(2)
    val tile = ImageBitmap(step, step)
    val paint = Paint().apply { this.color = color; isAntiAlias = true }
    Canvas(tile).drawCircle(Offset(step / 2f, step / 2f), 0.9.dp.toPx(), paint)
    val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    onDrawBehind { drawRect(brush) }
}

/** The ID card; its back (after a pull) shows your Root at a glance. */
@Composable
private fun IdCard(vm: AppViewModel, joined: Long?, flip: () -> Float, tilt: () -> Offset) {
    val scheme = MaterialTheme.colorScheme
    val me = vm.me ?: return
    val muted = scheme.onSurfaceVariant
    // spread the small local id into a card-looking 11-digit number (stable per account)
    val number = remember(me.id, me.name) { ((me.name.hashCode().toLong() and 0xFFFFFFFFL) * 2654435761L % 90_000_000_000L + 10_000_000_000L).toString() }
    val joinedText = joined?.let { "joined at " + java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(it * 1000)) } ?: "on Root"
    val front = flip() < 0.5f
    BoxWithConstraints(
        Modifier.fillMaxWidth().aspectRatio(CardAspect)
            .graphicsLayer {
                rotationY = flip() * 180f + tilt().x * 8f
                rotationX = -tilt().y * 6f
                cameraDistance = 14f * density
            },
    ) {
        val unit = maxWidth / 332f
        Row(
            Modifier.fillMaxSize().graphicsLayer { if (!front) rotationY = 180f }
                .clip(RoundedCornerShape(22.dp)).background(scheme.surfaceContainerHighest),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight().padding(start = unit * 22f, top = unit * 20f, end = unit * 12f, bottom = unit * 18f)) {
                if (front) {
                    Text("PROFILE USR#$number", color = muted, fontFamily = FontFamily.Monospace, fontSize = (unit.value * 11f).sp, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(me.displayName, me.hue, unit * 96f, Modifier.flightTarget(me.id), userId = me.id, shape = CircleShape)
                        Spacer(Modifier.width(unit * 18f))
                        Column(Modifier.weight(1f)) {
                            Text("ID CARD OF", color = muted, fontFamily = FontFamily.Monospace, fontSize = (unit.value * 10f).sp)
                            Text(me.displayName, color = scheme.onSurface, fontSize = (unit.value * 21f).sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(joinedText, color = scheme.onSurface.copy(alpha = 0.85f), fontSize = (unit.value * 10.5f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                } else {
                    Text("ROOT · ${me.name.uppercase()}", color = muted, fontFamily = FontFamily.Monospace, fontSize = (unit.value * 11f).sp, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(unit * 22f)) {
                        BackStat("${vm.servers.size}", "servers", unit)
                        BackStat("${vm.friends.size}", "friends", unit)
                        BackStat("${vm.dms.size}", "chats", unit)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(me.statusText.ifBlank { "pull again to flip back" }, color = muted, fontSize = (unit.value * 11f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            MarqueeStrip("${me.displayName}`s CARD", muted, scheme.surfaceContainerHigh, unit.value * 17f, Modifier.width(unit * 40f).fillMaxHeight())
        }
    }
}

@Composable
private fun BackStat(value: String, label: String, unit: Dp) {
    Column {
        Text(value, fontWeight = FontWeight.Bold, fontSize = (unit.value * 30f).sp, letterSpacing = (-1).sp)
        Text(label, fontFamily = FontFamily.Monospace, fontSize = (unit.value * 10f).sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Vertical text turned a quarter, sliding down forever (drawn, not recomposed). */
@Composable
private fun MarqueeStrip(text: String, color: Color, background: Color, fontSizeSp: Float, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val style = remember(color, fontSizeSp) { TextStyle(color = color, fontSize = fontSizeSp.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp) }
    val layout = remember(text, style) { measurer.measure("$text   ·   ", style) }
    val time by if (Ui.effectsOn) rememberFrameTime(fps = 30) else remember { mutableStateOf(0f) }
    Canvas(modifier.background(background)) {
        val w = layout.size.width.toFloat()
        val shift = (time / 5.2f * w) % w
        rotate(90f, Offset(size.width / 2, size.height / 2)) {
            val left = size.width / 2 - size.height / 2
            var x = -w + shift
            while (x < size.height + w) {
                drawText(layout, topLeft = Offset(left + x, size.height / 2 - layout.size.height / 2f))
                x += w
            }
        }
    }
}

// ---------- cards ----------

@Composable
private fun StatsCard(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(PageCardShape).background(scheme.surfaceContainer).clickable { vm.screen = app.bloom.Screen.Chat }.padding(CardPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BigValue("${vm.servers.size}", "s")
            Box(Modifier.padding(horizontal = 14.dp).width(1.dp).height(34.dp).background(scheme.onSurface.copy(alpha = 0.4f)))
            BigValue("${vm.friends.size}", "f")
            Spacer(Modifier.width(10.dp))
            Text(
                "Servers &\nfriends",
                style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 18.sp, color = scheme.onSurfaceVariant, textAlign = TextAlign.End),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(scheme.outlineVariant))
        Spacer(Modifier.height(12.dp))
        Text("${vm.dms.size} conversations  >", style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = scheme.onSurfaceVariant))
    }
}

/** A big number that rolls when it changes, with a small unit after it. */
@Composable
private fun BigValue(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedContent(value, transitionSpec = {
            (slideInVertically(Motion.spring(0.8f, 380f)) { it } + fadeIn(Motion.effects())) togetherWith (slideOutVertically(Motion.spring(0.8f, 380f)) { -it } + fadeOut(Motion.effects()))
        }, label = "big") { v -> Text(v, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-2).sp)) }
        Text(unit, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp), modifier = Modifier.padding(start = 1.dp, bottom = 6.dp))
    }
}

@Composable
private fun VersionCard(version: String, updateHeadline: String?, onCard: () -> Unit, onPill: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(PageCardShape).background(scheme.surfaceContainer).clickable(onClick = onCard).padding(CardPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Version & about app", style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = scheme.onSurfaceVariant))
            Text(version, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 44.sp, letterSpacing = (-2).sp), maxLines = 1)
            if (updateHeadline != null) Text(updateHeadline, style = TextStyle(fontSize = 12.sp, color = scheme.tertiary))
        }
        Spacer(Modifier.width(10.dp))
        VersionCapsule(updateHeadline != null, onPill)
    }
}

/** "About app", or "Update" once one is out; the swap squashes and springs. */
@Composable
private fun VersionCapsule(update: Boolean, onClick: () -> Unit) {
    val shown = if (update) "Update" else "About app"
    val pop = remember { Animatable(1f) }
    var popped by remember { mutableStateOf(false) }
    LaunchedEffect(shown) {
        if (!popped) { popped = true; return@LaunchedEffect }
        pop.animateTo(0.86f, Motion.tween(90))
        pop.animateTo(1f, Motion.spring(0.38f, 520f))
    }
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape)
            .background(if (update) scheme.tertiary else scheme.surfaceContainerHighest).clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(shown, transitionSpec = {
            (scaleIn(Motion.spring(0.45f, 420f), initialScale = 0.35f) + fadeIn(Motion.tween(160))) togetherWith
                (scaleOut(Motion.tween(140), targetScale = 0.35f) + fadeOut(Motion.tween(120))) using
                SizeTransform(clip = false) { _, _ -> Motion.spring(0.6f, 380f) }
        }, label = "capsule") { l ->
            Text(l, style = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = if (update) scheme.onTertiary else scheme.onSurface), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun PillRow(onClick: () -> Unit, leading: @Composable () -> Unit, text: String, big: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.spring(0.6f, 900f), label = "press")
    Row(
        Modifier.fillMaxWidth().graphicsLayer { scaleX = k; scaleY = k }.clip(RoundedCornerShape(if (big) 32.dp else 36.dp))
            .background(scheme.surfaceContainer).clickable(src, null, onClick = onClick).padding(horizontal = 20.dp, vertical = if (big) 18.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(18.dp))
        Text(text, style = TextStyle(fontWeight = FontWeight.Bold, fontSize = if (big) 20.sp else 17.sp, letterSpacing = (-0.03).em))
    }
}

// ---------- update check ----------

private suspend fun latestRelease(): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        val c = java.net.URL("https://api.github.com/repos/vsoultann/bloom/releases/latest").openConnection() as java.net.HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.connectTimeout = 6000; c.readTimeout = 6000
        JSONObject(c.inputStream.bufferedReader().readText()).optString("tag_name").removePrefix("v").ifBlank { null }
    }.getOrNull()
}

private fun isNewer(latest: String, current: String): Boolean {
    fun parts(v: String) = v.split('.', '-', ' ').mapNotNull { it.toIntOrNull() }
    val a = parts(latest); val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
