@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package app.bloom.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import app.bloom.ui.motion.AvatarFlight
import app.bloom.ui.motion.flightSource
import app.bloom.ui.motion.flightTarget
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import app.bloom.AppViewModel
import app.bloom.Screen
import app.bloom.core.ChannelKind
import app.bloom.ui.components.*
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.Motion
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import app.bloom.ui.theme.hueColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val RAIL = 76.dp

@Composable
fun MainScreen(vm: AppViewModel) {
    if (!vm.ready) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), Alignment.Center) { MorphingLoader(size = 84.dp) }
        return
    }
    AnimatedContent(
        vm.authMode == app.bloom.core.AuthMode.SIGNED_OUT,
        transitionSpec = { (fadeIn(Motion.effects()) + scaleIn(Motion.spatial(), initialScale = 0.96f)) togetherWith fadeOut(Motion.effects()) },
        label = "auth",
    ) { signedOut -> if (signedOut) LoginScreen(vm) else SignedInShell(vm) }
}

@Composable
internal fun SignedIn(vm: AppViewModel) {

    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var width by remember { mutableFloatStateOf(1f) }
    val peek = with(density) { 64.dp.toPx() }
    val openX = (width - peek).coerceAtLeast(0f)
    // 0 = chat panel covers the screen; openX = slid right to reveal servers/channels
    val offset = remember { Animatable(0f) }
    var panelOpen by remember { mutableStateOf(true) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    fun settle(open: Boolean) {
        panelOpen = open
        // leaving the chat: put the keyboard away
        if (open) { keyboard?.hide(); focus.clearFocus() }
        scope.launch { offset.animateTo(if (open) openX else 0f, Motion.spatial()) }
    }
    // keep the panel in place across size changes (first layout, rotation, foldables)
    LaunchedEffect(openX) { offset.snapTo(if (panelOpen) openX else 0f) }
    // picking a channel slides the chat back in (but not for the initial selection)
    LaunchedEffect(vm.channelOpens) { if (vm.channelOpens > 0) settle(false) }
    // Settings / Edit Profile slide fully into view instead of staying behind the side panel
    LaunchedEffect(vm.screen) { if (vm.screen != Screen.Chat && panelOpen) settle(false) }
    BackHandler(enabled = !panelOpen || vm.screen != Screen.Chat) {
        if (vm.screen != Screen.Chat) vm.screen = Screen.Chat else settle(true)
    }

    // Profile sheet blurs & recedes everything behind it.
    val profileOpen = vm.profileUserId != null
    val blur by animateDpAsState(if (profileOpen) 18.dp else 0.dp, Motion.effects(), label = "bgBlur")
    val recede by animateFloatAsState(if (profileOpen) 0.94f else 1f, Motion.spatial(), label = "recede")

    Box(Modifier.fillMaxSize().onSizeChanged { width = it.width.toFloat() }.background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        BackgroundShapes()
        // Both values are read inside the layer block, so the animation only redraws:
        // the screen underneath is never recomposed frame by frame.
        val blurOn = app.bloom.ui.theme.Ui.effectsOn
        Box(Modifier.fillMaxSize().graphicsLayer {
            scaleX = recede; scaleY = recede
            val r = if (blurOn) blur.toPx().toInt() else 0
            renderEffect = if (r >= 1) blurEffect(r) else null
        }) {
            // Base layer: rail + side pane
            Row(Modifier.fillMaxSize()) {
                ServerRail(vm)
                Box(
                    Modifier.weight(1f).fillMaxHeight().background(panel(MaterialTheme.colorScheme.surfaceContainerLow)).padding(end = 64.dp)
                        .graphicsLayer {
                            // read the drag position here (draw phase), not in composition
                            val fraction = if (openX > 0f) offset.value / openX else 0f
                            alpha = 0.4f + 0.6f * fraction; translationX = (1 - fraction) * -40f
                        },
                ) {
                    AnimatedContent(
                        targetState = vm.selectedServerId,
                        transitionSpec = {
                            // the list slides in from the direction you moved on the rail
                            val from = vm.servers.indexOfFirst { it.id == initialState }
                            val to = vm.servers.indexOfFirst { it.id == targetState }
                            val down = to > from
                            (fadeIn(Motion.effects()) + slideInVertically(Motion.spatial()) { if (down) it / 8 else -it / 8 })
                                .togetherWith(fadeOut(Motion.effects()) + slideOutVertically(Motion.spatial()) { if (down) -it / 12 else it / 12 })
                        },
                        label = "side",
                    ) { sid -> if (sid == null) FriendsPane(vm) else ChannelPane(vm) }
                }
            }

            // Foreground: chat / plugins panel that slides
            // only flips twice per slide, so the panel recomposes twice, not every frame
            val slid by remember { androidx.compose.runtime.derivedStateOf { offset.value > 1f } }
            val radiusAnim by animateDpAsState(if (slid) LocalLook.current.cardRadius else 0.dp, Motion.spatial(), label = "r")
            val radius = radiusAnim.coerceAtLeast(0.dp)
            Box(
                Modifier
                    .offset { IntOffset(offset.value.roundToInt(), 0) }
                    .fillMaxSize()
                    .then(if (slid) Modifier.softGlow(RoundedCornerShape(radius), MaterialTheme.colorScheme.primary, 24.dp, 0.25f) else Modifier)
                    .clip(RoundedCornerShape(radius))
                    .draggable(
                        rememberDraggableState { d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, openX)) } },
                        Orientation.Horizontal,
                        // the moment you start swiping the chat away, the keyboard starts going down
                        onDragStarted = { if (offset.value < 1f) { keyboard?.hide(); focus.clearFocus() } },
                        onDragStopped = { v -> settle(if (v > 600f) true else if (v < -600f) false else offset.value > openX / 2) },
                    ),
            ) {
                AnimatedContent(
                    targetState = vm.screen,
                    transitionSpec = {
                        (fadeIn(Motion.effects()) + scaleIn(Motion.spatial(), initialScale = 0.92f))
                            .togetherWith(fadeOut(Motion.effects()) + scaleOut(Motion.effects(), targetScale = 1.04f))
                    },
                    label = "screen",
                ) { s ->
                    when (s) {
                        Screen.Settings -> SettingsScreen(vm, onBack = { vm.screen = Screen.Chat })
                        Screen.EditProfile -> EditProfileScreen(vm, onBack = { vm.screen = Screen.Settings })
                        Screen.Customize -> app.bloom.ui.custom.CustomizeScreen(vm, onBack = { vm.screen = Screen.Settings })
                        Screen.You -> app.bloom.ui.custom.YouScreen(vm, onBack = { vm.screen = Screen.Chat })
                        is Screen.ServerRoles -> RolesScreen(vm, s.serverId, onBack = { vm.screen = Screen.Chat })
                        Screen.Notifications -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                            MentionsPage(vm) { }
                            IconButton(onClick = { vm.screen = Screen.Chat }, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp)) { Icon(Icons.Rounded.Close, "Close") }
                        }
                        else -> ChatPane(vm, onMenu = { settle(!panelOpen) })
                    }
                }
                // Tap the peeking edge to close the panel. Also swallow taps while the chat is
                // still sliding, so a tap meant for the channel list can't land on a message
                // (an author's avatar) as the chat passes under the finger.
                if (panelOpen || offset.isRunning) Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { settle(false) })
            }
        }

        ProfileSheet(vm)
        MediaViewer(vm)
        ErrorToast(vm, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
internal fun ServerRail(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    Box(Modifier.width(RAIL).fillMaxHeight().background(panel(scheme.surfaceContainerLowest))) {
    LazyColumn(
        Modifier.fillMaxSize()
            // feather: everything fades out as it slides under the pinned avatar
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                val fade = 150.dp.toPx() + bottomInset.toPx()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black, ((size.height - fade) / size.height).coerceIn(0f, 1f) to Color.Black,
                        ((size.height - fade * 0.45f) / size.height).coerceIn(0f, 1f) to Color.Transparent, 1f to Color.Transparent,
                    ),
                    blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = WindowInsets.systemBars.asPaddingValues().let { PaddingValues(top = it.calculateTopPadding() + 12.dp, bottom = it.calculateBottomPadding() + 96.dp) },
    ) {
        item {
            val sel = vm.selectedServerId == null
            ServerIcon("Home", 0, seed = 7, selected = sel, background = if (sel) scheme.primary else scheme.surfaceContainerHigh, onClick = { vm.selectServer(null) }) {
                Icon(Icons.Rounded.Forum, "Home", tint = if (sel) scheme.onPrimary else scheme.onSurface)
            }
        }
        item { HorizontalDivider(Modifier.width(32.dp), thickness = 2.dp, color = scheme.outlineVariant) }
        items(vm.servers, key = { it.id }) { s ->
            val sel = vm.selectedServerId == s.id
            Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.width(RAIL)) {
                // selection pill
                val pill by animateDpAsState(if (sel) 36.dp else if (s.hasUnread) 8.dp else 0.dp, Motion.spatial(), label = "pill")
                Box(Modifier.width(4.dp).height(pill.coerceAtLeast(0.dp)).clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)).background(scheme.primary))
                Box(Modifier.align(Alignment.Center)) {
                    ServerIcon(s.name, s.hue, seed = s.id.toInt() * 31 + s.shape, selected = sel, imageUrl = s.iconUrl, onClick = { vm.selectServer(s.id) })
                    androidx.compose.animation.AnimatedVisibility(
                        s.mentions > 0, Modifier.align(Alignment.BottomEnd),
                        enter = scaleIn(Motion.spatialFast()), exit = scaleOut(Motion.effects()),
                    ) {
                        Badge(containerColor = scheme.error) { Text("${s.mentions}") }
                    }
                }
            }
        }
        if (vm.plugin("read_all")) item {
            val src = remember { MutableInteractionSource() }
            FilledTonalIconButton(onClick = { vm.readAll() }, modifier = Modifier.pressScale(src), interactionSource = src) {
                Icon(Icons.Rounded.DoneAll, "Read all")
            }
        }
        item {
            val src = remember { MutableInteractionSource() }
            FilledTonalIconButton(onClick = { vm.screen = Screen.Settings }, modifier = Modifier.pressScale(src), interactionSource = src) { Icon(Icons.Rounded.Settings, "Settings") }
        }
    }
    // you: pinned bottom-left; the rail scrolls and fades away behind it.
    // Tapping flies the picture into the You page's ID card.
    vm.me?.let { me ->
        var bounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val src = remember { MutableInteractionSource() }
        Box(
            // not clipped (keeps the status dot whole); hides while its copy is flying
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 14.dp).pressScale(src)
                .flightSource(me.id) { bounds = it }
                .clickable(src, null) { AvatarFlight.launch(me.id, bounds); vm.openYou() },
        ) {
            Avatar(me.displayName, me.hue, 52.dp, userId = me.id, status = me.status)
        }
    }
    }
}

@Composable
internal fun ChannelPane(vm: AppViewModel) {
    val s = vm.selectedServer ?: return
    val scheme = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxSize(), contentPadding = WindowInsets.systemBars.asPaddingValues()) {
        item {
            val spec = LocalLook.current
            Box(
                Modifier.padding(8.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(spec.cardRadius))
                    .background(Brush.linearGradient(listOf(hueColor(s.hue), hueColor(s.hue + 50, tone = 0.4f)))),
                contentAlignment = Alignment.BottomStart,
            ) {
                // the community's own banner, when it has one
                if (s.bannerUrl != null) coil3.compose.AsyncImage(s.bannerUrl, null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                // bottom scrim so the title is always readable
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
                Column(Modifier.padding(16.dp)) {
                    Text(s.name, style = MaterialTheme.typography.headlineSmall.copy(shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 2f), 8f)), color = Color.White)
                    Text("${s.channels.size} channels", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        item(key = "server-actions") {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.screen = Screen.ServerRoles(s.id) }, label = { Text("Roles") }, leadingIcon = { Icon(Icons.Rounded.Shield, null, Modifier.size(18.dp)) })
            }
        }
        s.channels.groupBy { it.category }.forEach { (cat, chans) ->
            item(key = "cat-$cat") {
                Text(cat.uppercase(), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
            }
            items(chans, key = { it.id }) { c ->
                val sel = c.id == vm.selectedChannelId
                val bg by animateColorAsState(if (sel) scheme.secondaryContainer else Color.Transparent, Motion.effects(), label = "chbg")
                val src = remember { MutableInteractionSource() }
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 1.dp).fillMaxWidth().pressScale(src, 0.96f)
                        .clip(RoundedCornerShape(LocalLook.current.rowRadius)).background(bg)
                        .clickable(src, null) { vm.openChannel(c.id) }.padding(horizontal = 12.dp, vertical = if (vm.channelIcons) 8.dp else 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val kindIcon = when (c.kind) { ChannelKind.ANNOUNCEMENT -> Icons.Rounded.Campaign; ChannelKind.VOICE -> Icons.Rounded.GraphicEq; ChannelKind.APP -> Icons.Rounded.Apps; else -> Icons.Rounded.Tag }
                    if (vm.channelIcons) {
                        // Root style: the channel's own picture with a small type badge in the corner
                        Box(Modifier.size(46.dp)) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                                if (c.iconUrl != null) coil3.compose.AsyncImage(c.iconUrl, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                else Icon(kindIcon, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                            }
                            if (c.iconUrl != null) Box(
                                Modifier.align(Alignment.BottomEnd).size(20.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                    .background(scheme.surfaceContainerLow).padding(2.dp).clip(androidx.compose.foundation.shape.CircleShape).background(scheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    when (c.kind) { ChannelKind.APP -> Icons.Rounded.WebAsset; ChannelKind.VOICE -> Icons.Rounded.VolumeUp; ChannelKind.ANNOUNCEMENT -> Icons.Rounded.Campaign; else -> Icons.Rounded.Notes },
                                    null, Modifier.size(12.dp), tint = scheme.onSurface,
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    } else {
                        Icon(kindIcon, null, Modifier.size(20.dp), tint = if (sel) scheme.onSecondaryContainer else scheme.onSurfaceVariant)
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            c.name, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            fontWeight = if (c.unread || sel) FontWeight.Bold else FontWeight.Normal,
                            color = if (c.unread || sel) scheme.onSurface else scheme.onSurfaceVariant,
                        )
                        if (vm.channelTopics && c.topic.isNotBlank()) Text(
                            plainText(c.topic), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                    AnimatedVisibility(c.unread, enter = scaleIn(Motion.spatialFast()), exit = scaleOut(Motion.effects())) {
                        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(scheme.onSurface))
                    }
                }
            }
        }
    }
}

@Composable
internal fun FriendsPane(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues())) {
        Text(if (tab == 0) "Messages" else "Friends", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 8.dp))
        // segmented tabs with a sliding pill
        Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(50)).background(scheme.surfaceContainerHigh)) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(4.dp)) {
                val half = maxWidth / 2
                val x by animateDpAsState(if (tab == 0) 0.dp else half, Motion.spatial(), label = "homeTab")
                Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(half).fillMaxHeight().clip(RoundedCornerShape(50)).background(scheme.primary))
                Row(Modifier.fillMaxSize()) {
                    listOf("Messages", "Friends · ${vm.friends.size}").forEachIndexed { i, label ->
                        val c by animateColorAsState(if (tab == i) scheme.onPrimary else scheme.onSurfaceVariant, Motion.effects(), label = "ht")
                        Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).clickable { tab = i }, contentAlignment = Alignment.Center) {
                            Text(label, color = c, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        var addFriend by remember { mutableStateOf(false) }
        var newGroup by remember { mutableStateOf(false) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { addFriend = true }, label = { Text("Add friend") }, leadingIcon = { Icon(Icons.Rounded.PersonAdd, null, Modifier.size(18.dp)) })
            AssistChip(onClick = { newGroup = true }, label = { Text("New group") }, leadingIcon = { Icon(Icons.Rounded.GroupAdd, null, Modifier.size(18.dp)) })
            AssistChip(onClick = { vm.screen = Screen.Notifications }, label = { Text("Notifications") }, leadingIcon = { Icon(Icons.Rounded.Notifications, null, Modifier.size(18.dp)) })
        }
        if (addFriend) AddFriendDialog(vm) { addFriend = false }
        if (newGroup) FriendPicker(vm, "New group", "Create", onDismiss = { newGroup = false }) { vm.createGroup(it) }
        AnimatedContent(tab, transitionSpec = { fadeIn(Motion.effects()) togetherWith fadeOut(Motion.effects()) }, label = "homeBody") { t ->
            if (t == 0) DmList(vm) else FriendList(vm)
        }
    }
}

@Composable
internal fun DmList(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    if (vm.dms.isEmpty()) {
        Text("No conversations yet", color = scheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        return
    }
    var sheetFor by remember { mutableStateOf<Long?>(null) }
    sheetFor?.let { id -> val d0 = vm.dms.firstOrNull { it.id == id }; PersonSheet(vm, d0?.userIds?.singleOrNull(), id) { sheetFor = null } }
    // Pin DMs plugin: pinned conversations first
    val ordered = if (vm.plugin("pin_dms") && vm.pinnedDms.isNotEmpty())
        vm.pinnedDms.mapNotNull { p -> vm.dms.firstOrNull { it.id == p } } + vm.dms.filter { it.id !in vm.pinnedDms }
        else vm.dms
    LazyColumn(Modifier.fillMaxSize()) {
        items(ordered, key = { it.id }) { d ->
            val other = d.userIds.firstOrNull()?.let { vm.users[it] }
            val pinned = vm.plugin("pin_dms") && d.id in vm.pinnedDms
            val sel = vm.selectedChannelId == d.id
            val bg by animateColorAsState(if (sel) scheme.secondaryContainer else Color.Transparent, Motion.effects(), label = "dmbg")
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth().pressScale(src, 0.97f)
                    .clip(RoundedCornerShape(LocalLook.current.rowRadius)).background(bg)
                    .combinedClickable(src, null, onLongClick = { sheetFor = d.id }) { vm.openDm(d.id) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (other != null) Avatar(other.displayName, other.hue, 44.dp, userId = other.id, status = other.status)
                else Box(Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).background(scheme.surfaceContainerHighest))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pinned) Icon(Icons.Rounded.PushPin, "Pinned", Modifier.size(14.dp).padding(end = 2.dp), tint = scheme.primary)
                        if (d.userIds.singleOrNull()?.let { vm.isMuted(it) } == true) Icon(Icons.Rounded.NotificationsOff, "Muted", Modifier.size(14.dp).padding(end = 2.dp), tint = scheme.onSurfaceVariant)
                        Text(vm.dmName(d), style = MaterialTheme.typography.titleMedium, fontWeight = if (d.unread) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                    val draft = vm.drafts[d.id]
                    if (draft != null && d.id != vm.selectedChannelId) Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            pushStyle(androidx.compose.ui.text.SpanStyle(color = scheme.error, fontWeight = FontWeight.SemiBold)); append("Draft: "); pop()
                            append(plainText(draft).replace('\n', ' '))
                        },
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    ) else d.lastMessage?.let {
                        val who = if (d.lastAuthor == vm.me?.id) "You: " else ""
                        Text(who + plainText(it), style = MaterialTheme.typography.bodySmall, color = if (d.unread) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    if (d.lastActivity > 0) Text(timeLabel(d.lastActivity, true), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    if (d.unread) Box(Modifier.padding(top = 4.dp).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(scheme.primary))
                }
            }
        }
    }
}

@Composable
internal fun FriendList(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    var sheetFor by remember { mutableStateOf<Long?>(null) }
    sheetFor?.let { id -> PersonSheet(vm, id, null) { sheetFor = null } }
    LazyColumn(Modifier.fillMaxSize()) {
        items(vm.friends, key = { it.id }) { u0 ->
            val u = vm.users[u0.id] ?: u0 // picks up nicknames
            val src = remember { MutableInteractionSource() }
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp).fillMaxWidth().pressScale(src, 0.97f)
                    .clip(RoundedCornerShape(LocalLook.current.rowRadius)).combinedClickable(src, null, onLongClick = { sheetFor = u.id }) { vm.openProfile(u.id) }.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(u.displayName, u.hue, 44.dp, userId = u.id, status = u.status)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(u.displayName, style = MaterialTheme.typography.titleMedium)
                        if (u.bot) { Spacer(Modifier.width(6.dp)); BotBadge() }
                    }
                    val sub = u.nowPlaying?.let { "♪ ${it.title}" + (if (it.artist.isNotBlank()) " — ${it.artist}" else "") } ?: u.statusText.ifBlank { plainText(u.bio) }
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (vm.plugin("platform_indicators")) PlatformIcons(u.platforms)
            }
        }
    }
}


/** Shows errors (failed send/upload, network) instead of failing silently. */
@Composable
internal fun ErrorToast(vm: AppViewModel, modifier: Modifier = Modifier) {
    val msg = vm.error
    LaunchedEffect(msg) { if (msg != null) { kotlinx.coroutines.delay(4500); if (vm.error == msg) vm.error = null } }
    AnimatedVisibility(msg != null, modifier.navigationBarsPadding().padding(bottom = 96.dp, start = 16.dp, end = 16.dp),
        enter = slideInVertically(Motion.spatial()) { it } + fadeIn(Motion.effects()), exit = slideOutVertically(Motion.spatial()) { it } + fadeOut(Motion.effects())) {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.clip(app.bloom.ui.theme.Radii.card).background(scheme.inverseSurface).clickable { vm.error = null }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = scheme.inversePrimary)
            Spacer(Modifier.width(10.dp))
            Text(msg ?: "", color = scheme.inverseOnSurface, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
