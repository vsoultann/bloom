/*
 * Customize screen for Bloom.
 *
 * The card, slider and style-pager components below are adapted from Mochy Music
 * (https://github.com/realidkroo/Mochy-Music, ui/screens/gesture/CustomisationCards.kt),
 * Copyright (C) the Mochy Music contributors, licensed under the GNU General Public
 * License v3.0. Bloom is distributed under the same license.
 */
package app.bloom.ui.custom

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.bloom.AppViewModel
import app.bloom.notify.NowPlayingService
import app.bloom.ui.components.MorphingLoader
import app.bloom.ui.components.rememberLivingShape
import app.bloom.ui.screens.EmojiGlyph
import app.bloom.ui.screens.BloomSwitch
import app.bloom.ui.theme.Motion
import app.bloom.ui.theme.Ui
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val PageMargin = 20.dp
private const val MaxRadiusPx = 45f
private const val DefaultRadiusPx = 20f
private val CardShape @androidx.compose.runtime.Composable get() = app.bloom.ui.theme.Radii.panel
private val SliderCardShape @androidx.compose.runtime.Composable get() = app.bloom.ui.theme.Radii.panel
private val CardTextInset = 22.dp
private val CardLabel = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, letterSpacing = (-0.03).em)
private val CardValue = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 34.sp, letterSpacing = (-0.045).em)
private val SmallLabel = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = (-0.01).em)
private val SliderTitle = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = (-0.03).em)

private enum class Part(val title: String, val words: String) {
    ROUNDING("Corner rounding", "rounding corner radius square rounded cards avatars media"),
    MOTION("Animation", "animation speed motion smooth off faster slower entrance messages"),
    ICONS("Living icons", "living icons morph shapes server rail battery"),
    BLUR("Blur", "blur glass frosted strength element"),
    FONT("Font", "font typeface text import weight bold spacing google inter"),
    EMOJI("Emoji", "emoji apple windows fluent google twitter facebook samsung pack"),
    MUSIC("Now playing", "music now playing spotify youtube soundcloud listening status"),
    SCREEN("Screen", "status bar full screen hide profile bubble status"),
    PERFORMANCE("Performance", "performance fps 120hz battery saver smooth"),
}

@Composable
fun CustomizeScreen(vm: AppViewModel, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val shown = Part.entries.filter { p -> query.isBlank() || query.lowercase().split(' ').filter { it.isNotBlank() }.all { w -> (p.words + " " + p.title.lowercase()).contains(w) } }
    Box(Modifier.fillMaxSize().background(scheme.surface)) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 48.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Customize", Modifier.padding(horizontal = 12.dp), style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.04).em), color = scheme.onSurface)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    query, { query = it }, placeholder = { Text("Search") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        shown.forEach { part ->
            item(key = part.name) {
                Column(Modifier.padding(horizontal = PageMargin)) {
                    when (part) {
                        Part.ROUNDING -> RoundingSection(vm)
                        Part.MOTION -> MotionSection(vm)
                        Part.ICONS -> IconsSection(vm)
                        Part.BLUR -> BlurSection(vm)
                        Part.FONT -> FontSection(vm)
                        Part.EMOJI -> EmojiSection(vm)
                        Part.MUSIC -> MusicSection(vm)
                        Part.SCREEN -> ScreenSection(vm)
                        Part.PERFORMANCE -> PerformanceSection()
                    }
                }
            }
        }
        if (shown.isEmpty()) item { Text("Nothing matches \"$query\"", Modifier.padding(PageMargin), color = scheme.onSurfaceVariant) }
        item {
            Text(
                "Customize page design adapted from Mochy Music (GPL-3.0).",
                Modifier.padding(horizontal = PageMargin), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
            )
        }
    }
    // keeps scrolled content from running under the clock and icons
    Box(
        Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars)
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(scheme.surface, scheme.surface.copy(alpha = 0.85f)))),
    )
    }
}

// ---------- ported building blocks ----------

@Composable
private fun CardTitle(title: String, canReset: Boolean = false, onReset: (() -> Unit)? = null) {
    val haptic = LocalHapticFeedback.current
    val c = MaterialTheme.colorScheme.onSurface
    Row(Modifier.padding(start = CardTextInset, top = CardTextInset)) {
        Text(title, style = CardLabel, color = c.copy(alpha = 0.9f))
        if (onReset != null) Icon(
            Icons.Rounded.Refresh, "Reset",
            tint = c.copy(alpha = if (canReset) 0.8f else 0.25f),
            modifier = Modifier.offset(x = 2.dp, y = (-9).dp).size(34.dp).clip(CircleShape)
                .clickable(enabled = canReset) { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onReset() }.padding(8.dp),
        )
    }
}

/** A slim card: title (+ value), a bare slider line, and what each end means. */
@Composable
private fun SliderCard(title: String, startLabel: String, endLabel: String, fraction: Float, onFraction: (Float) -> Unit, valueText: String? = null, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme.onSurface
    Column(
        Modifier.fillMaxWidth().clip(SliderCardShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .padding(start = CardTextInset, end = CardTextInset, top = 16.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(title, style = SliderTitle, color = c, modifier = Modifier.weight(1f))
            if (valueText != null) Text(valueText, style = SliderTitle, color = c.copy(alpha = 0.7f))
        }
        Spacer(Modifier.height(4.dp))
        BareSlider(fraction, onFraction, enabled)
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(startLabel, style = SmallLabel, color = c.copy(alpha = 0.5f))
            Text(endLabel, style = SmallLabel, color = c.copy(alpha = 0.5f))
        }
    }
}

/** A bare line: the bright part is how far along it is; tap or drag anywhere. */
@Composable
private fun BareSlider(fraction: Float, onFraction: (Float) -> Unit, enabled: Boolean) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    val shown = fraction.coerceIn(0f, 1f)
    val currentShown by rememberUpdatedState(shown)
    val currentOn by rememberUpdatedState(onFraction)
    fun update(x: Float) = currentOn((x / widthPx).coerceIn(0f, 1f))
    var dragX by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { d -> dragX += d; update(dragX) }
    val c = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Canvas(
        Modifier.fillMaxWidth().height(22.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(enabled) { if (enabled) detectTapGestures { o -> update(o.x) } }
            .draggable(drag, Orientation.Horizontal, enabled = enabled, onDragStarted = { dragX = currentShown * widthPx }),
    ) {
        val y = size.height / 2f
        val stroke = 3.dp.toPx()
        val start = stroke / 2f
        val end = size.width - stroke / 2f
        drawLine(track, Offset(start, y), Offset(end, y), stroke, StrokeCap.Round)
        drawLine(c, Offset(start, y), Offset(start + (end - start) * shown, y), stroke, StrokeCap.Round)
        drawCircle(c, 7.dp.toPx(), Offset(start + (end - start) * shown, y))
    }
}

/** The big corner tucked into the card's top right, with an arc hugging its bend. */
@Composable
private fun RoundingSwatch(radiusPx: Float, modifier: Modifier) {
    val swatch = MaterialTheme.colorScheme.primaryContainer
    val arc = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val r = radiusPx.coerceIn(0f, MaxRadiusPx).dp.toPx()
        val left = size.width - 146.dp.toPx()
        val bottom = 135.dp.toPx()
        drawRoundRect(swatch, Offset(left, -r), Size(size.width - left + r, bottom + r), CornerRadius(r, r))
        val gap = 19.dp.toPx(); val tail = 12.dp.toPx()
        val arcR = r + gap
        val x = left - gap; val y = bottom + gap
        val path = Path().apply {
            moveTo(x, y - arcR - tail); lineTo(x, y - arcR)
            arcTo(Rect(x, y - arcR * 2f, x + arcR * 2f, y), 180f, -90f, false)
            lineTo(x + arcR + tail, y)
        }
        drawPath(path, arc, style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** A swipeable row of style cards; tapping one uses it and outlines it. */
@Composable
private fun <T> StylePager(category: String, styles: List<T>, selected: T, nameOf: (T) -> String, onSelect: (T) -> Unit, preview: @Composable BoxScope.(T) -> Unit) {
    val pager = rememberPagerState(initialPage = styles.indexOf(selected).coerceAtLeast(0)) { styles.size }
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(pager, contentPadding = PaddingValues(end = 40.dp), pageSpacing = 12.dp, modifier = Modifier.fillMaxWidth()) { page ->
            val style = styles[page]
            val ring by animateColorAsState(if (style == selected) scheme.primary else Color.Transparent, app.bloom.ui.theme.Motion.effects(), label = "ring")
            Box(
                Modifier.fillMaxWidth().height(170.dp).clip(CardShape).background(scheme.surfaceContainerHigh)
                    .clickable {
                        if (style != selected) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(style) }
                        if (pager.currentPage != page) scope.launch { pager.animateScrollToPage(page) }
                    },
            ) {
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp).size(120.dp), contentAlignment = Alignment.Center) { preview(style) }
                Text(category, style = CardLabel, color = scheme.onSurface.copy(alpha = 0.9f), modifier = Modifier.padding(start = CardTextInset, top = CardTextInset))
                Text(
                    nameOf(style), style = CardValue.copy(fontSize = 28.sp), color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = CardTextInset, bottom = 22.dp, end = 150.dp),
                )
                Box(Modifier.matchParentSize().border(2.dp, ring, CardShape))
            }
        }
        Text("Swipe to see more · tap to use", style = SmallLabel, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    }
}

@Composable
private fun ToggleCard(title: String, sub: String, on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(SliderCardShape).background(scheme.surfaceContainerHigh)
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .clickable(enabled = enabled) { onChange(!on) }.padding(horizontal = CardTextInset, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = SliderTitle, color = scheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        BloomSwitch(on, onChange, enabled = enabled)
    }
}

@Composable
private fun SectionHeader(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 6.dp))

// ---------- sections ----------

@Composable
private fun RoundingSection(vm: AppViewModel) {
    val haptic = LocalHapticFeedback.current
    val px = Ui.rounding * DefaultRadiusPx
    var lastTick by remember { mutableIntStateOf((px / 5f).roundToInt()) }
    Box(Modifier.fillMaxWidth().height(186.dp).clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        RoundingSwatch(px, Modifier.matchParentSize())
        CardTitle("Corner rounding", kotlin.math.abs(px - DefaultRadiusPx) >= 0.5f) { vm.setPref("ui_rounding", 1f) }
        Text("${px.roundToInt()}px", style = CardValue, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.align(Alignment.BottomStart).padding(start = CardTextInset, bottom = 24.dp))
    }
    Spacer(Modifier.height(11.dp))
    SliderCard("Customize it", "Square", "More rounded", px / MaxRadiusPx, { f ->
        val next = (f * MaxRadiusPx).coerceAtLeast(2f)
        val tick = (next / 5f).roundToInt()
        if (tick != lastTick) { lastTick = tick; haptic.performHapticFeedback(HapticFeedbackType.SegmentTick) }
        vm.setPref("ui_rounding", next / DefaultRadiusPx)
    })
}

private val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private fun speedText(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0')) + "x"

@Composable
private fun MotionSection(vm: AppViewModel) {
    val haptic = LocalHapticFeedback.current
    SectionHeader("Animation")
    ToggleCard("Animations", "Off = everything jumps straight to its end", Ui.animations) { vm.setPref("ui_animations", it) }
    Spacer(Modifier.height(10.dp))
    val idx = Speeds.indexOf(Ui.speed).coerceAtLeast(0)
    SliderCard("Animation speed", "Slower", "Faster", idx / (Speeds.size - 1f), { f ->
        val i = (f * (Speeds.size - 1)).roundToInt()
        if (Speeds[i] != Ui.speed) { haptic.performHapticFeedback(HapticFeedbackType.SegmentTick); vm.setPref("ui_speed", Speeds[i]) }
    }, valueText = speedText(Ui.speed), enabled = Ui.animations)
    Spacer(Modifier.height(14.dp))
    StylePager("New messages", listOf("rise", "blur", "fade", "none"), Ui.messageEnter,
        { mapOf("rise" to "Rise", "blur" to "Blur in", "fade" to "Fade", "none" to "None")[it]!! },
        { vm.setPref("ui_msg_enter", it) }) { style -> EntrancePreview(style) }
}

/** Three bubbles that keep replaying the entrance style. */
@Composable
private fun EntrancePreview(style: String) {
    val scheme = MaterialTheme.colorScheme
    var cycle by remember { mutableIntStateOf(0) }
    LaunchedEffect(style) { while (true) { kotlinx.coroutines.delay(2200); cycle++ } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { i ->
            key(cycle, i) {
                var on by remember { mutableStateOf(style == "none") }
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(150L * i); on = true }
                val p by animateFloatAsState(if (on) 1f else 0f, app.bloom.ui.theme.Motion.tween(460, app.bloom.ui.screens.EnterEasing), label = "pv")
                Box(
                    Modifier.graphicsLayer {
                        alpha = if (style == "none") 1f else (p * 1.35f).coerceAtMost(1f)
                        if (style == "rise" || style == "blur") translationY = (1f - p) * 12.dp.toPx()
                        if (style == "blur") {
                            val r = ((1f - p) * (1f - p) * 9.dp.toPx()).toInt()
                            renderEffect = if (r >= 1) app.bloom.ui.screens.blurEffect(r) else null
                        }
                    }.size(width = (70 + i * 18).dp, height = 18.dp).clip(RoundedCornerShape(9.dp)).background(if (i == 1) scheme.tertiary else scheme.primary),
                )
            }
        }
    }
}

@Composable
private fun IconsSection(vm: AppViewModel) {
    StylePager("Living icons", listOf("always", "selected", "off"), Ui.livingIcons,
        { mapOf("always" to "Always morphing", "selected" to "Only the open one", "off" to "Still")[it]!! },
        { vm.setPref("ui_living", it) }) { mode ->
        val living = rememberLivingShape(seed = 7, selected = mode == "selected", animate = mode != "off")
        Box(Modifier.size(84.dp).graphicsLayer { shape = living.shape(); clip = true }.background(MaterialTheme.colorScheme.primary))
    }
    Text("\"Only the open one\" lets the screen rest when nothing moves — smoother and lighter on battery.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

@Composable
private fun BlurSection(vm: AppViewModel) {
    SectionHeader("Blur")
    ToggleCard("Frosted glass", "Blur what's behind the top bar and message box", Ui.glass) { vm.setPref("ui_glass", it) }
    Spacer(Modifier.height(10.dp))
    SliderCard("Blur strength", "Light", "Heavy", (Ui.blurStrength - 4f) / 44f, { f -> vm.setPref("ui_blur", 4f + f * 44f) }, valueText = "${Ui.blurStrength.roundToInt()}dp", enabled = Ui.glass)
    Spacer(Modifier.height(10.dp))
    ToggleCard("Element blur", "Things come into focus from a soft blur as they appear", Ui.elementBlur) { vm.setPref("ui_element_blur", it) }
}

@Composable
private fun FontSection(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var tick by remember { mutableIntStateOf(0) }
    val choices = remember(tick) { Fonts.builtIn + Fonts.imported(ctx) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = runCatching {
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c -> c.moveToFirst(); c.getString(c.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)) }
            }.getOrNull() ?: "font.ttf"
            vm.importFont(uri, name) { tick++ }
        }
    }
    StylePager("Font", choices, choices.firstOrNull { it.id == Ui.fontId } ?: choices[0], { it.label }, { vm.chooseFont(it.id) }) { c ->
        val fam = remember(c.id, vm.fontsReady) { Fonts.family(ctx, c.id) }
        when {
            vm.fontLoading == c.id -> MorphingLoader(size = 40.dp)
            c.id != "system" && fam == null -> Icon(Icons.Rounded.Download, "Tap to download", Modifier.size(40.dp), tint = scheme.onSurfaceVariant)
            else -> Text("Aa", style = TextStyle(fontFamily = fam, fontSize = 64.sp, fontWeight = FontWeight.SemiBold), color = scheme.onSurface)
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { import.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-otf", "application/octet-stream", "*/*")) }) {
            Icon(Icons.Rounded.UploadFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Import font")
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(Ui.fontScope == "all", { vm.setPref("ui_font_scope", "all") }, { Text("Whole app") })
        FilterChip(Ui.fontScope == "messages", { vm.setPref("ui_font_scope", "messages") }, { Text("Messages only") })
    }
    Spacer(Modifier.height(10.dp))
    SliderCard("Weight", "Regular", "Bolder", Ui.fontBold / 200f, { f -> vm.setPref("ui_font_bold", ((f * 200f) / 50f).roundToInt() * 50) }, valueText = "+${Ui.fontBold}", enabled = Ui.fontId != "system")
    Spacer(Modifier.height(10.dp))
    SliderCard("Letter spacing", "Tight", "Airy", (Ui.letterSpacing + 0.05f) / 0.15f, { f -> vm.setPref("ui_letter", ((-0.05f + f * 0.15f) * 100).roundToInt() / 100f) }, valueText = "%.2f".format(Ui.letterSpacing))
}

@Composable
private fun EmojiSection(vm: AppViewModel) {
    StylePager("Emoji", EmojiPacks.all, EmojiPacks.all.first { it.id == Ui.emojiPack }, { it.label }, { vm.setPref("ui_emoji", it.id) }) { p ->
        // preview this pack regardless of the current one
        CompositionLocalProvider(LocalPreviewPack provides p.id) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row { listOf("😀", "😭", "🔥").forEach { PackGlyph(p.id, it) } }
                Row { listOf("💜", "👍", "✨").forEach { PackGlyph(p.id, it) } }
            }
        }
    }
    Text("Samsung: your phone's own emoji are Samsung's — pick System. Images are downloaded once and cached.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

private val LocalPreviewPack = compositionLocalOf { "system" }

@Composable
private fun PackGlyph(pack: String, e: String) {
    val urls = remember(pack, e) { EmojiPacks.urls(pack, e) }
    var i by remember(urls) { mutableIntStateOf(0) }
    Box(Modifier.size(36.dp).padding(3.dp), contentAlignment = Alignment.Center) {
        if (i >= urls.size) Text(e, fontSize = 24.sp) else coil3.compose.AsyncImage(urls[i], e, Modifier.fillMaxSize(), onError = { i++ })
    }
}

@Composable
private fun MusicSection(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var access by remember { mutableStateOf(NowPlayingService.hasAccess(ctx)) }
    LaunchedEffect(Unit) { while (true) { access = NowPlayingService.hasAccess(ctx); kotlinx.coroutines.delay(1500) } }
    SectionHeader("Now playing")
    ToggleCard("Share what I'm listening to", "Only people using Bloom see it (hidden in your Root status)", vm.npOn) { vm.setPref("np_on", it) }
    if (vm.npOn && !access) {
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().clip(SliderCardShape).background(scheme.tertiaryContainer).padding(16.dp)) {
            Text("Bloom needs notification access to see what's playing. It only reads the song title and artist.", style = MaterialTheme.typography.bodyMedium, color = scheme.onTertiaryContainer)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Allow access") }
        }
    }
    if (vm.npOn && access) {
        Spacer(Modifier.height(10.dp))
        val apps = remember(access) { NowPlayingService.mediaApps(ctx) }
        val known = listOf(
            "com.spotify.music" to "Spotify", "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.soundcloud.android" to "SoundCloud", "com.google.android.youtube" to "YouTube",
        )
        val all = (known + apps).distinctBy { it.first }
        Text("Share from (none selected = any app)", style = SliderTitle, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            all.forEach { (pkg, label) -> FilterChip(pkg in vm.npApps, { vm.toggleNpApp(pkg) }, { Text(label) }) }
        }
        Text("Apps appear here once they've played something. Updates at most every 20 seconds.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
    }
}

@Composable
private fun ScreenSection(vm: AppViewModel) {
    SectionHeader("Profiles")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("colour" to "Status bubble: their colour", "glass" to "Frosted", "solid" to "Solid").forEach { (k, l) ->
            FilterChip(Ui.statusBubble == k, { vm.setPref("ui_bubble", k) }, { Text(l) })
        }
    }
    Spacer(Modifier.height(10.dp))
    SectionHeader("Screen")
    ToggleCard("Hide status bar", "Full-screen look (swipe down from the top to peek)", Ui.hideStatusBar) { vm.setPref("ui_hide_status", it) }
    ToggleCard("Root cosmetics", "Show people's avatar frames, card borders, name fonts and name effects", Ui.cosmeticFrames) { vm.setPref("ui_cosmetics", it) }
    ToggleCard("Haptics", "Little vibrations when you send, react, long-press and swipe", Ui.haptics) { vm.setPref("ui_haptics", it) }
}

@Composable
private fun PerformanceSection() {
    val scheme = MaterialTheme.colorScheme
    SectionHeader("Performance")
    Column(Modifier.fillMaxWidth().clip(SliderCardShape).background(scheme.surfaceContainerHigh).padding(horizontal = CardTextInset, vertical = 14.dp)) {
        Text(if (Ui.powerSave) "Battery saver is on" else "Running at your screen's highest refresh rate", style = SliderTitle)
        Text(
            if (Ui.powerSave) "Bloom stepped down: no forced 120 Hz, background shapes and icons hold still, no element blur."
            else "Bloom asks for 120 Hz while it's open. Turn on Android's battery saver and it steps down automatically.",
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
        )
    }
}
