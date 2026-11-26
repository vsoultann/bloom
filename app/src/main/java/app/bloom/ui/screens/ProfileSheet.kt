package app.bloom.ui.screens

import androidx.compose.foundation.gestures.awaitFirstDown

import androidx.compose.foundation.gestures.awaitEachGesture

import androidx.compose.ui.input.pointer.pointerInput

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.border
import androidx.compose.foundation.basicMarquee
import androidx.compose.animation.core.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp
import app.bloom.AppViewModel
import app.bloom.core.Mutuals
import app.bloom.core.User
import app.bloom.ui.components.*
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion
import app.bloom.ui.theme.hueColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun LocalLookRadius() = app.bloom.ui.theme.LocalLook.current.cardRadius * 0.6f

@Composable
fun ProfileSheet(vm: AppViewModel) {
    val open = vm.profileUserId != null
    // keep showing the last user while the exit animation runs
    var shown by remember { mutableStateOf<User?>(null) }
    val current = vm.profileUserId?.let { vm.users[it] }
    if (current != null && current != shown) shown = current
    BackHandler(open) { vm.closeProfile() }

    val scrim by animateFloatAsState(if (open) 0.45f else 0f, Motion.effects(), label = "scrim")
    val scrimOn by remember { androidx.compose.runtime.derivedStateOf { scrim > 0.001f } }
    if (scrimOn) {
        Box(
            Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = scrim)) }
                .clickable(remember { MutableInteractionSource() }, null) { vm.closeProfile() },
        )
    }

    val drag = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    val heightFrac by animateFloatAsState(if (expanded) 1f else 0.86f, Motion.spatial(), label = "sheetH")
    LaunchedEffect(open) { if (open) { drag.snapTo(0f); expanded = false } }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            open,
            enter = slideInVertically(Motion.spatial()) { it } + fadeIn(Motion.effects()),
            exit = slideOutVertically(Motion.spatial()) { it } + fadeOut(Motion.effects()),
        ) {
            val u = shown ?: return@AnimatedVisibility
            Surface(
                shape = app.bloom.ui.theme.Radii.sheet,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    // Height and drag are read in the layout phase, so the sheet follows your finger
                    // without recomposing the profile. Pulling up grows it live (it used to do
                    // nothing until you let go); pulling down slides it away.
                    .layout { m, c ->
                        val base = c.maxHeight * heightFrac.coerceIn(0.5f, 1f)
                        val h = (base - drag.value.coerceAtMost(0f)).coerceAtMost(c.maxHeight.toFloat()).roundToInt()
                        val pl = m.measure(c.copy(minHeight = h, maxHeight = h))
                        layout(pl.width, pl.height) { pl.place(0, 0) }
                    }
                    .offset { IntOffset(0, drag.value.coerceAtLeast(0f).roundToInt()) },
            ) {
                Box {
                    ProfileContent(vm, u, vm.profileMutuals)
                    // The handle strip: drag up to expand, down to collapse/close.
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).draggable(
                            rememberDraggableState { d -> scope.launch { drag.snapTo(drag.value + d) } },
                            Orientation.Vertical,
                            onDragStopped = { v ->
                                when {
                                    drag.value < -60f || v < -1200f -> expanded = true
                                    expanded && (drag.value > 60f || v > 1200f) -> expanded = false
                                    drag.value > 300f || v > 1500f -> vm.closeProfile()
                                }
                                drag.animateTo(0f, Motion.spatial())
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileContent(vm: AppViewModel, u: User, mutuals: Mutuals?) {
    val border = u.borderId?.let { app.bloom.ui.components.CosmeticStore.assets[it] }
    // "hover" layers of a card-border scene play when the card is touched (and once on open)
    var hover by remember(u.id) { androidx.compose.runtime.mutableIntStateOf(0) }
    LaunchedEffect(u.id, border) { if (border != null) { delay(700); hover++ } }
    Box(
        Modifier.fillMaxSize().pointerInput(u.id) {
            awaitEachGesture { awaitFirstDown(requireUnconsumed = false); hover++ }
        },
    ) {
        ProfileCard(vm, u, mutuals, border)
        if (border != null && (border.scene != null || border.clientArt != null)) {
            app.bloom.ui.components.CardBorderScene(
                border, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                dark = app.bloom.ui.theme.LocalDark.current, hover = hover,
            )
        }
    }
}

@Composable
private fun ProfileCard(vm: AppViewModel, u: User, mutuals: Mutuals?, border: app.bloom.ui.components.CosmeticAsset?) {
    val scheme = MaterialTheme.colorScheme
    val accent = hueColor(u.hue)
    val clipboard = LocalClipboardManager.current

    // Avatar morphs from a cookie into a circle as the sheet lands.
    var landed by remember(u.id) { mutableStateOf(false) }
    LaunchedEffect(u.id) { delay(120); landed = true }
    val morphP by animateFloatAsState(if (landed) 1f else 0f, Motion.spatial(), label = "avatarMorph")
    val living = app.bloom.ui.theme.LocalLook.current.iconMode == app.bloom.ui.theme.IconMode.LIVING
    val avatarShape = if (living) MorphShape(BloomShapes.morph(BloomShapes.sunny, BloomShapes.circle), morphP.coerceIn(0f, 1f), rotation = (1 - morphP) * 45f)
        else androidx.compose.foundation.shape.CircleShape

    // Root profile background cosmetic: tints the whole card, fading into the sheet
    val bg = u.profileBg?.let { f -> f.colors.map { androidx.compose.ui.graphics.Color(it) } }
    Column(
        Modifier.fillMaxSize()
            .then(app.bloom.ui.components.cosmeticBorderOf(Modifier, border))
            .then(if (bg != null) Modifier.background(Brush.verticalGradient(listOf(bg.first().copy(alpha = 0.55f), (bg.getOrNull(1) ?: bg.first()).copy(alpha = 0.22f), androidx.compose.ui.graphics.Color.Transparent))) else Modifier)
            .verticalScroll(rememberScrollState()),
    ) {
        Box(Modifier.fillMaxWidth().height(150.dp)) {
            Box(
                Modifier.fillMaxSize()
                    .background(app.bloom.ui.components.fillBrush(u.profileBg) ?: Brush.linearGradient(listOf(accent, hueColor(u.hue + 50, tone = 0.42f)))),
            )
            if (u.bannerUrl != null) coil3.compose.AsyncImage(u.bannerUrl, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            Box(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).size(40.dp, 5.dp).clip(RoundedCornerShape(50)).background(scheme.onSurface.copy(alpha = 0.3f)))
            Box(Modifier.align(Alignment.BottomStart).offset(x = 20.dp, y = 48.dp).size(104.dp)) {
                // ring behind the avatar (clipped to the morphing shape)
                Box(
                    Modifier.matchParentSize()
                        .contourGlow(avatarShape, listOf(accent, scheme.primary, scheme.tertiary), intensity = morphP.coerceIn(0f, 1f), stroke = 3.dp, glowRadius = 18.dp)
                        .clip(avatarShape)
                        .background(scheme.surfaceContainer),
                )
                // avatar + status dot drawn unclipped so the dot isn't cut off
                Avatar(u.displayName, u.hue, 92.dp, Modifier.align(Alignment.Center), userId = u.id, status = u.status, shape = avatarShape)
            }
        }
        // the strip under the banner: the avatar's lower half on the left, the status
        // bubble floating out of it on the right (never over the banner)
        Box(Modifier.fillMaxWidth().height(60.dp)) {
            if (u.statusText.isNotBlank()) StatusBubble(u.statusText, accent, Modifier.align(Alignment.CenterStart).padding(start = 128.dp, end = 16.dp))
        }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.bloom.ui.components.UserNameText(u, MaterialTheme.typography.headlineSmall, scheme.onSurface, Modifier.weight(1f, fill = false), animated = true)
                if (u.bot) { Spacer(Modifier.width(8.dp)); BotBadge() }
                Spacer(Modifier.width(8.dp))
                if (vm.plugin("platform_indicators")) PlatformIcons(u.platforms)
            }
            Text(
                listOf("@${u.name}", u.pronouns).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
            )
            // roles in the server you're looking at
            vm.selectedServerId?.let { sid -> if (vm.authMode == app.bloom.core.AuthMode.ROOT) MemberRolesRow(vm, sid, u.id) }
            u.nowPlaying?.let { np ->
                Spacer(Modifier.height(12.dp))
                NowPlayingCard(np, accent)
            }
            // relationship actions: add · accept/decline · message/call
            if (u.id != vm.me?.id && !u.bot && vm.authMode == app.bloom.core.AuthMode.ROOT) {
                LaunchedEffect(u.id) { if (vm.notifications.isEmpty()) vm.loadNotifications() }
                val isFriend = vm.friends.any { it.id == u.id }
                val request = vm.notifications.firstOrNull { it.isFriendRequest && it.userId == u.id }
                var sent by remember(u.id) { mutableStateOf(false) }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        isFriend -> {
                            val dm = vm.dmWith(u.id)
                            Button(onClick = { vm.closeProfile(); if (dm != null) vm.openDm(dm.id) else vm.createGroup(listOf(u.id)) }) {
                                Icon(Icons.Rounded.ChatBubble, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Message")
                            }
                            FilledTonalButton(onClick = { if (dm != null) { vm.closeProfile(); vm.openDm(dm.id); vm.voice.join(dm.id, u.displayName) } }, enabled = dm != null) {
                                Icon(Icons.Rounded.Call, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Call")
                            }
                        }
                        request != null -> {
                            Button(onClick = { vm.respondFriend(request, true) }) {
                                Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Accept")
                            }
                            OutlinedButton(onClick = { vm.respondFriend(request, false) }) {
                                Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Decline")
                            }
                        }
                        else -> Button(onClick = { vm.addFriend(u.name); sent = true }, enabled = !sent) {
                            Icon(if (sent) Icons.Rounded.Check else Icons.Rounded.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(if (sent) "Request sent" else "Add friend")
                        }
                    }
                }
            }
            if (u.id == vm.me?.id && vm.authMode == app.bloom.core.AuthMode.ROOT) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { vm.closeProfile(); vm.screen = app.bloom.Screen.EditProfile }) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Edit profile")
                }
            }
            Spacer(Modifier.height(12.dp))
            Card(shape = app.bloom.ui.theme.Radii.panel, colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh)) {
                Column(Modifier.padding(16.dp).fillMaxWidth()) {
                    Text("About", style = MaterialTheme.typography.labelLarge, color = scheme.primary)
                    if (u.bio.isBlank()) Text("No bio yet", style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
                    else {
                        var revealed by remember(u.id) { mutableStateOf(setOf<Int>()) }
                        val bio = remember(u.bio, revealed) { renderContent(u.bio, scheme.primary, scheme.surfaceContainerHighest, revealed, { revealed = revealed + it }, { vm.openMention(it) }, scheme.onSurfaceVariant) }
                        RichText(bio, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
                    }
                    if (vm.plugin("friends_since") && u.friendSince != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Cake, null, Modifier.size(16.dp), tint = scheme.tertiary)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Friends since " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(u.friendSince * 1000)),
                                style = MaterialTheme.typography.labelLarge, color = scheme.tertiary,
                            )
                        }
                    }
                }
            }
            if (u.id != vm.me?.id && vm.friends.any { it.id == u.id }) {
                var confirm by remember { mutableStateOf(false) }
                TextButton(onClick = { confirm = true }) { Text("Remove friend", color = scheme.error) }
                if (confirm) AlertDialog(
                    onDismissRequest = { confirm = false },
                    title = { Text("Remove ${u.displayName}?") },
                    text = { Text("They won't be notified.") },
                    confirmButton = { TextButton(onClick = { vm.removeFriend(u.id); confirm = false }) { Text("Remove", color = scheme.error) } },
                    dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                )
            }
            if (vm.plugin("copy_user_urls")) {
                TextButton(onClick = { clipboard.setText(AnnotatedString("https://bloom.app/u/${u.id}")) }) {
                    Icon(Icons.Rounded.Link, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Copy profile link")
                }
            }

            if (vm.plugin("mutual_view") && u.id != vm.me?.id) {
                Spacer(Modifier.height(8.dp))
                MutualsSection(vm, mutuals)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MutualsSection(vm: AppViewModel, m: Mutuals?) {
    var tab by remember { mutableIntStateOf(0) }
    val scheme = MaterialTheme.colorScheme
    if (m == null) {
        Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) { MorphingLoader(size = 40.dp) }
        return
    }
    // Expressive segmented toggle with a sliding, glowing indicator
    Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(50)).background(scheme.surfaceContainerHighest)) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
            val half = maxWidth / 2
            val x by androidx.compose.animation.core.animateDpAsState(if (tab == 0) 0.dp else half, Motion.spatial(), label = "tab")
            Box(
                Modifier.offset(x = x).width(half).fillMaxHeight()
                    .softGlow(RoundedCornerShape(50), scheme.primary, 10.dp, 0.35f)
                    .clip(RoundedCornerShape(50)).background(scheme.primary),
            )
            Row(Modifier.fillMaxSize()) {
                listOf("Mutual servers · ${m.servers.size}", "Mutual friends · ${m.friends.size}").forEachIndexed { i, label ->
                    val c by animateColorAsState(if (tab == i) scheme.onPrimary else scheme.onSurfaceVariant, Motion.effects(), label = "tc")
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50))
                            .clickable { tab = i },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = c, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    AnimatedContent(
        tab,
        transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (slideInHorizontally(Motion.spatial()) { it / 3 * dir } + fadeIn(Motion.effects()))
                .togetherWith(slideOutHorizontally(Motion.spatial()) { -it / 3 * dir } + fadeOut(Motion.effects()))
        },
        label = "mutualTab",
    ) { t ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val empty = if (t == 0) m.servers.isEmpty() else m.friends.isEmpty()
            if (empty) {
                Text(
                    if (t == 0) "No servers in common" else "No friends in common",
                    color = scheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                )
            }
            if (t == 0) m.servers.forEachIndexed { i, s ->
                Staggered(i) {
                    MutualRow(onClick = { vm.closeProfile(); vm.selectServer(s.id) }) {
                        ServerIcon(s.name, s.hue, seed = s.id.toInt() * 31 + s.shape, selected = false, size = 40.dp, imageUrl = s.iconUrl, onClick = { vm.closeProfile(); vm.selectServer(s.id) })
                        Spacer(Modifier.width(12.dp))
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            } else m.friends.forEachIndexed { i, f ->
                Staggered(i) {
                    MutualRow(onClick = { vm.openProfile(f.id) }) {
                        Avatar(f.displayName, f.hue, 40.dp, userId = f.id, status = f.status)
                        Spacer(Modifier.width(12.dp))
                        Text(f.displayName, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** Items cascade in one after another. */
@Composable
private fun Staggered(index: Int, content: @Composable () -> Unit) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(40L * index); show = true }
    val p by animateFloatAsState(if (show) 1f else 0f, Motion.spatial(), label = "stagger")
    Box(Modifier.graphicsLayer { alpha = p.coerceIn(0f, 1f); translationY = (1 - p) * 40f; scaleX = 0.95f + 0.05f * p; scaleY = scaleX }) { content() }
}

@Composable
private fun MutualRow(onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().pressScale(src, 0.97f).clip(app.bloom.ui.theme.Radii.panel)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(src, null, onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}


/**
 * Status as a speech bubble floating out of the avatar: tinted by the person's colour
 * (or frosted / solid, see Customize), with trailing dots, a pop-in, and a gentle bob.
 */
@Composable
private fun StatusBubble(text: String, accent: Color, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val style = app.bloom.ui.theme.Ui.statusBubble
    var expanded by remember(text) { mutableStateOf(false) }
    var shown by remember(text) { mutableStateOf(false) }
    LaunchedEffect(text) { delay(180); shown = true }
    val pop by animateFloatAsState(if (shown) 1f else 0f, spring(0.55f, 380f), label = "bubblePop")
    val t = if (app.bloom.ui.theme.Ui.effectsOn) rememberInfiniteTransition(label = "bob") else null
    val bob = t?.animateFloat(-1f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "y")
    val dots = t?.animateFloat(0.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d")
    val (bg, fg, border) = when (style) {
        "glass" -> Triple(scheme.surfaceContainerHighest.copy(alpha = 0.72f), scheme.onSurface, scheme.onSurface.copy(alpha = 0.12f))
        "solid" -> Triple(scheme.surfaceContainerHighest, scheme.onSurface, Color.Transparent)
        else -> Triple(lerp(scheme.surfaceContainerHighest, accent, 0.55f), if (lerp(scheme.surfaceContainerHighest, accent, 0.55f).luminance() > 0.5f) Color.Black else Color.White, accent.copy(alpha = 0.6f))
    }
    Row(
        modifier.graphicsLayer {
            scaleX = pop; scaleY = pop; alpha = pop
            translationY = (bob?.value ?: 0f) * 2.dp.toPx()
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // trailing "thought" dots leading back to the avatar
        Box(Modifier.size(6.dp).graphicsLayer { alpha = dots?.value ?: 1f }.clip(androidx.compose.foundation.shape.CircleShape).background(bg))
        Spacer(Modifier.width(3.dp))
        Box(Modifier.size(10.dp).graphicsLayer { alpha = 0.5f + 0.5f * (dots?.value ?: 1f) }.clip(androidx.compose.foundation.shape.CircleShape).background(bg))
        Spacer(Modifier.width(4.dp))
        Text(
            text, color = fg, style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) 8 else 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
                .animateContentSize(app.bloom.ui.theme.Motion.spatial())
                .clip(RoundedCornerShape(18.dp)).background(bg)
                .border(1.dp, border, RoundedCornerShape(18.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** "Listening on Spotify": a bold card in the person's colours with live equaliser bars. */
@Composable
private fun NowPlayingCard(np: app.bloom.notify.NowPlaying, accent: Color) {
    val scheme = MaterialTheme.colorScheme
    val fg = if (accent.luminance() > 0.55f) Color.Black else Color.White
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(accent, lerp(accent, scheme.tertiary, 0.6f))))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).background(fg.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Equalizer(fg)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("LISTENING ON ${np.app.uppercase()}", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold), color = fg.copy(alpha = 0.8f))
            Text(np.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = fg, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.basicMarquee())
            if (np.artist.isNotBlank()) Text(np.artist, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.85f), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.GraphicEq, null, tint = fg.copy(alpha = 0.7f))
    }
}

/** Four bars bouncing out of step, like a playing track. */
@Composable
private fun Equalizer(color: Color) {
    val t = if (app.bloom.ui.theme.Ui.effectsOn) rememberInfiniteTransition(label = "eq") else null
    Row(Modifier.height(26.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(520, 700, 430, 610).forEachIndexed { i, ms ->
            val h = t?.animateFloat(0.25f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(i * 120)), label = "b$i")
            Box(Modifier.width(5.dp).fillMaxHeight(h?.value ?: (0.4f + i * 0.15f)).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}
