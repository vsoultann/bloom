package app.bloom.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.bloom.AppViewModel
import app.bloom.Screen
import app.bloom.ui.components.*
import app.bloom.ui.theme.Look
import app.bloom.ui.theme.LocalLook
import kotlinx.coroutines.launch

/** Picks the layout shell for the active look. */
@Composable
fun SignedInShell(vm: AppViewModel) {
    when (LocalLook.current.look) {
        Look.MATERIAL -> SignedIn(vm)
        Look.DISCORD -> DiscordShell(vm)
        Look.ROOT -> RootShell(vm)
    }
}

// Root's design tokens: 150 / 200 / 300 ms eased transitions, no bounce.
private fun <T> rootFast() = tween<T>(150, easing = FastOutSlowInEasing)
private fun <T> rootNormal() = tween<T>(200, easing = FastOutSlowInEasing)
private fun <T> rootSlow() = tween<T>(300, easing = FastOutSlowInEasing)

/** Bottom navigation bar shared by the Discord- and Root-style shells. */
@Composable
private fun BottomBar(items: List<Triple<String, ImageVector, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().background(scheme.surfaceContainerLowest).navigationBarsPadding().height(60.dp),
        horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { i, (label, icon, badge) ->
            val sel = i == selected
            val tint by animateColorAsState(if (sel) scheme.onSurface else scheme.onSurfaceVariant, rootFast(), label = "tab")
            Column(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                BadgedBox(badge = { if (badge > 0) Badge { Text(if (badge > 99) "99+" else "$badge") } }) {
                    Icon(icon, label, tint = tint)
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = tint, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

// ---------------------------------------------------------------- Discord-style

/**
 * Discord-style: server rail + channel list with the chat sliding over it,
 * plus a bottom bar (Servers, Mentions, You). Flat, crisp, dense.
 */
@Composable
fun DiscordShell(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // the bottom bar only shows while you're browsing, not inside a chat
    val showBar = tab != 0 || vm.screen == Screen.Chat
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
        Box(Modifier.weight(1f)) {
            AnimatedContent(tab, transitionSpec = { fadeIn(rootFast()) togetherWith fadeOut(rootFast()) }, label = "dtab") { t ->
                when (t) {
                    0 -> SignedIn(vm)
                    1 -> MentionsPage(vm) { tab = 0 }
                    else -> YouPage(vm) { tab = 0 }
                }
            }
        }
        if (showBar) BottomBar(
            listOf(Triple("Servers", Icons.Rounded.Forum, vm.dms.count { it.unread }), Triple("Mentions", Icons.Rounded.AlternateEmail, vm.mentions.size), Triple("You", Icons.Rounded.AccountCircle, 0)),
            tab,
        ) { tab = it }
    }
}

// ---------------------------------------------------------------- Root-style

private enum class RootTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    MESSAGES("Messages", Icons.Rounded.ChatBubble),
    FRIENDS("Friends", Icons.Rounded.People),
    NOTIFICATIONS("Notifications", Icons.Rounded.Notifications),
    PROFILE("Profile", Icons.Rounded.AccountCircle),
}

/**
 * Root-style: five bottom tabs. Home shows the selected community's channels with
 * a drawer listing your communities; opening a channel or DM pushes the chat on
 * top (the bottom bar hides while chatting). Motion uses Root's 150–300 ms timings.
 */
@Composable
fun RootShell(vm: AppViewModel) {
    var tab by rememberSaveable { mutableStateOf(RootTab.HOME) }
    var chatOpen by rememberSaveable { mutableStateOf(false) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(vm.channelOpens) { if (vm.channelOpens > 0) chatOpen = true }
    BackHandler(chatOpen || vm.screen != Screen.Chat) { if (vm.screen != Screen.Chat) vm.screen = Screen.Chat else chatOpen = false }

    Box(Modifier.fillMaxSize().background(scheme.surface)) {
        AnimatedContent(
            targetState = when { vm.screen != Screen.Chat -> 2; chatOpen -> 1; else -> 0 },
            transitionSpec = {
                if (targetState > initialState) (slideInHorizontally(rootSlow()) { it } + fadeIn(rootNormal())) togetherWith (slideOutHorizontally(rootSlow()) { -it / 4 } + fadeOut(rootNormal()))
                else (slideInHorizontally(rootSlow()) { -it / 4 } + fadeIn(rootNormal())) togetherWith (slideOutHorizontally(rootSlow()) { it } + fadeOut(rootNormal()))
            },
            label = "rootStack",
        ) { layer ->
            when (layer) {
                2 -> when (vm.screen) {
                    Screen.EditProfile -> EditProfileScreen(vm, onBack = { vm.screen = Screen.Settings })
                    Screen.Customize -> app.bloom.ui.custom.CustomizeScreen(vm, onBack = { vm.screen = Screen.Settings })
                    Screen.Notifications -> MentionsPage(vm) { vm.screen = Screen.Chat }
                    else -> SettingsScreen(vm, onBack = { vm.screen = Screen.Chat })
                }
                1 -> ChatPane(vm, onMenu = { chatOpen = false })
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        AnimatedContent(tab, transitionSpec = { fadeIn(rootFast()) togetherWith fadeOut(rootFast()) }, label = "rootTab") { t ->
                            when (t) {
                                RootTab.HOME -> ModalNavigationDrawer(
                                    drawerState = drawer,
                                    drawerContent = {
                                        ModalDrawerSheet(drawerContainerColor = scheme.surfaceContainerLow, drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)) {
                                            CommunityDrawer(vm) { scope.launch { drawer.close() } }
                                        }
                                    },
                                ) { RootHome(vm, openDrawer = { scope.launch { drawer.open() } }) }
                                RootTab.MESSAGES -> Column(Modifier.statusBarsPadding()) { PageTitle("Messages"); DmList(vm) }
                                RootTab.FRIENDS -> Column(Modifier.statusBarsPadding()) { PageTitle("Friends"); FriendList(vm) }
                                RootTab.NOTIFICATIONS -> MentionsPage(vm) { }
                                RootTab.PROFILE -> YouPage(vm) { }
                            }
                        }
                    }
                    BottomBar(
                        RootTab.entries.map { Triple(it.label, it.icon, when (it) { RootTab.MESSAGES -> vm.dms.count { d -> d.unread }; RootTab.NOTIFICATIONS -> vm.mentions.size; else -> 0 }) },
                        tab.ordinal,
                    ) { tab = RootTab.entries[it] }
                }
            }
        }
        ProfileSheet(vm)
        MediaViewer(vm)
        ErrorToast(vm, Modifier.align(Alignment.BottomCenter))
    }
}


@Composable
private fun PageTitle(t: String) {
    Text(t, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(20.dp, 16.dp, 20.dp, 8.dp))
}

/** Root-style Home: the selected community's banner and channels; drawer button top-left. */
@Composable
private fun RootHome(vm: AppViewModel, openDrawer: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = openDrawer) { Icon(Icons.Rounded.Menu, "Communities") }
            val s = vm.selectedServer
            Text(s?.name ?: "Home", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Rounded.Settings, "Settings") }
        }
        if (vm.selectedServer == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                TextButton(onClick = openDrawer) { Text("Pick a community") }
            }
        } else Box(Modifier.weight(1f)) { ChannelPane(vm) }
    }
}

/** Drawer listing communities with icon + name (Root shows names, not just icons). */
@Composable
private fun CommunityDrawer(vm: AppViewModel, close: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Text("Communities", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp))
    LazyColumn {
        items(vm.servers, key = { it.id }) { s ->
            val sel = s.id == vm.selectedServerId
            NavigationDrawerItem(
                label = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { ServerIcon(s.name, s.hue, seed = s.id.toInt(), selected = sel, size = 36.dp, imageUrl = s.iconUrl, onClick = { vm.selectServer(s.id); close() }) },
                badge = { if (s.mentions > 0) Badge { Text("${s.mentions}") } else if (s.hasUnread) Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary)) },
                selected = sel,
                onClick = { vm.selectServer(s.id); close() },
                shape = RoundedCornerShape(LocalLook.current.rowRadius),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- shared pages

/** Root notifications: everything, mentions, and friend requests (accept / decline). */
@Composable
fun MentionsPage(vm: AppViewModel, onOpened: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { vm.loadNotifications() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val list = when (tab) {
        1 -> vm.notifications.filter { it.isMention }
        2 -> vm.notifications.filter { it.isFriendRequest }
        else -> vm.notifications
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(20.dp, 16.dp, 8.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (vm.notifications.any { !it.viewed }) TextButton(onClick = { vm.markNotificationsRead() }) { Text("Mark all read") }
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val req = vm.notifications.count { it.isFriendRequest }
            listOf("All", "Mentions", "Friend requests" + if (req > 0) " · $req" else "").forEachIndexed { i, l -> FilterChip(tab == i, { tab = i }, { Text(l) }) }
        }
        if (vm.loadingNotifications && vm.notifications.isEmpty()) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { MorphingLoader(size = 40.dp) }
        if (!vm.loadingNotifications && list.isEmpty()) Text("Nothing here yet.", color = scheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        LazyColumn {
            items(list, key = { it.id }) { n ->
                val who = n.userId?.let { vm.users[it] }
                val where = n.place ?: n.channelId?.let { ch -> vm.servers.firstNotNullOfOrNull { s -> s.channels.firstOrNull { it.id == ch }?.let { "${s.name} · #${it.name}" } } ?: vm.dms.firstOrNull { it.id == ch }?.let { vm.dmName(it) } }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (!n.viewed) scheme.primary.copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(enabled = n.channelId != null) { vm.openNotification(n); onOpened() }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (who != null) Avatar(who.displayName, who.hue, 40.dp, userId = who.id) else Box(Modifier.size(40.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val what = when (n.type) {
                            1 -> "sent you a friend request"
                            2 -> "answered your friend request"
                            3, 7, 8 -> "mentioned you"
                            4 -> "invited you to a community"
                            5 -> "removed you from a community"
                            6 -> "banned you from a community"
                            else -> "sent a notification"
                        }
                        Text("${who?.displayName ?: "Someone"} $what", style = MaterialTheme.typography.titleSmall)
                        Text(timeLabel(n.ts, true) + (where?.let { " · $it" } ?: ""), style = MaterialTheme.typography.labelSmall, color = scheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        n.text?.let { Text(plainText(it), style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        if (n.isFriendRequest) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { vm.respondFriend(n, true) }) { Text("Accept") }
                            OutlinedButton(onClick = { vm.respondFriend(n, false) }) { Text("Decline") }
                        }
                    }
                }
            }
        }
    }
}

/** Pick friends (multi-select) — used for new groups and adding people. */
@Composable
fun FriendPicker(vm: AppViewModel, title: String, confirm: String, exclude: Set<Long> = emptySet(), onDismiss: () -> Unit, onPick: (List<Long>) -> Unit) {
    val picked = remember { mutableStateListOf<Long>() }
    var q by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text("Search friends") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(vm.friends.filter { it.id !in exclude && (q.isBlank() || it.displayName.contains(q, true)) }, key = { it.id }) { u ->
                        Row(Modifier.fillMaxWidth().clickable { if (u.id in picked) picked.remove(u.id) else picked.add(u.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(u.id in picked, { if (it) picked.add(u.id) else picked.remove(u.id) })
                            Avatar(u.displayName, u.hue, 32.dp, userId = u.id)
                            Spacer(Modifier.width(10.dp))
                            Text(u.displayName)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked.toList()); onDismiss() }, enabled = picked.isNotEmpty()) { Text(confirm + if (picked.isNotEmpty()) " (${picked.size})" else "") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Add a friend by username. */
@Composable
fun AddFriendDialog(vm: AppViewModel, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add friend") },
        text = { OutlinedTextField(name, { name = it.trim() }, label = { Text("Root username") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { vm.addFriend(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Send request") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "You": your profile card with quick actions. */
@Composable
fun YouPage(vm: AppViewModel, onDone: () -> Unit) {
    val me = vm.me ?: return
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(LocalLook.current.cardRadius)).background(scheme.surfaceContainerHigh)) {
            me.bannerUrl?.let { coil3.compose.AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(me.displayName, me.hue, 72.dp, userId = me.id, status = me.status)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(me.displayName, style = MaterialTheme.typography.titleLarge)
                if (me.statusText.isNotBlank()) Text(me.statusText, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
        if (me.bio.isNotBlank()) Text(me.bio, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { vm.screen = Screen.EditProfile; onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Edit profile") }
        OutlinedButton(onClick = { vm.screen = Screen.Settings; onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Settings") }
    }
}
