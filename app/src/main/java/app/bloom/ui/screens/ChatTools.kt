package app.bloom.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.bloom.AppViewModel
import app.bloom.core.ChannelKind
import app.bloom.ui.components.Avatar
import app.bloom.ui.components.MorphingLoader
import app.bloom.ui.components.UserNameText
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.hueColor

/** Root's pinned messages for the open chat. Tap to jump; unpin from the row. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PinsSheet(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = { vm.closePins() }, shape = RoundedCornerShape(topStart = LocalLook.current.cardRadius, topEnd = LocalLook.current.cardRadius)) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PushPin, null, tint = scheme.primary)
            Spacer(Modifier.width(10.dp))
            Text("Pinned messages", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            vm.pins?.let { Text("${it.size}", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant) }
        }
        val pins = vm.pins
        when {
            pins == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { MorphingLoader(size = 40.dp) }
            pins.isEmpty() -> Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.PushPin, null, Modifier.size(40.dp), tint = scheme.outline)
                Spacer(Modifier.height(8.dp))
                Text("Nothing pinned here yet", color = scheme.onSurfaceVariant)
                Text("Long-press a message → Pin", style = MaterialTheme.typography.bodySmall, color = scheme.outline)
            }
            else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(pins, key = { it.id }) { m ->
                    val u = vm.users[m.authorId]
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.closePins(); vm.jumpTarget = m.id }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Avatar(u?.displayName ?: "?", u?.hue ?: 0, 36.dp, userId = m.authorId)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (u != null) UserNameText(u, MaterialTheme.typography.titleSmall, hueColor(u.hue), Modifier.weight(1f, fill = false))
                                Spacer(Modifier.width(8.dp))
                                Text(timeLabel(m.ts, false), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                            }
                            val text = plainText(m.content).replace(Regex("⟦[a-z]:[^⟧]*⟧"), "").trim()
                            Text(
                                text.ifEmpty { if (m.attachments.isNotEmpty()) "📎 ${m.attachments.size} attachment" + (if (m.attachments.size > 1) "s" else "") else "…" },
                                style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(onClick = { vm.setPinned(m, false) }) { Icon(Icons.Rounded.Close, "Unpin", tint = scheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
}

private data class Hit(val key: String, val title: String, val sub: String, val icon: ImageVector?, val userId: Long?, val unread: Boolean, val go: () -> Unit)

/**
 * "Jump to…": type to find any channel, DM, group or community and open it.
 * Ranked: prefix matches first, then unread, then recent DMs.
 */
@Composable
fun QuickSwitcher(vm: AppViewModel, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var q by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val hits = remember(q, vm.servers, vm.dms, vm.users.size) {
        val query = q.trim().lowercase()
        fun score(name: String): Int? {
            val n = name.lowercase()
            return when {
                query.isEmpty() -> 2
                n.startsWith(query) -> 0
                n.split(' ', '-', '_').any { it.startsWith(query) } -> 1
                n.contains(query) -> 2
                else -> null
            }
        }
        val out = mutableListOf<Pair<Int, Hit>>()
        vm.dms.forEach { d ->
            val name = vm.dmName(d)
            score(name)?.let { s -> out += (s * 10 + (if (d.unread) 0 else 1)) to Hit("d${d.id}", name, if (d.userIds.size > 1) "Group" else "Direct message", null, d.userIds.firstOrNull(), d.unread) { vm.openDm(d.id) } }
        }
        vm.servers.forEach { s ->
            score(s.name)?.let { sc -> if (query.isNotEmpty()) out += (sc * 10 + 3) to Hit("s${s.id}", s.name, "Community", Icons.Rounded.Groups, null, false) { vm.selectServer(s.id) } }
            s.channels.filter { it.kind != ChannelKind.APP }.forEach { c ->
                score(c.name)?.let { sc ->
                    if (query.isNotEmpty() || c.unread) out += (sc * 10 + (if (c.unread) 0 else 2)) to Hit(
                        "c${c.id}", c.name, s.name, if (c.kind == ChannelKind.VOICE) Icons.Rounded.VolumeUp else Icons.Rounded.Tag, null, c.unread,
                    ) { vm.openChannelIn(s.id, c.id) }
                }
            }
        }
        out.sortedBy { it.first }.map { it.second }.take(40)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).statusBarsPadding().padding(top = 24.dp).imePadding(),
            shape = RoundedCornerShape(LocalLook.current.cardRadius), color = scheme.surfaceContainerHigh, tonalElevation = 6.dp,
        ) {
            Column {
                OutlinedTextField(
                    q, { q = it }, placeholder = { Text("Jump to a chat, channel or community") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus),
                )
                if (hits.isEmpty()) Text("No matches", Modifier.padding(20.dp), color = scheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                    items(hits, key = { it.key }) { h ->
                        Row(
                            Modifier.fillMaxWidth().clickable { h.go(); onDismiss() }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val u = h.userId?.let { vm.users[it] }
                            if (u != null) Avatar(u.displayName, u.hue, 36.dp, userId = u.id, status = u.status)
                            else Box(Modifier.size(36.dp).clip(CircleShape), contentAlignment = Alignment.Center) { Icon(h.icon ?: Icons.Rounded.Tag, null, tint = scheme.onSurfaceVariant) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(h.sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (h.unread) Box(Modifier.size(8.dp).clip(CircleShape).background(scheme.primary))
                        }
                    }
                }
            }
        }
    }
}
