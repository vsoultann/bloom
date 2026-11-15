package app.bloom.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.foundation.border
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.gestures.rememberDraggableState
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.appendInlineContent
import app.bloom.AppViewModel
import app.bloom.core.ChannelKind
import app.bloom.core.Message
import app.bloom.core.User
import app.bloom.ui.components.*
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Frosted glass in looks that have it, a solid bar otherwise. */
@Composable
private fun Modifier.bar(haze: HazeState, solid: Color): Modifier =
    if (LocalLook.current.glass) hazeBlur(HazeInput.Sources(haze), style = glassStyle()).background(solid.copy(alpha = 0.72f))
    else background(solid)

/** Top bar: blur that feathers out downward (no hard-edged dark box). */
@Composable
private fun Modifier.featheredBar(haze: HazeState, solid: Color): Modifier {
    if (!LocalLook.current.glass) return background(solid)
    val style = remember(solid) {
        HazeBlurStyle {
            blurRadius(app.bloom.ui.theme.Ui.blurStrength.dp * 1.5f)
            noiseFactor(0.05f)
            backgroundColor(solid.copy(alpha = 0.8f))
            // fully blurred behind the title, fading to nothing at the bottom edge
            mask(Brush.verticalGradient(0f to Color.Black, 0.72f to Color.Black, 1f to Color.Transparent))
        }
    }
    return hazeBlur(HazeInput.Sources(haze), style = style)
        .background(Brush.verticalGradient(0f to solid.copy(alpha = 0.92f), 0.62f to solid.copy(alpha = 0.85f), 1f to Color.Transparent))
}

@Composable
private fun glassStyle(): HazeBlurStyle {
    val tint = MaterialTheme.colorScheme.surfaceContainer
    return remember(tint) {
        HazeBlurStyle {
            blurRadius(app.bloom.ui.theme.Ui.blurStrength.dp * 1.67f)
            noiseFactor(0.08f)
            backgroundColor(tint.copy(alpha = 0.9f))
        }
    }
}

@Composable
fun ChatPane(vm: AppViewModel, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val haze = rememberHazeState()
    val channel = vm.selectedChannel
    val listState = rememberLazyListState()
    val jumpScope = rememberCoroutineScope()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // the composer grows with the reply bar / attachment tray; messages move up with it
    var composerHeight by remember { mutableStateOf(80.dp) }
    val msgs = vm.messages

    // opening a chat always starts at the newest message
    LaunchedEffect(vm.selectedChannelId) { listState.scrollToItem(0) }
    val firstLoad = vm.messages.isNotEmpty()
    LaunchedEffect(vm.selectedChannelId, firstLoad) { if (firstLoad) listState.scrollToItem(0) }

    LaunchedEffect(composerHeight) { if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0) }

    // Load older history when you get near the top.
    // Re-checks after each page arrives too, so sitting at the top keeps loading further back.
    LaunchedEffect(listState, vm.selectedChannelId) {
        snapshotFlow { Triple(listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0, vm.messages.size, vm.loadingOlder) }
            .collect { (last, size, loading) -> if (!loading && size > 0 && last >= size - 10) vm.loadOlder() }
    }

    // Stick to bottom when a new message arrives and we're already at the bottom.
    LaunchedEffect(msgs.size) {
        if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0)
    }

    // The chat slides over the server/channel layer, so it must be opaque.
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        ChatBackground(vm)
        val isVoice = channel?.kind == ChannelKind.VOICE
        val topPad = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + if (isVoice) 236.dp else 0.dp
        if (channel?.kind == ChannelKind.APP) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Apps, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(16.dp))
                    Text(channel.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        "This channel is a Root App. Apps run inside Root's own client.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = {
                        ctx.packageManager.getLaunchIntentForPackage("com.rootapp.mobile")?.let { ctx.startActivity(it) }
                    }) { Text("Open in Root") }
                }
            }
        } else {
            val reversed by remember { derivedStateOf { msgs.asReversed().toList() } }
            val d = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, d.fontScale * vm.textScale)) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize().hazeSource(haze),
                contentPadding = PaddingValues(
                    top = topPad + 8.dp,
                    // composerHeight already includes the keyboard and nav bar insets
                    bottom = composerHeight + 12.dp,
                ),
            ) {
                items(vm.outgoing.filter { true }.asReversed(), key = { "out-" + it.key }) { o ->
                    Box(Modifier.animateItem(fadeInSpec = Motion.smooth(), placementSpec = Motion.smooth(), fadeOutSpec = Motion.smooth())) { OutgoingMediaRow(vm, o) }
                }
                items(reversed.size, key = { reversed[it].id }) { i ->
                    val m = reversed[i]
                    val prev = reversed.getOrNull(i + 1)
                    // a new day starts here (or this is the very first message)
                    val newDay = if (prev == null) vm.noMoreHistory else !sameDay(prev.ts, m.ts)
                    val grouped = !newDay && prev != null && prev.authorId == m.authorId && m.ts - prev.ts < 300 && m.replyTo == null
                    if (newDay) Column(Modifier.animateItem(fadeInSpec = null, placementSpec = Motion.smooth(), fadeOutSpec = Motion.effects())) {
                        DateDivider(m.ts)
                        MessageRow(
                            vm, m, grouped, reversed, Modifier,
                            onJump = { rid ->
                                val idx = reversed.indexOfFirst { it.id == rid }
                                if (idx >= 0) jumpScope.launch { listState.animateScrollToItem(idx, -200); vm.flash(rid) }
                                else vm.error = "The original message is further back than what's loaded"
                            },
                        )
                    } else MessageRow(
                        vm, m, grouped, reversed,
                        // existing messages glide (no bounce) as new ones arrive; the entrance itself is in MessageBody
                        Modifier.animateItem(fadeInSpec = null, placementSpec = Motion.smooth(), fadeOutSpec = Motion.effects()),
                        onJump = { rid ->
                            val idx = reversed.indexOfFirst { it.id == rid }
                            if (idx >= 0) jumpScope.launch { listState.animateScrollToItem(idx, -200); vm.flash(rid) }
                            else vm.error = "The original message is further back than what's loaded"
                        },
                    )
                }
                item(key = "history") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        when {
                            vm.loadingOlder -> MorphingLoader(size = 32.dp)
                            vm.noMoreHistory -> ChatIntro(vm)
                        }
                    }
                }
            }
            }
        }

        // "jump to latest" when you've scrolled up
        val scrolledUp by remember { derivedStateOf { listState.firstVisibleItemIndex > 6 } }
        androidx.compose.animation.AnimatedVisibility(
            scrolledUp,
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().imePadding().padding(end = 16.dp, bottom = 100.dp),
            enter = scaleIn(Motion.spatialFast()) + fadeIn(), exit = scaleOut() + fadeOut(),
        ) {
            val scope = rememberCoroutineScope()
            SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }, containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Jump to latest")
            }
        }

        TopBar(vm, haze, onMenu, Modifier.align(Alignment.TopCenter))
        if (isVoice && channel != null) VoiceStage(vm, channel, Modifier.align(Alignment.TopCenter).padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp))
        if (channel?.kind == ChannelKind.TEXT || channel?.kind == ChannelKind.ANNOUNCEMENT || isVoice) Composer(
            vm, haze,
            Modifier.align(Alignment.BottomCenter).onSizeChanged { with(density) { composerHeight = it.height.toDp() } },
        )
    }
}

@Composable
private fun TopBar(vm: AppViewModel, haze: HazeState, onMenu: () -> Unit, modifier: Modifier) {
    val c = vm.selectedChannel
    Row(
        modifier
            .fillMaxWidth()
            .featheredBar(haze, MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .height(76.dp)
            .padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMenu) { Icon(Icons.Rounded.Menu, "Channels") }
        Icon(
            when {
                vm.selectedServerId == null && c != null -> Icons.Rounded.AlternateEmail
                else -> when (c?.kind) {
                ChannelKind.ANNOUNCEMENT -> Icons.Rounded.Campaign
                ChannelKind.VOICE -> Icons.Rounded.GraphicEq
                ChannelKind.APP -> Icons.Rounded.Apps
                else -> Icons.Rounded.Tag
                }
            },
            null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(c?.name ?: "", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                c?.topic ?: "", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (vm.selectedServerId == null && c != null) {
            var adding by remember { mutableStateOf(false) }
            IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.PersonAdd, "Add people") }
            if (adding) {
                val dm = vm.dms.firstOrNull { it.id == c.id }
                FriendPicker(vm, "Add people", "Add", exclude = dm?.userIds?.toSet().orEmpty(), onDismiss = { adding = false }) { ids ->
                    // adding people to a 1:1 DM creates a new group with everyone
                    if (dm != null && dm.userIds.size <= 1) vm.createGroup(dm.userIds + ids) else vm.addToGroup(c.id, ids)
                }
            }
        }
        IconButton(onClick = { vm.screen = app.bloom.Screen.Settings }) { Icon(Icons.Rounded.Settings, "Settings") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(vm: AppViewModel, m: Message, grouped: Boolean, all: List<Message>, modifier: Modifier, onJump: (Long) -> Unit = {}) {
    if (m.system != null) { SystemRow(vm, m, modifier); return }
    SwipeToReply(enabled = vm.swipeReply, onReply = { vm.replyingTo = m; vm.editing = null }, modifier = modifier) {
        MessageBody(vm, m, grouped, all, Modifier, onJump)
    }
}

/**
 * Drag a message to the right to reply. A reply icon grows out of a shape that
 * morphs (cookie → circle) and spins as you pull; past the threshold it pops and
 * a haptic tick fires; releasing there starts the reply. Springs back either way.
 */
@Composable
private fun SwipeToReply(enabled: Boolean, onReply: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val threshold = with(density) { 72.dp.toPx() }
    // Drag position is applied synchronously (no coroutine per delta), so a late
    // update can never land after the spring-back and leave the row stuck.
    var dragX by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Box(
        modifier.fillMaxWidth().pointerInput(enabled) {
            if (!enabled) return@pointerInput
            // Claim the gesture only for right-to-left drags; a left-to-right drag is not
            // consumed, so the chat panel underneath still slides open the side menu.
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var claimed = false
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    if (over < 0f) { change.consume(); claimed = true; dragX = -over }
                }
                if (drag != null && claimed) {
                    horizontalDrag(drag.id) { change ->
                        val pull = -change.positionChange().x
                        dragX = (dragX + pull * if (dragX > threshold) 0.35f else 1f).coerceIn(0f, threshold * 1.6f)
                        val nowArmed = dragX >= threshold
                        if (nowArmed && !armed) haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        armed = nowArmed
                        change.consume()
                    }
                    if (armed) onReply()
                    armed = false
                    scope.launch { androidx.compose.animation.core.animate(dragX, 0f, animationSpec = Motion.spatial()) { v, _ -> dragX = v } }
                }
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        val offset = object { val value get() = dragX }
        val p = (offset.value / threshold).coerceIn(0f, 1f)
        // content first; the icon is an overlay that never changes the row's size
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(-offset.value.toInt(), 0) }) { content() }
        if (p > 0.02f) Box(Modifier.matchParentSize(), contentAlignment = Alignment.CenterEnd) {
            val pop by animateFloatAsState(if (armed) 1.15f else 1f, Motion.spatialFast(), label = "replyPop")
            val iconShape = MorphShape(BloomShapes.morph(BloomShapes.cookie, BloomShapes.circle), p, rotation = p * 270f)
            Box(
                Modifier.padding(end = 16.dp).size(36.dp)
                    .graphicsLayer { scaleX = p * pop; scaleY = p * pop; alpha = p; rotationZ = (1 - p) * -180f }
                    .clip(iconShape).background(if (armed) scheme.primary else scheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.AutoMirrored.Rounded.Reply, "Reply", Modifier.size(20.dp), tint = if (armed) scheme.onPrimary else scheme.onSecondaryContainer) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBody(vm: AppViewModel, m: Message, grouped: Boolean, all: List<Message>, modifier: Modifier, onJump: (Long) -> Unit) {
    val author = vm.users[m.authorId]
    val isMe = m.authorId == vm.me?.id
    var menu by remember { mutableStateOf(false) }
    var reactPicker by remember { mutableStateOf(false) }
    if (reactPicker) androidx.compose.ui.window.Dialog(onDismissRequest = { reactPicker = false }) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(top = 12.dp)) {
                Text("React", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
                EmojiPanel(vm) { picked -> vm.react(m, vm.reactionCode(picked)); reactPicker = false }
            }
        }
    }
    var showRaw by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val scheme = MaterialTheme.colorScheme

    // Fresh messages ease in: fade + a short rise (or a soft blur-in). No scaling.
    var appeared by remember { mutableStateOf(System.currentTimeMillis() / 1000 - m.ts > 5 || app.bloom.ui.theme.Ui.messageEnter == "none") }
    LaunchedEffect(Unit) { appeared = true }
    val enter by animateFloatAsState(if (appeared) 1f else 0f, Motion.smooth(), label = "enter")
    val enterStyle = app.bloom.ui.theme.Ui.messageEnter

    val deletedTint by animateFloatAsState(if (m.deleted) 1f else 0f, Motion.effects(), label = "del")
    val flash by animateFloatAsState(if (vm.flashId == m.id) 1f else 0f, Motion.effects(), label = "flash")
    val replyingThis by animateFloatAsState(if (vm.replying.any { it.id == m.id }) 1f else 0f, Motion.effects(), label = "replyHl")
    // mentions you / replies to you / @everyone: tinted with an accent bar
    val forMe = remember(m.content, m.replyAuthor, m.moreReplies, vm.me?.id) { vm.isForMe(m) }

    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter
                if (enterStyle == "rise" || enterStyle == "blur") translationY = (1f - enter) * 14.dp.toPx()
                if (enterStyle == "blur" && enter < 0.99f && app.bloom.ui.theme.Ui.elementBlur && app.bloom.ui.theme.Ui.effectsOn) {
                    val r = (1f - enter) * 10.dp.toPx()
                    renderEffect = android.graphics.RenderEffect.createBlurEffect(r.coerceAtLeast(0.1f), r.coerceAtLeast(0.1f), android.graphics.Shader.TileMode.DECAL).asComposeRenderEffect()
                }
            }
            .background(scheme.errorContainer.copy(alpha = 0.25f * deletedTint))
            .background(scheme.primary.copy(alpha = 0.22f * flash))
            .background(scheme.tertiary.copy(alpha = 0.16f * replyingThis))
            .then(if (forMe) Modifier.background(scheme.primary.copy(alpha = 0.12f)).drawBehind { drawRect(scheme.primary, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) } else Modifier)
            .drawBehind { if (replyingThis > 0.01f) drawRect(scheme.tertiary.copy(alpha = replyingThis), size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height)) }
            .combinedClickable(onClick = {}, onLongClick = { menu = true })
            .padding(start = 12.dp, end = 12.dp, top = if (grouped) 1.dp else if (vm.compact) 6.dp else 10.dp, bottom = if (vm.compact) 1.dp else 2.dp),
    ) {
        ReplyPreview(vm, m, all, vm.compact, onJump)
        Row {
            if (!grouped && author != null) {
                Avatar(author.displayName, author.hue, if (vm.compact) 32.dp else 40.dp, Modifier.clip(androidx.compose.foundation.shape.CircleShape).clickable { vm.openProfile(author.id) }, userId = author.id)
            } else {
                Spacer(Modifier.width(if (vm.compact) 32.dp else 40.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (!grouped && author != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            author.displayName, style = MaterialTheme.typography.titleSmall,
                            color = app.bloom.ui.theme.hueColor(author.hue),
                            modifier = Modifier.clickable { vm.openProfile(author.id) },
                        )
                        if (author.bot) { Spacer(Modifier.width(6.dp)); BotBadge() }
                        if (vm.plugin("platform_indicators")) {
                            Spacer(Modifier.width(4.dp))
                            PlatformIcons(author.platforms, scheme.outline)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(timeLabel(m.ts, vm.plugin("relative_timestamps")), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                }
                val gif = m.content.trim().takeIf { isGifUrl(it) }
                if (gif != null) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val model = if (vm.autoplayGifs) gif else coil3.request.ImageRequest.Builder(ctx).data(gif).decoderFactory(coil3.decode.BitmapFactoryDecoder.Factory()).build()
                    coil3.compose.AsyncImage(
                        model, "GIF",
                        Modifier.padding(top = 4.dp).widthIn(max = 260.dp).heightIn(max = 260.dp).clip(RoundedCornerShape(LocalLook.current.cardRadius * 0.6f))
                            .clickable { vm.viewer = app.bloom.core.Attachment(app.bloom.core.AttachmentKind.IMAGE, "GIF", gif, null, 0, 0) },
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                } else if (m.content.isNotBlank()) {
                    var revealed by remember(m.id) { mutableStateOf(setOf<Int>()) }
                    val rendered = remember(m.content, revealed, scheme.primary) {
                        renderContent(m.content, scheme.primary, scheme.surfaceContainerHighest, revealed, { revealed = revealed + it }, { vm.openMention(it) }, scheme.onSurfaceVariant)
                    }
                    RichText(
                        rendered,
                        style = MaterialTheme.typography.bodyLarge.copy(
                            textDecoration = if (m.deleted) TextDecoration.LineThrough else null,
                        ),
                        color = if (m.deleted) scheme.error else scheme.onSurface,
                    )
                }
                m.attachments.filter { !(gif != null && it.kind == app.bloom.core.AttachmentKind.LINK) }.forEach { AttachmentView(vm, it) }
                if (m.editedTs != null || m.deleted) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (m.deleted) Text("deleted · logged", style = MaterialTheme.typography.labelSmall, color = scheme.error)
                        if (m.editedTs != null) {
                            Text(
                                if (m.editHistory.isNotEmpty()) "(edited · ${m.editHistory.size} earlier)" else "(edited)",
                                style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp).clickable(enabled = m.editHistory.isNotEmpty()) { showHistory = !showHistory },
                            )
                        }
                    }
                }
                AnimatedVisibility(showHistory, enter = expandVertically(Motion.spatial()) + fadeIn(), exit = shrinkVertically(Motion.spatial()) + fadeOut()) {
                    Column(Modifier.padding(top = 4.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHigh).padding(8.dp)) {
                        m.editHistory.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, textDecoration = TextDecoration.LineThrough)
                        }
                    }
                }
                AnimatedVisibility(showRaw, enter = expandVertically(Motion.spatial()) + fadeIn(), exit = shrinkVertically(Motion.spatial()) + fadeOut()) {
                    Text(
                        m.content, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHighest).padding(8.dp),
                    )
                }
                if (m.reactions.isNotEmpty()) Reactions(vm, m)
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("🔥", "😂", "💜", "👀", "✨").forEach { e ->
                    Text(e, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clip(CircleShape).clickable { vm.react(m, e); menu = false }.padding(8.dp))
                }
                // any emoji (incl. custom) from the full panel
                IconButton(onClick = { menu = false; reactPicker = true }) { Icon(Icons.Rounded.AddReaction, "More reactions") }
            }
            DropdownMenuItem({ Text("Reply") }, { vm.replyingTo = m; vm.editing = null; menu = false }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Reply, null) })
            if (isMe && !m.deleted) {
                DropdownMenuItem({ Text("Edit") }, { vm.editing = m; vm.replyingTo = null; menu = false }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
            }
            DropdownMenuItem({ Text("Copy text") }, { clipboard.setText(AnnotatedString(m.content)); menu = false }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
            if (vm.plugin("view_raw")) {
                DropdownMenuItem({ Text(if (showRaw) "Hide raw" else "View raw") }, { showRaw = !showRaw; menu = false }, leadingIcon = { Icon(Icons.Rounded.Code, null) })
            }
            if (isMe) {
                DropdownMenuItem({ Text("Delete", color = scheme.error) }, { vm.delete(m); menu = false }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = scheme.error) })
            }
        }
    }
}

@Composable
private fun Reactions(vm: AppViewModel, m: Message) {
    val me = vm.me?.id
    val scheme = MaterialTheme.colorScheme
    // keep removed reactions around until their exit animation has finished
    val current = m.reactions.associateBy { it.emoji }
    val shown = remember(m.id) { mutableStateListOf<String>().apply { addAll(m.reactions.map { it.emoji }) } }
    val last = remember(m.id) { mutableStateMapOf<String, app.bloom.core.Reaction>() }
    current.forEach { (k, r) -> last[k] = r; if (k !in shown) shown.add(k) }
    FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        shown.toList().forEach { k ->
            val r0 = current[k] ?: last[k]
            if (r0 != null) key(k) {
                val r = r0
                val state = remember { androidx.compose.animation.core.MutableTransitionState(m.reactions.isNotEmpty() && System.currentTimeMillis() / 1000 - m.ts > 5 && k in current) }
                state.targetState = k in current
                if (state.isIdle && !state.currentState && k !in current) LaunchedEffect(Unit) { shown.remove(k); last.remove(k) }
                AnimatedVisibility(
                    state,
                    enter = fadeIn(Motion.smooth()) + expandHorizontally(Motion.smooth(), expandFrom = Alignment.Start, clip = false) + slideInVertically(Motion.smooth()) { it / 3 },
                    exit = fadeOut(Motion.smooth()) + shrinkHorizontally(Motion.smooth(), shrinkTowards = Alignment.Start),
                ) {
                    val mine = me in r.userIds
                    val bg by androidx.compose.animation.animateColorAsState(if (mine) scheme.primaryContainer else scheme.surfaceContainerHigh, Motion.smooth(), label = "rx")
                    val border by androidx.compose.animation.animateColorAsState(if (mine) scheme.primary.copy(alpha = 0.6f) else Color.Transparent, Motion.smooth(), label = "rxb")
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(bg)
                            .border(1.dp, border, RoundedCornerShape(50))
                            .clickable { vm.react(m, r.emoji) }.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val custom = vm.customEmoji.firstOrNull { it.id == r.emoji }
                        when {
                            custom != null -> coil3.compose.AsyncImage(custom.url, custom.shortcode, Modifier.size(20.dp))
                            r.emoji.length >= 20 && r.emoji.all { it.isLetterOrDigit() || it == '-' || it == '_' } -> Icon(Icons.Rounded.EmojiEmotions, null, Modifier.size(18.dp))
                            else -> if (app.bloom.ui.theme.Ui.emojiPack == "system") Text(r.emoji) else EmojiGlyph(r.emoji, Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(5.dp))
                        if (vm.plugin("who_reacted")) {
                            Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                r.userIds.take(3).mapNotNull { vm.users[it] }.forEach { u -> Avatar(u.displayName, u.hue, 18.dp, userId = u.id) }
                            }
                            Spacer(Modifier.width(4.dp))
                        }
                        // the count rolls up or down
                        AnimatedContent(r.userIds.size, transitionSpec = {
                            val up = targetState > initialState
                            (slideInVertically(Motion.smooth()) { if (up) it else -it } + fadeIn(Motion.smooth())) togetherWith
                                (slideOutVertically(Motion.smooth()) { if (up) -it else it } + fadeOut(Motion.smooth()))
                        }, label = "rxCount") { n -> Text("$n", style = MaterialTheme.typography.labelMedium, color = if (mine) scheme.onPrimaryContainer else scheme.onSurface) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(vm: AppViewModel, haze: HazeState, modifier: Modifier) {
    var text by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf(ComposerPanel.NONE) }
    BackHandler(panel != ComposerPanel.NONE) { panel = ComposerPanel.NONE }
    val scheme = MaterialTheme.colorScheme
    val editing = vm.editing
    val replying = vm.replyingTo
    LaunchedEffect(editing) { if (editing != null) text = editing.content }

    // Live preview of what plugins will turn the text into (debounced).
    var preview by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        delay(250)
        preview = if (text.isBlank() || text.any { vm.customForChar(it) != null }) "" else vm.preview(text).takeIf { it != text } ?: ""
    }

    val typerIds = vm.typing[vm.selectedChannelId].orEmpty().keys.toList()
    val typers = typerIds.mapNotNull { vm.users[it]?.displayName }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(if (LocalLook.current.look == app.bloom.ui.theme.Look.DISCORD) 24.dp else 28.dp))
            .bar(haze, MaterialTheme.colorScheme.surfaceContainerHigh)
            .animateContentSize(Motion.spatial()),
    ) {
        AnimatedVisibility(typers.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Box(Modifier.padding(start = 18.dp, top = 8.dp)) { TypingIndicator(vm, typerIds) }
        }
        val banner = when {
            editing != null -> "Editing message"
            replying != null -> "Replying to " + vm.replying.map { vm.users[it.authorId]?.displayName ?: "?" }.distinct().joinToString(", ") +
                (if (vm.replying.size > 1) " (${vm.replying.size} messages)" else "")
            else -> null
        }
        AnimatedVisibility(banner != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(banner ?: "", style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.editing = null; vm.replyingTo = null; text = "" }, Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Close, "Cancel", Modifier.size(18.dp))
                }
            }
        }
        AnimatedVisibility(preview.isNotEmpty(), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.padding(start = 18.dp, end = 18.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(14.dp), tint = scheme.tertiary)
                Spacer(Modifier.width(6.dp))
                Text(renderContent(preview, scheme.tertiary, scheme.surfaceContainerHighest), style = MaterialTheme.typography.bodySmall, color = scheme.tertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        PendingTray(vm)
        val focus = androidx.compose.ui.platform.LocalFocusManager.current
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        val fieldFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        fun toggle(p: ComposerPanel) {
            panel = if (panel == p) ComposerPanel.NONE else p
            if (panel != ComposerPanel.NONE) { keyboard?.hide(); focus.clearFocus() }
            else { runCatching { fieldFocus.requestFocus() }; keyboard?.show() } // back to typing
        }
        // ---- @mentions: suggestions while typing "@…" ----
        val picked = remember { mutableStateMapOf<String, String>() } // "@Name" → token for the core
        val mentionQuery = remember(text) { Regex("(?:^|\\s)@([^\\s@]{0,32})$").find(text)?.groupValues?.get(1) }
        LaunchedEffect(vm.selectedServerId) { vm.selectedServerId?.let { vm.loadRoles(it) } }
        fun insertMention(label: String, token: String) {
            text = text.dropLast(mentionQuery?.length?.plus(1) ?: 0) + "@$label "
            picked["@$label"] = token
        }
        AnimatedVisibility(
            mentionQuery != null,
            enter = fadeIn(Motion.smooth()) + expandVertically(Motion.smooth()),
            exit = fadeOut(Motion.smooth()) + shrinkVertically(Motion.smooth()),
        ) {
            val q = mentionQuery.orEmpty()
            val people = remember(q, vm.messages.size) { vm.mentionCandidates(q) }
            val serverRoles = vm.selectedServerId?.let { vm.roles[it] }.orEmpty()
            val special = if (vm.selectedServerId != null) listOf(Triple("All", "All", "everyone in the server"), Triple("Here", "Here", "everyone online")) else emptyList()
            val roleRows = (special.map { Triple(it.first, it.second, it.third) } + serverRoles.map { Triple(it.first, it.second, "role") })
                .filter { q.isEmpty() || it.second.contains(q, true) }.take(6)
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(horizontal = 8.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerHigh),
            ) {
                items(people, key = { "u${it.id}" }) { u ->
                    Row(
                        Modifier.fillMaxWidth().clickable { insertMention(u.name, "⟦@u:${u.id}⟧") }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(u.displayName, u.hue, 30.dp, userId = u.id, status = u.status)
                        Spacer(Modifier.width(10.dp))
                        Text(u.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (u.displayName != u.name) Text("  @${u.name}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        if (u.bot) { Spacer(Modifier.width(6.dp)); BotBadge() }
                    }
                }
                items(roleRows, key = { "r${it.first}" }) { (target, name, sub) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { insertMention(name, "⟦@r:$target|$name⟧") }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(scheme.secondaryContainer), contentAlignment = Alignment.Center) {
                            Icon(if (sub == "role") Icons.Rounded.Shield else Icons.Rounded.Campaign, null, Modifier.size(16.dp), tint = scheme.onSecondaryContainer)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text("@$name", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.width(8.dp))
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.Bottom) {
            // "+" turns into × while the attach sheet is open
            val plusSpin by animateFloatAsState(if (panel == ComposerPanel.ATTACH) 45f else 0f, Motion.spatial(), label = "plus")
            IconButton(onClick = { toggle(ComposerPanel.ATTACH) }, Modifier.padding(bottom = 2.dp).size(44.dp).clip(CircleShape).background(scheme.surfaceContainerHighest)) {
                Icon(Icons.Rounded.Add, "Attach", Modifier.graphicsLayer { rotationZ = plusSpin }, tint = scheme.onSurface)
            }
            // Custom emoji are placeholder characters in the field; when present, the field's
            // own text is drawn invisibly and an identical overlay draws the emoji images.
            val hasCustom = text.any { vm.customForChar(it) != null }
            val fieldStyle = MaterialTheme.typography.bodyLarge.copy(color = if (hasCustom) Color.Transparent else scheme.onSurface)
            BasicTextField(
                value = text,
                onValueChange = { text = it; vm.onTyping() },
                textStyle = fieldStyle,
                cursorBrush = SolidColor(scheme.primary),
                maxLines = 6,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp)
                    .focusRequester(fieldFocus)
                    .onFocusChanged { if (it.isFocused) panel = ComposerPanel.NONE },
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Message ${if (vm.selectedServerId == null) "@" else "#"}${vm.selectedChannel?.name ?: ""}", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (hasCustom) {
                        val annotated = buildAnnotatedString {
                            text.forEach { c -> if (vm.customForChar(c) != null) appendInlineContent("pua:${c.code}", c.toString()) else append(c) }
                        }
                        val inline = text.filter { vm.customForChar(it) != null }.toSet().associate { c ->
                            "pua:${c.code}" to androidx.compose.foundation.text.InlineTextContent(
                                androidx.compose.ui.text.Placeholder(1.25.em, 1.25.em, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
                            ) { coil3.compose.AsyncImage(vm.customForChar(c)?.url, null, Modifier.fillMaxSize()) }
                        }
                        Text(annotated, inlineContent = inline, style = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface), maxLines = 6)
                    }
                    inner()
                },
            )
            IconButton(onClick = { toggle(ComposerPanel.EMOJI) }, Modifier.padding(bottom = 2.dp).size(44.dp)) {
                Icon(if (panel == ComposerPanel.EMOJI) Icons.Rounded.Keyboard else Icons.Rounded.EmojiEmotions, "Emoji", tint = scheme.onSurfaceVariant)
            }
            val canSend = text.isNotBlank() || vm.pending.isNotEmpty()
            val sendScale by animateFloatAsState(if (canSend) 1f else 0.85f, Motion.spatialFast(), label = "send")
            val progress by animateFloatAsState(if (canSend) 1f else 0f, Motion.spatial(), label = "sendShape")
            val shape = if (LocalLook.current.iconMode == app.bloom.ui.theme.IconMode.LIVING)
                MorphShape(BloomShapes.morph(BloomShapes.cookie, BloomShapes.circle), progress.coerceIn(0f, 1f))
            else androidx.compose.foundation.shape.CircleShape
            SendOrRecordButton(vm, canSend, onSend = {
                // picked @mentions become tokens the core turns into Root mention links
                var out = text
                picked.entries.sortedByDescending { it.key.length }.forEach { (label, token) -> out = out.replace(label, token) }
                vm.send(out); text = ""; picked.clear(); panel = ComposerPanel.NONE
            }) {
                Box(
                    Modifier
                        .size(48.dp)
                        .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                        .softGlow(shape, scheme.primary, 12.dp, 0.5f * progress.coerceIn(0f, 1f))
                        .clip(shape)
                        .background(if (canSend) scheme.primary else scheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        vm.uploading -> MorphingLoader(size = 24.dp, color = scheme.onPrimary)
                        editing != null -> Icon(Icons.Rounded.Check, "Save", tint = scheme.onPrimary)
                        canSend -> Icon(Icons.AutoMirrored.Rounded.Send, "Send", tint = scheme.onPrimary)
                        else -> Icon(Icons.Rounded.Mic, "Hold to record a voice message", tint = scheme.onSecondaryContainer)
                    }
                }
            }
        }
        AnimatedContent(
            panel,
            // panels get a solid background so chat text never shows through the buttons
            modifier = Modifier.background(if (panel == ComposerPanel.NONE) Color.Transparent else scheme.surfaceContainerHigh),
            transitionSpec = { (expandVertically(Motion.spatial()) + fadeIn()) togetherWith (shrinkVertically(Motion.spatial()) + fadeOut()) },
            label = "panel",
        ) { p ->
            when (p) {
                ComposerPanel.ATTACH -> AttachSheet(vm, onGifs = { panel = ComposerPanel.GIFS }, onClose = { panel = ComposerPanel.NONE })
                ComposerPanel.GIFS -> GifPanel(vm, onSent = { panel = ComposerPanel.NONE })
                ComposerPanel.EMOJI -> EmojiPanel(vm) { e -> text += e }
                ComposerPanel.NONE -> Spacer(Modifier.height(0.dp))
            }
        }
    }
}

/** Time formatting that follows the 12/24-hour setting. */
object Clock {
    @Volatile var use12h = false
    private val c24 = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val d24 = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    private val c12 = SimpleDateFormat("h:mm a", Locale.getDefault())
    private val d12 = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())
    val clock get() = if (use12h) c12 else c24
    val dateClock get() = if (use12h) d12 else d24
}
private val clock get() = Clock.clock
private val dateClock get() = Clock.dateClock

fun timeLabel(ts: Long, relative: Boolean): String {
    val now = System.currentTimeMillis() / 1000
    val d = now - ts
    if (relative) return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60}m ago"
        d < 86400 -> "${d / 3600}h ago"
        else -> "${d / 86400}d ago"
    }
    return (if (d < 86400) clock else dateClock).format(Date(ts * 1000))
}

/**
 * Markdown as Root/Discord write it: ```code```, `inline`, **bold**, *italic* / _italic_,
 * __underline__, ~~strike~~, ||spoiler|| (tap to reveal), # headings, > quotes, -# subtext,
 * links, <t:UNIX:t> timestamps, @mentions (rounded pills, tap → profile) and custom emoji.
 * Ranges tagged "pill" get a soft rounded background from [RichText].
 */
fun renderContent(
    src: String, accent: Color, codeBg: Color,
    revealed: Set<Int> = emptySet(), onSpoiler: ((Int) -> Unit)? = null, onMention: ((String) -> Unit)? = null,
    muted: Color = accent.copy(alpha = 0.6f),
): AnnotatedString = buildAnnotatedString {
    var spoilerIdx = 0
    val pack = app.bloom.ui.theme.Ui.emojiPack != "system"
    fun inline(text: String) {
        var i = 0
        fun at(tok: String) = text.startsWith(tok, i)
        fun wrap(tok: String, style: SpanStyle): Boolean {
            val end = text.indexOf(tok, i + tok.length)
            if (end <= i + tok.length - 1 || end < 0) return false
            withStyle(style) { inline(text.substring(i + tok.length, end)) }
            i = end + tok.length
            return true
        }
        while (i < text.length) {
            when {
                at("`") -> {
                    val end = text.indexOf('`', i + 1)
                    if (end < 0) { append('`'); i++ } else {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(text.substring(i + 1, end)) }
                        i = end + 1
                    }
                }
                at("||") -> {
                    val end = text.indexOf("||", i + 2)
                    if (end < 0) { append("||"); i += 2 } else {
                        val idx = spoilerIdx++
                        val body = text.substring(i + 2, end)
                        if (idx in revealed) withStyle(SpanStyle(background = codeBg)) { inline(body) }
                        else {
                            // hidden: text drawn in the box colour, tap to reveal
                            val link = LinkAnnotation.Clickable("spoiler:$idx", linkInteractionListener = { onSpoiler?.invoke(idx) })
                            withLink(link) { withStyle(SpanStyle(color = codeBg, background = codeBg)) { append(body.replace(Regex("⟦[^⟧]*⟧"), "•••")) } }
                        }
                        i = end + 2
                    }
                }
                at("**") && wrap("**", SpanStyle(fontWeight = FontWeight.Bold)) -> {}
                at("__") && wrap("__", SpanStyle(textDecoration = TextDecoration.Underline)) -> {}
                at("~~") && wrap("~~", SpanStyle(textDecoration = TextDecoration.LineThrough)) -> {}
                (at("*") || at("_")) && i + 1 < text.length && text[i + 1] != ' ' && wrap(text[i].toString(), SpanStyle(fontStyle = FontStyle.Italic)) -> {}
                at("⟦m:") || at("⟦c:") -> {
                    val end = text.indexOf('⟧', i)
                    if (end < 0) { append(text[i]); i++ } else {
                        val isChannel = text[i + 1] == 'c'
                        val name = text.substring(i + 3, end)
                        val label = if (isChannel) "#$name" else "@$name"
                        val start = length
                        val style = SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)
                        if (!isChannel && onMention != null) {
                            withLink(LinkAnnotation.Clickable("mention:$name", TextLinkStyles(style), linkInteractionListener = { onMention(name) })) { append(" $label ") }
                        } else withStyle(style) { append(" $label ") }
                        addStringAnnotation("pill", "", start, length)
                        i = end + 1
                    }
                }
                at("⟦e:") -> {
                    val end = text.indexOf('⟧', i)
                    if (end < 0) { append(text[i]); i++ } else {
                        val body = text.substring(i + 3, end)
                        appendInlineContent("emoji:" + body.substringBefore('|'), ":" + body.substringAfter('|', "emoji") + ":")
                        i = end + 1
                    }
                }
                at("<t:") -> {
                    val end = text.indexOf('>', i)
                    val ts = if (end > 0) text.substring(i + 3, end).substringBefore(':').toLongOrNull() else null
                    if (ts == null) { append("<"); i++ } else {
                        val start = length
                        withStyle(SpanStyle(color = accent, fontWeight = FontWeight.SemiBold)) { append(" ${clock.format(Date(ts * 1000))} ") }
                        addStringAnnotation("pill", "", start, length)
                        i = end + 1
                    }
                }
                at("http://") || at("https://") -> {
                    var end = i
                    while (end < text.length && !text[end].isWhitespace()) end++
                    val url = text.substring(i, end)
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = accent, textDecoration = TextDecoration.Underline)))) { append(url) }
                    i = end
                }
                // emoji pack: draw unicode emoji as the chosen pack's artwork
                pack && text[i].code > 0xFF && app.bloom.ui.custom.EmojiPacks.emojiAt(text, i).let { it > 0 } -> {
                    val n = app.bloom.ui.custom.EmojiPacks.emojiAt(text, i)
                    val e = text.substring(i, i + n)
                    appendInlineContent("uemoji:$e", e)
                    i += n
                }
                else -> { append(text[i]); i++ }
            }
        }
    }

    // block level: code fences, then line by line (headings, quotes, subtext)
    var i = 0
    while (i < src.length) {
        if (src.startsWith("```", i)) {
            val end = src.indexOf("```", i + 3)
            if (end < 0) { inline(src.substring(i)); break }
            val body = src.substring(i + 3, end).let { b -> if ('\n' in b && b.substringBefore('\n').matches(Regex("[A-Za-z0-9+#-]{0,16}"))) b.substringAfter('\n') else b }
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)) { append(body.trimEnd()) }
            i = end + 3
            continue
        }
        val nl = src.indexOf('\n', i).let { if (it < 0) src.length else it }
        val nextFence = src.indexOf("```", i).let { if (it in i until nl) it else -1 }
        val lineEnd = if (nextFence >= 0) nextFence else nl
        val line = src.substring(i, lineEnd)
        when {
            line.startsWith("### ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.1.em)) { inline(line.removePrefix("### ")) }
            line.startsWith("## ") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.25.em)) { inline(line.removePrefix("## ")) }
            line.startsWith("# ") -> withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold, fontSize = 1.45.em)) { inline(line.removePrefix("# ")) }
            line.startsWith("-# ") -> withStyle(SpanStyle(fontSize = 0.8.em, color = muted)) { inline(line.removePrefix("-# ")) }
            line.startsWith("> ") || line == ">" -> {
                withStyle(SpanStyle(color = muted, fontWeight = FontWeight.Black)) { append("▎ ") }
                inline(line.removePrefix(">").removePrefix(" "))
            }
            else -> inline(line)
        }
        i = lineEnd
        if (i < src.length && src[i] == '\n') { append('\n'); i++ }
    }
}

/**
 * Text that draws soft, fully rounded backgrounds behind "pill" ranges (mentions,
 * timestamps), wrapping across lines correctly.
 */
@Composable
fun RichText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    pill: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    val pills = remember(text) { text.getStringAnnotations("pill", 0, text.length) }
    // Customize → Font → "Messages only"
    val ui = app.bloom.ui.theme.Ui
    val style = if (ui.fontScope == "messages" && ui.fontFamily != null) style.copy(fontFamily = ui.fontFamily) else style
    Text(
        text,
        modifier.drawBehind {
            val l = layout ?: return@drawBehind
            pills.forEach { r ->
                val end = r.end.coerceAtMost(l.layoutInput.text.length)
                if (r.start >= end) return@forEach
                val first = l.getLineForOffset(r.start)
                val last = l.getLineForOffset((end - 1).coerceAtLeast(r.start))
                for (line in first..last) {
                    val s = if (line == first) l.getHorizontalPosition(r.start, true) else l.getLineLeft(line)
                    val e = if (line == last) l.getHorizontalPosition(end, true) else l.getLineRight(line)
                    val top = l.getLineTop(line) + 1.dp.toPx()
                    val bottom = l.getLineBottom(line) - 1.dp.toPx()
                    val h = bottom - top
                    drawRoundRect(pill, androidx.compose.ui.geometry.Offset(minOf(s, e), top), androidx.compose.ui.geometry.Size(kotlin.math.abs(e - s), h), androidx.compose.ui.geometry.CornerRadius(h / 2, h / 2))
                }
            }
        },
        style = style, color = color, maxLines = maxLines, overflow = overflow,
        inlineContent = emojiInline(text),
        onTextLayout = { layout = it },
    )
}

private fun sameDay(a: Long, b: Long): Boolean {
    val z = java.util.TimeZone.getDefault()
    val da = (a * 1000 + z.getOffset(a * 1000)) / 86_400_000
    val db = (b * 1000 + z.getOffset(b * 1000)) / 86_400_000
    return da == db
}

/** ──── November 12 ──── between days (year shown when it isn't this year). */
@Composable
fun DateDivider(ts: Long) {
    val scheme = MaterialTheme.colorScheme
    val label = remember(ts) {
        val now = java.util.Calendar.getInstance()
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        val pattern = if (c.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)) "MMMM d" else "MMMM d, yyyy"
        val day = java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(ts * 1000))
        val n = java.util.Calendar.getInstance()
        val y = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
        fun same(x: java.util.Calendar) = x.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) && x.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
        when { same(n) -> "Today · $day"; same(y) -> "Yesterday · $day"; else -> day }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(scheme.outlineVariant))
        Text(label, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Box(Modifier.weight(1f).height(1.dp).background(scheme.outlineVariant))
    }
}

/** Shown at the very top of a chat: who/what this is, like Root's start-of-chat header. */
@Composable
fun ChatIntro(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    val ch = vm.selectedChannel
    val dm = vm.dms.firstOrNull { it.id == vm.selectedChannelId }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp)) {
        if (dm != null) {
            val people = dm.userIds.mapNotNull { vm.users[it] }
            if (people.size == 1) {
                val u = people[0]
                Avatar(u.displayName, u.hue, 80.dp, Modifier.clip(CircleShape).clickable { vm.openProfile(u.id) }, userId = u.id)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.displayName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (u.bot) { Spacer(Modifier.width(8.dp)); BotBadge() }
                }
                Text("@${u.name}", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text("This is the beginning of your direct message history with @${u.displayName}.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy((-14).dp)) { people.take(4).forEach { u -> Avatar(u.displayName, u.hue, 64.dp, userId = u.id) } }
                Spacer(Modifier.height(12.dp))
                Text(vm.dmName(dm), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Welcome to the beginning of this group.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        } else if (ch != null) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(LocalLook.current.cardRadius * 0.7f)).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                if (ch.iconUrl != null) coil3.compose.AsyncImage(ch.iconUrl, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Icon(Icons.Rounded.Tag, null, Modifier.size(40.dp), tint = scheme.onSurface)
            }
            Spacer(Modifier.height(12.dp))
            Text("Welcome to #${ch.name}!", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("This is the start of the #${ch.name} channel.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            if (ch.topic.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                RichText(renderContent(ch.topic, scheme.primary, scheme.surfaceContainerHighest), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
            }
        }
    }
}

/** Strips Bloom markers and markdown for one-line previews (replies, notifications). */
fun plainText(s: String): String = s
    .replace(Regex("\\|\\|([^|]*)\\|\\|"), "•••")
    .replace(Regex("(\\*\\*|__|~~)(.+?)\\1"), "$2")
    .replace(Regex("^(#{1,3}|-#|>) ", RegexOption.MULTILINE), "")
    .replace(Regex("⟦[mc]:([^⟧]*)⟧")) { "@" + it.groupValues[1] }
    .replace(Regex("⟦e:[^|⟧]*\\|?([^⟧]*)⟧")) { ":" + it.groupValues[1] + ":" }

/** One emoji in the chosen pack's artwork (falls back to the phone's emoji). */
@Composable
fun EmojiGlyph(e: String, modifier: Modifier = Modifier, fallbackStyle: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default) {
    val pack = app.bloom.ui.theme.Ui.emojiPack
    val urls = remember(e, pack) { app.bloom.ui.custom.EmojiPacks.urls(pack, e) }
    var idx by remember(urls) { mutableIntStateOf(0) }
    if (idx >= urls.size) Box(modifier, contentAlignment = Alignment.Center) { Text(e, style = fallbackStyle) }
    else coil3.compose.AsyncImage(urls[idx], e, modifier, onError = { idx++ })
}

/** Inline images for custom emoji placed by [renderContent]. */
@Composable
fun emojiInline(text: AnnotatedString): Map<String, androidx.compose.foundation.text.InlineTextContent> {
    val keys = text.getStringAnnotations("androidx.compose.foundation.text.inlineContent", 0, text.length).map { it.item }.filter { it.startsWith("emoji:") || it.startsWith("uemoji:") }.toSet()
    return keys.associateWith { key ->
        if (key.startsWith("uemoji:")) androidx.compose.foundation.text.InlineTextContent(
            androidx.compose.ui.text.Placeholder(1.2.em, 1.2.em, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
        ) { EmojiGlyph(key.removePrefix("uemoji:"), Modifier.fillMaxSize()) }
        else androidx.compose.foundation.text.InlineTextContent(
            androidx.compose.ui.text.Placeholder(1.35.em, 1.35.em, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
        ) { coil3.compose.AsyncImage(key.removePrefix("emoji:"), null, Modifier.fillMaxSize()) }
    }
}

/** System events ("X joined the community") as a compact centred row. */
@Composable
private fun SystemRow(vm: AppViewModel, m: Message, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val who = vm.users[m.authorId]?.displayName
    val text = m.system ?: ""
    // events that already name a user ("Ann joined") don't need the author prefix
    val line = if (who == null || text.firstOrNull()?.isUpperCase() == true || text.contains(" joined") || text.contains(" left") || text.contains(" was ")) text else "$who $text"
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(
            when {
                text.contains("joined") || text.contains("added") -> Icons.Rounded.Login
                text.contains("left") -> Icons.Rounded.Logout
                text.contains("pinned") -> Icons.Rounded.PushPin
                text.contains("call") -> Icons.Rounded.Call
                else -> Icons.Rounded.Info
            },
            null, Modifier.size(16.dp), tint = scheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(line, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(timeLabel(m.ts, vm.plugin("relative_timestamps")), style = MaterialTheme.typography.labelSmall, color = scheme.outline)
    }
}

/** Attachment: inline image, link preview card, or a file chip that opens externally. */
@Composable
private fun AttachmentView(vm: AppViewModel, a: app.bloom.core.Attachment) {
    val scheme = MaterialTheme.colorScheme
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    val radius = LocalLook.current.cardRadius * 0.6f
    when (a.kind) {
        app.bloom.core.AttachmentKind.IMAGE -> if (a.url != null) {
            // The decoded image's own shape wins: Root's stored size ignores photo rotation
            // (EXIF), which made fresh camera photos come out sideways-shaped.
            val key = a.url.substringBefore('?')
            val measured = vm.mediaRatios[key]
            val ratio = measured ?: if (a.width > 0 && a.height > 0) a.width.toFloat() / a.height else null
            coil3.compose.AsyncImage(
                a.url, a.name,
                // unknown shape: no placeholder box at all, the image appears at its own shape
                (if (ratio != null) Modifier.padding(top = 6.dp).mediaRatio(ratio) else Modifier.padding(top = 6.dp).widthIn(max = 280.dp).heightIn(max = 340.dp))
                    .clip(RoundedCornerShape(radius)).background(scheme.surfaceContainerHigh)
                    .clickable { vm.viewer = a },
                contentScale = if (ratio != null) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                onSuccess = { st ->
                    val img = st.result.image
                    if (img.width > 0 && img.height > 0) {
                        val r = img.width.toFloat() / img.height
                        if (measured == null || kotlin.math.abs(measured - r) > 0.03f) vm.mediaRatios[key] = r
                    }
                },
            )
        }
        app.bloom.core.AttachmentKind.VIDEO -> if (a.url != null) {
            val key = a.url.substringBefore('?')
            val measured = vm.mediaRatios[key]
            val ratio = measured ?: if (a.width > 0 && a.height > 0) a.width.toFloat() / a.height else null
            Box(
                (if (ratio != null) Modifier.padding(top = 6.dp).mediaRatio(ratio) else Modifier.padding(top = 6.dp).widthIn(max = 280.dp).heightIn(max = 340.dp))
                    .clip(RoundedCornerShape(radius))
                    .background(scheme.surfaceContainerHighest).clickable { vm.viewer = a },
                contentAlignment = Alignment.Center,
            ) {
                // Root sends a thumbnail for videos; its decoded shape is the video's shape
                coil3.compose.AsyncImage(
                    a.preview ?: a.url, a.name,
                    if (ratio != null) Modifier.fillMaxSize() else Modifier,
                    contentScale = if (ratio != null) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                    onSuccess = { st ->
                        val img = st.result.image
                        if (measured == null && img.width > 0 && img.height > 0) vm.mediaRatios[key] = img.width.toFloat() / img.height
                    },
                )
                Icon(Icons.Rounded.PlayCircle, "Play", Modifier.size(52.dp), tint = Color.White)
            }
        }
        app.bloom.core.AttachmentKind.AUDIO -> if (a.url != null) AudioAttachment(a.url, a.name)
        app.bloom.core.AttachmentKind.LINK -> Column(
            Modifier.padding(top = 6.dp).widthIn(max = 320.dp).clip(RoundedCornerShape(radius))
                .background(scheme.surfaceContainerHigh).clickable { a.href?.let { runCatching { uri.openUri(it) } } },
        ) {
            if (a.url != null) coil3.compose.AsyncImage(a.url, null, Modifier.fillMaxWidth().heightIn(max = 180.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Text(
                a.href?.removePrefix("https://")?.removePrefix("http://")?.substringBefore('/') ?: a.name,
                style = MaterialTheme.typography.labelLarge, color = scheme.primary,
                modifier = Modifier.padding(10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        else -> Row(
            Modifier.padding(top = 6.dp).clip(RoundedCornerShape(radius)).background(scheme.surfaceContainerHigh)
                .clickable(enabled = a.url != null) { a.url?.let { runCatching { uri.openUri(it) } } }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (a.kind) {
                    app.bloom.core.AttachmentKind.VIDEO -> Icons.Rounded.Movie
                    app.bloom.core.AttachmentKind.AUDIO -> Icons.Rounded.Audiotrack
                    else -> Icons.Rounded.Description
                },
                null, tint = scheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(a.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
    }
}

/** A message that is just a GIF/animated image link renders as the image itself. */
fun isGifUrl(s: String): Boolean {
    if (!(s.startsWith("https://") || s.startsWith("http://")) || s.any { it.isWhitespace() }) return false
    val path = s.substringBefore('?').lowercase()
    return path.endsWith(".gif") || path.endsWith(".webp") || "klipy.com" in path || "tenor.com" in path && path.endsWith(".gif")
}


/** Small "BOT" tag shown next to bot names. */
@Composable
fun BotBadge() {
    val scheme = MaterialTheme.colorScheme
    // unmistakable: tertiary-coloured pill with a robot icon
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(scheme.tertiary).padding(start = 5.dp, end = 7.dp, top = 1.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.SmartToy, null, Modifier.size(12.dp), tint = scheme.onTertiary)
        Spacer(Modifier.width(3.dp))
        Text("BOT", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = scheme.onTertiary)
    }
}

/** Media keeps its real shape: width-limited, height-limited, never forced to 16:9. */
private fun Modifier.mediaRatio(r: Float): Modifier {
    val ratio = r.coerceIn(0.4f, 3f)
    return this.widthIn(max = 280.dp).heightIn(max = 340.dp).aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
}

private fun Modifier.mediaSize(w: Int, h: Int, fallback: Float = 4f / 3f): Modifier {
    val ratio = if (w > 0 && h > 0) (w.toFloat() / h).coerceIn(0.4f, 3f) else fallback
    return this.widthIn(max = 280.dp).heightIn(max = 340.dp).aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
}
