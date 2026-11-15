package app.bloom.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.bloom.AppViewModel
import app.bloom.ui.components.Avatar

/**
 * Long-press a friend or a DM: nickname, notifications for this person, mute,
 * and plugin actions (pin the conversation, copy ids, …).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonSheet(vm: AppViewModel, userId: Long?, dmId: Long?, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val u = userId?.let { vm.users[it] }
    val dm = dmId?.let { id -> vm.dms.firstOrNull { it.id == id } } ?: userId?.let { vm.dmWith(it) }
    val clipboard = LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surfaceContainerLow) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (u != null) Avatar(u.displayName, u.hue, 52.dp, userId = u.id, status = u.status)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(u?.displayName ?: dm?.let { vm.dmName(it) } ?: "Conversation", style = MaterialTheme.typography.titleLarge)
                    if (u != null && vm.nicknames[u.id] != null) Text("@${vm.realName(u.id)}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    if (u != null && vm.isMuted(u.id)) {
                        val until = vm.muteUntil[u.id] ?: -1L
                        Text(if (until == -1L) "Muted" else "Muted until ${timeLabel(until / 1000, true)}", style = MaterialTheme.typography.labelMedium, color = scheme.error)
                    }
                }
            }

            // quick actions
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                if (dm != null) AssistChip({ vm.openDm(dm.id); onDismiss() }, { Text("Message") }, leadingIcon = { Icon(Icons.Rounded.ChatBubble, null, Modifier.size(18.dp)) })
                if (u != null) AssistChip({ vm.openProfile(u.id); onDismiss() }, { Text("Profile") }, leadingIcon = { Icon(Icons.Rounded.Person, null, Modifier.size(18.dp)) })
                if (dm != null) {
                    val pinned = dm.id in vm.pinnedDms
                    AssistChip({ vm.togglePinDm(dm.id) }, { Text(if (pinned) "Unpin" else "Pin to top") }, leadingIcon = { Icon(if (pinned) Icons.Rounded.PushPin else Icons.Rounded.PushPin, null, Modifier.size(18.dp)) })
                }
                if (u != null && vm.plugin("copy_user_urls")) AssistChip({ clipboard.setText(AnnotatedString(u.name)) }, { Text("Copy username") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)) })
            }

            if (u != null) {
                // nickname (only on this device)
                Text("Nickname", style = MaterialTheme.typography.titleSmall, color = scheme.primary)
                var nick by remember(u.id) { mutableStateOf(vm.nicknames[u.id] ?: "") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(nick, { nick = it }, placeholder = { Text(vm.realName(u.id)) }, singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = { vm.setNickname(u.id, nick) }) { Text("Save") }
                }
                if (vm.nicknames[u.id] != null) TextButton(onClick = { nick = ""; vm.setNickname(u.id, null) }) { Text("Reset to ${vm.realName(u.id)}") }
                Text("Only you see this, only in Bloom.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)

                // notifications for this person
                Text("Notifications from ${u.displayName}", style = MaterialTheme.typography.titleSmall, color = scheme.primary, modifier = Modifier.padding(top = 6.dp))
                val mode = vm.userNotif[u.id] ?: "default"
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("default" to "Default", "all" to "Every message", "mentions" to "Only mentions", "none" to "Nothing").forEach { (k, l) ->
                        FilterChip(mode == k, { vm.setUserNotif(u.id, k) }, { Text(l) })
                    }
                }
                Text(
                    when (mode) {
                        "all" -> "Notify for everything they send, including in servers"
                        "mentions" -> "Only when they mention you or reply to you"
                        "none" -> "Never notify for their messages"
                        else -> "Follow your notification settings"
                    },
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                )

                // mute
                Text("Mute", style = MaterialTheme.typography.titleSmall, color = scheme.primary, modifier = Modifier.padding(top = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    listOf(15 to "15 min", 30 to "30 min", 60 to "1 hour", 360 to "6 hours").forEach { (m, l) ->
                        FilterChip(false, { vm.muteUser(u.id, m) }, { Text(l) })
                    }
                    FilterChip(vm.muteUntil[u.id] == -1L, { vm.muteUser(u.id, null) }, { Text("Until I turn it back on") })
                }
                if (vm.isMuted(u.id)) OutlinedButton(onClick = { vm.muteUser(u.id, 0) }) {
                    Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Unmute")
                }

                if (vm.friends.any { it.id == u.id }) {
                    var confirm by remember { mutableStateOf(false) }
                    TextButton(onClick = { confirm = true }) { Text("Remove friend", color = scheme.error) }
                    if (confirm) AlertDialog(
                        onDismissRequest = { confirm = false },
                        title = { Text("Remove ${u.displayName}?") },
                        confirmButton = { TextButton(onClick = { vm.removeFriend(u.id); confirm = false; onDismiss() }) { Text("Remove", color = scheme.error) } },
                        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}
