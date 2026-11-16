package app.bloom.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.luminance
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.bloom.AppViewModel
import app.bloom.ui.theme.LocalLook

/** "#ff8800" / "ff8800" → Color (falls back to the theme's outline). */
fun roleColor(hex: String, fallback: Color): Color = runCatching {
    val h = hex.removePrefix("#").uppercase()
    // Root uses pure white / black for roles without a colour
    if (h.length != 6 || h == "FFFFFF" || h == "000000") fallback else Color(0xFF000000 or h.toLong(16))
}.getOrDefault(fallback)

private val Palette = listOf("#e74c3c", "#e67e22", "#f1c40f", "#2ecc71", "#1abc9c", "#3498db", "#9b59b6", "#e91e63", "#95a5a6", "#ffffff")

/** A server's roles (colour, name, member count); "Manage roles" turns on create/edit/delete. */
@Composable
fun RolesScreen(vm: AppViewModel, serverId: Long, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val server = vm.servers.firstOrNull { it.id == serverId }
    LaunchedEffect(serverId) { vm.loadServerRoles(serverId, force = true) }
    val roles = vm.serverRoles[serverId].orEmpty()
    var manage by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AppViewModel.Role?>(null) }
    var creating by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(scheme.surface).statusBarsPadding()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Roles", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(server?.name.orEmpty(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = { manage = !manage }) {
                Icon(if (manage) Icons.Rounded.Check else Icons.Rounded.ManageAccounts, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text(if (manage) "Done" else "Manage roles")
            }
        }
        AnimatedVisibility(manage) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Changes apply on Root for everyone in this server. You need the Manage Roles permission.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { creating = true }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New role") }
            }
        }
        OutlinedTextField(query, { query = it }, placeholder = { Text("Search ${roles.size} roles") }, singleLine = true, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        if (roles.isEmpty()) Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { app.bloom.ui.components.MorphingLoader(size = 40.dp) }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            items(roles.filter { query.isBlank() || it.name.contains(query, true) }, key = { it.id }) { r ->
                val c = roleColor(r.color, scheme.onSurface)
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 3.dp).fillMaxWidth().clip(RoundedCornerShape(LocalLook.current.rowRadius))
                        .background(scheme.surfaceContainerLow).clickable(enabled = manage) { editing = r }.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, color = c, fontWeight = FontWeight.SemiBold)
                        val tags = listOfNotNull("${r.members} members", if (r.mentionable) "mentionable" else null, if (r.selfAssignable) "self-assign" else null)
                        Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                    }
                    if (manage) Icon(Icons.Rounded.Edit, "Edit", tint = scheme.onSurfaceVariant)
                }
            }
        }
    }
    editing?.let { r ->
        RoleDialog(r, onDelete = { vm.deleteRole(serverId, r.id); editing = null }, onDismiss = { editing = null }) { name, color, m, sa ->
            vm.editRole(serverId, r.id, name, color, m, sa); editing = null
        }
    }
    if (creating) RoleDialog(null, onDismiss = { creating = false }) { name, color, _, _ -> vm.createRole(serverId, name, color); creating = false }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoleDialog(
    role: AppViewModel.Role?,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit = {},
    onSave: (name: String, color: String, mentionable: Boolean, selfAssignable: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(role?.name ?: "") }
    var color by remember { mutableStateOf(role?.color?.takeIf { it.isNotBlank() && !it.equals("#FFFFFF", true) && !it.equals("#000000", true) } ?: Palette[5]) }
    var mentionable by remember { mutableStateOf(role?.mentionable ?: false) }
    var selfAssign by remember { mutableStateOf(role?.selfAssignable ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val c = roleColor(color, scheme.primary)
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surfaceContainerLow, shape = app.bloom.ui.theme.Radii.sheet) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // live preview of the role pill
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (role == null) "New role" else "Edit role", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
                    Text(name.ifBlank { "role" }, color = c, fontWeight = FontWeight.SemiBold)
                }
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, shape = app.bloom.ui.theme.Radii.card, modifier = Modifier.fillMaxWidth())
            Text("Colour", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Palette.forEach { hex ->
                    val pc = roleColor(hex, scheme.onSurface)
                    val sel = color.equals(hex, true)
                    val k by androidx.compose.animation.core.animateFloatAsState(if (sel) 1.15f else 1f, app.bloom.ui.theme.Motion.spatial(), label = "sw")
                    Box(
                        Modifier.size(36.dp).graphicsLayer { scaleX = k; scaleY = k }.clip(CircleShape).background(pc)
                            .border(if (sel) 3.dp else 0.dp, scheme.onSurface, CircleShape).clickable { color = hex },
                    )
                }
            }
            Column(Modifier.clip(app.bloom.ui.theme.Radii.panel).background(scheme.surfaceContainerHigh)) {
                Row(Modifier.clickable { mentionable = !mentionable }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Anyone can @mention it", Modifier.weight(1f)); Switch(mentionable, { mentionable = it })
                }
                if (role != null) Row(Modifier.clickable { selfAssign = !selfAssign }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Members can give it to themselves", Modifier.weight(1f)); Switch(selfAssign, { selfAssign = it })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    if (confirmDelete) Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)) { Text("Delete for everyone") }
                    else TextButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, null, Modifier.size(18.dp), tint = scheme.error); Spacer(Modifier.width(6.dp)); Text("Delete", color = scheme.error) }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSave(name.trim(), color, mentionable, selfAssign) }, enabled = name.isNotBlank()) { Text("Save") }
            }
        }
    }
}

/** A member's roles in the current server, as coloured chips; managers can add/remove. */
@Composable
fun MemberRolesRow(vm: AppViewModel, serverId: Long, userId: Long) {
    val scheme = MaterialTheme.colorScheme
    LaunchedEffect(serverId, userId) { vm.loadMemberRoles(serverId, userId) }
    val all = vm.serverRoles[serverId].orEmpty()
    val mine = vm.memberRoles[serverId to userId]
    var picking by remember { mutableStateOf(false) }
    if (mine == null) return
    Column(Modifier.padding(top = 10.dp)) {
        Text("Roles in ${vm.servers.firstOrNull { it.id == serverId }?.name.orEmpty()}", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            all.filter { it.id in mine }.forEach { r ->
                val c = roleColor(r.color, scheme.outline)
                Row(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(c)); Spacer(Modifier.width(6.dp))
                    Text(r.name, style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(Modifier.clip(RoundedCornerShape(50)).background(scheme.surfaceContainerHighest).clickable { picking = true }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                Text("Manage", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (picking) RolePickerSheet(vm, serverId, userId) { picking = false }
}

/** Bottom sheet to give or take a member's roles. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RolePickerSheet(vm: AppViewModel, serverId: Long, userId: Long, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val user = vm.users[userId]
    val all = vm.serverRoles[serverId].orEmpty()
    val mine = vm.memberRoles[serverId to userId].orEmpty()
    var query by remember { mutableStateOf("") }
    val shown = all.filter { query.isBlank() || it.name.contains(query, true) }
    val has = shown.filter { it.id in mine }
    val canAdd = shown.filter { it.id !in mine }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surfaceContainerLow, shape = app.bloom.ui.theme.Radii.sheet) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (user != null) app.bloom.ui.components.Avatar(user.displayName, user.hue, 44.dp, userId = user.id)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Roles for ${user?.displayName ?: "member"}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${mine.size} of ${all.size} · tap to give or take", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("Search roles") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth(),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (has.isNotEmpty()) item(key = "h") { SheetLabel("Has · ${has.size}") }
            items(has, key = { "h" + it.id }) { r -> RoleToggleRow(vm, r, true, Modifier.animateItem()) { vm.setMemberRole(serverId, userId, r.id, false) } }
            if (canAdd.isNotEmpty()) item(key = "a") { SheetLabel("Can add · ${canAdd.size}") }
            items(canAdd, key = { "a" + it.id }) { r -> RoleToggleRow(vm, r, false, Modifier.animateItem()) { vm.setMemberRole(serverId, userId, r.id, true) } }
            if (shown.isEmpty()) item { Text("No roles match \"$query\"", color = scheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun SheetLabel(t: String) = Text(
    t.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 2.dp),
)

@Composable
private fun RoleToggleRow(vm: AppViewModel, r: AppViewModel.Role, on: Boolean, modifier: Modifier, onToggle: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val c = roleColor(r.color, scheme.primary)
    val bg by androidx.compose.animation.animateColorAsState(if (on) c.copy(alpha = 0.16f) else scheme.surfaceContainerHigh, app.bloom.ui.theme.Motion.smooth(), label = "rbg")
    val pending = r.id in vm.pendingRoles
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(LocalLook.current.rowRadius + 4.dp)).background(bg)
            .clickable(enabled = !pending, onClick = onToggle).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(roleColor(r.color, scheme.onSurfaceVariant)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.name, fontWeight = FontWeight.SemiBold, color = if (on) roleColor(r.color, scheme.onSurface) else scheme.onSurface)
            Text("${r.members} members", style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        }
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            androidx.compose.animation.AnimatedContent(
                when { pending -> 2; on -> 1; else -> 0 },
                transitionSpec = {
                    (androidx.compose.animation.scaleIn(app.bloom.ui.theme.Motion.spatial(), initialScale = 0.4f) + androidx.compose.animation.fadeIn(app.bloom.ui.theme.Motion.effects())) togetherWith
                        (androidx.compose.animation.scaleOut(targetScale = 0.4f) + androidx.compose.animation.fadeOut(app.bloom.ui.theme.Motion.effects()))
                }, label = "rstate",
            ) { st ->
                when (st) {
                    2 -> app.bloom.ui.components.MorphingLoader(size = 20.dp)
                    1 -> Box(Modifier.size(26.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, "Has this role", Modifier.size(16.dp), tint = if (c.luminance() > 0.5f) Color.Black else Color.White)
                    }
                    else -> Box(Modifier.size(26.dp).clip(CircleShape).border(2.dp, scheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Add, "Give this role", Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
