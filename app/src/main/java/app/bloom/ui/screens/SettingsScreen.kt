package app.bloom.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import app.bloom.AppViewModel
import app.bloom.core.Plugin
import app.bloom.ui.components.*
import app.bloom.ui.theme.LocalLook
import app.bloom.ui.theme.ColorRole
import app.bloom.ui.theme.Look
import app.bloom.ui.theme.get
import app.bloom.ui.theme.Motion
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    var settingsQuery by rememberSaveable { mutableStateOf("") }
    fun show(words: String) = settingsQuery.isBlank() || settingsQuery.trim().lowercase().split(' ').all { w -> words.contains(w) }
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val list = vm.plugins.filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
    LazyColumn(
        Modifier.fillMaxSize().background(scheme.surface),
        contentPadding = WindowInsets.systemBars.union(WindowInsets.ime).asPaddingValues(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Text("Settings", style = MaterialTheme.typography.displaySmall)
            }
        }
        // search (like Mochy's customize page): filters the sections below
        item {
            OutlinedTextField(
                settingsQuery, { settingsQuery = it },
                placeholder = { Text("Search settings") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (settingsQuery.isNotEmpty()) IconButton(onClick = { settingsQuery = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                singleLine = true, shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (show("customize animation speed motion blur glass rounding corners status bar entrance smooth font emoji apple music now playing icons performance")) item {
            // the full Customize page (Mochy-style cards)
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(LocalLook.current.cardRadius))
                    .background(scheme.primaryContainer).clickable { vm.screen = app.bloom.Screen.Customize }.padding(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(28.dp), tint = scheme.onPrimaryContainer)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Customize", style = MaterialTheme.typography.titleLarge, color = scheme.onPrimaryContainer)
                    Text("Rounding, motion, fonts, emoji, now playing", style = MaterialTheme.typography.bodySmall, color = scheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onPrimaryContainer)
            }
        }
        item { SectionTitle("Account") }
        item {
            val mode = vm.authMode
            SettingRow(
                Icons.Rounded.AccountCircle,
                if (mode == app.bloom.core.AuthMode.ROOT) "Signed in as ${vm.me?.name ?: ""}" else "Demo mode",
                if (mode == app.bloom.core.AuthMode.ROOT) "Root account" else "Showing sample data",
            ) {
                FilledTonalButton(onClick = { vm.signOut() }) { Text(if (mode == app.bloom.core.AuthMode.ROOT) "Sign out" else "Sign in") }
            }
        }
        item { SectionTitle("Appearance") }
        item {
            ExpandableSection("Theme", vm.look.label, Icons.Rounded.Palette, initiallyOpen = true) {
                Look.entries.forEach { l -> LookCard(l, selected = vm.look == l, inset = false) { vm.chooseLook(l) } }
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Pure black (AMOLED)", style = MaterialTheme.typography.bodyLarge)
                        Text("Saves battery on OLED screens", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    Switch(vm.amoled, { vm.chooseAmoled(it) })
                }
            }
        }
        if (show("chat time compact swipe reply channel icons descriptions gifs text size typing background")) item { ChatSection(vm) }
        if (show("notifications mentions dms replies everyone sound private background")) item { NotificationSection(vm) }
        if (show("logs crashes errors diagnostics debug")) item { DiagnosticsSection(vm) }
        if (show("colors colours palette accent")) item { ColorsSection(vm) }
        if (vm.look == Look.MATERIAL) item { BackgroundShapesSection(vm) }
        item {
            ExpandableSection("GIFs", if (vm.klipyKey.isBlank()) "Add a free Klipy API key to search GIFs" else "Search is on · favourites sync with Root", Icons.Rounded.Gif) {
                var key by remember { mutableStateOf(vm.klipyKey) }
                Text(
                    "GIF search uses Klipy. Get a free API key at klipy.com/developers and paste it here. Your favourite GIFs are stored on your Root account.",
                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
                )
                OutlinedTextField(key, { key = it }, label = { Text("Klipy API key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FilledTonalButton(onClick = { vm.saveKlipyKey(key) }, enabled = key != vm.klipyKey) { Text("Save key") }
            }
        }
        item { SectionTitle("Profile") }
        item { ProfileRow(vm) }
        item { SectionTitle("Plugins") }
        item {
            Text(
                "${vm.plugins.count { it.enabled }} of ${vm.plugins.size} enabled · core plugins run in the Rust engine",
                style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp),
            )
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("Search plugins") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 4.dp),
            )
        }
        items(list, key = { it.id }) { p -> PluginCard(vm, p, Modifier.animateItem(placementSpec = Motion.spatial())) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionTitle(t: String) {
    Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp))
}

@Composable
private fun SettingRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, trailing: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(LocalLook.current.cardRadius))
            .background(scheme.surfaceContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = scheme.onSurfaceVariant)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        trailing()
    }
}

/** Preview colours for each look (Material uses the live wallpaper palette). */
@Composable
private fun swatches(l: Look): List<Color> = when (l) {
    Look.MATERIAL -> MaterialTheme.colorScheme.run { listOf(primary, tertiary, secondaryContainer, surfaceContainerHigh) }
    Look.DISCORD -> listOf(Color(0xFF5865F2), Color(0xFF23A55A), Color(0xFF313338), Color(0xFF1E1F22))
    Look.ROOT -> listOf(Color(0xFF6C8CFF), Color(0xFF55D6BE), Color(0xFF181E2B), Color(0xFF0B0E15))
}

@Composable
private fun LookCard(l: Look, selected: Boolean, inset: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val border by animateDpAsState(if (selected) 2.dp else 0.dp, Motion.spatial(), label = "lb")
    val src = remember { MutableInteractionSource() }
    val radius = LocalLook.current.cardRadius
    Row(
        Modifier.padding(horizontal = if (inset) 12.dp else 0.dp).fillMaxWidth().pressScale(src, 0.97f)
            .clip(RoundedCornerShape(radius))
            .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh)
            .border(border.coerceAtLeast(0.dp), scheme.primary, RoundedCornerShape(radius))
            .clickable(src, null, onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // mini preview: the four key colours as overlapping dots
        Box(Modifier.width(76.dp).height(40.dp)) {
            swatches(l).forEachIndexed { i, c ->
                Box(
                    Modifier.offset(x = (i * 12).dp).size(40.dp).clip(CircleShape)
                        .border(2.dp, scheme.surfaceContainer, CircleShape).background(c),
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(l.label, style = MaterialTheme.typography.titleMedium, color = if (selected) scheme.onSecondaryContainer else scheme.onSurface)
            Text(l.blurb, style = MaterialTheme.typography.bodySmall, color = if (selected) scheme.onSecondaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant)
        }
        AnimatedVisibility(selected, enter = scaleIn(Motion.spatialFast()) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(Icons.Rounded.CheckCircle, "Selected", tint = scheme.primary)
        }
    }
}

@Composable
private fun ProfileRow(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    val me = vm.me ?: return
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setAvatar(uri) }
    Row(
        Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(LocalLook.current.cardRadius))
            .background(scheme.surfaceContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(me.displayName, me.hue, 56.dp, userId = me.id, status = me.status)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(me.displayName, style = MaterialTheme.typography.titleMedium)
            Text("Profile picture", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        if (vm.authMode == app.bloom.core.AuthMode.ROOT) FilledTonalButton(onClick = { vm.screen = app.bloom.Screen.EditProfile }) { Text("Edit profile") }
        else FilledTonalButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Change") }
        if (vm.myAvatar != null) IconButton(onClick = { vm.setAvatar(null) }) { Icon(Icons.Rounded.Delete, "Remove picture") }
    }
}

@Composable
private fun PluginCard(vm: AppViewModel, p: Plugin, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val spec = LocalLook.current
    var expanded by remember { mutableStateOf(false) }
    val bg by animateColorAsState(if (p.enabled) scheme.secondaryContainer else scheme.surfaceContainer, Motion.effects(), label = "pbg")
    val fg = if (p.enabled) scheme.onSecondaryContainer else scheme.onSurface
    val sub = if (p.enabled) scheme.onSecondaryContainer.copy(alpha = 0.8f) else scheme.onSurfaceVariant
    val src = remember { MutableInteractionSource() }
    val configurable = p.id == "message_tags" || p.id == "text_replace"

    Column(
        modifier.padding(horizontal = 12.dp).fillMaxWidth().pressScale(src, 0.98f)
            // background only (no clip) so the icon's glow isn't cut off
            .background(bg, RoundedCornerShape(spec.cardRadius))
            .clickable(src, null) { if (configurable) expanded = !expanded else vm.togglePlugin(p.id, !p.enabled) }
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .animateContentSize(Motion.spatial()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Living icon while enabled: slowly turns & morphs; settles to a rounded square when off.
            PluginIcon(p.id, p.enabled, p.isCore)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, color = fg)
                Text(p.description, style = MaterialTheme.typography.bodySmall, color = sub)
                if (configurable) Text(if (expanded) "Tap to close" else "Tap to configure", style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
            Switch(p.enabled, { vm.togglePlugin(p.id, it) })
        }
        if (expanded && configurable) {
            Spacer(Modifier.height(12.dp))
            when (p.id) {
                "message_tags" -> TagsEditor(vm, p)
                "text_replace" -> RulesEditor(vm, p)
            }
        }
    }
}

@Composable
private fun TagsEditor(vm: AppViewModel, p: Plugin) {
    val tags = p.config?.optJSONObject("tags") ?: JSONObject()
    var name by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    fun save(t: JSONObject) = vm.setPluginConfig(p.id, JSONObject().put("tags", t))
    tags.keys().asSequence().toList().forEach { k ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("{{$k}}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(tags.getString(k), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            IconButton(onClick = { save(JSONObject(tags.toString()).apply { remove(k) }) }) { Icon(Icons.Rounded.Close, "Remove") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(name, { name = it.filter { c -> c.isLetterOrDigit() } }, Modifier.weight(0.35f), placeholder = { Text("name") }, singleLine = true)
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(body, { body = it }, Modifier.weight(0.65f), placeholder = { Text("text") }, singleLine = true)
        IconButton(enabled = name.isNotBlank() && body.isNotBlank(), onClick = {
            save(JSONObject(tags.toString()).put(name, body)); name = ""; body = ""
        }) { Icon(Icons.Rounded.Add, "Add") }
    }
}

@Composable
private fun RulesEditor(vm: AppViewModel, p: Plugin) {
    val rules = p.config?.optJSONArray("rules") ?: JSONArray()
    var find by remember { mutableStateOf("") }
    var repl by remember { mutableStateOf("") }
    fun save(r: JSONArray) = vm.setPluginConfig(p.id, JSONObject().put("rules", r))
    for (i in 0 until rules.length()) {
        val r = rules.getJSONObject(i)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${r.optString("find")}  →  ${r.optString("replace")}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { save(JSONArray(rules.toString()).apply { remove(i) }) }) { Icon(Icons.Rounded.Close, "Remove") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(find, { find = it }, Modifier.weight(1f), placeholder = { Text("find") }, singleLine = true)
        Spacer(Modifier.width(6.dp))
        OutlinedTextField(repl, { repl = it }, Modifier.weight(1f), placeholder = { Text("replace") }, singleLine = true)
        IconButton(enabled = find.isNotEmpty(), onClick = {
            save(JSONArray(rules.toString()).put(JSONObject().put("find", find).put("replace", repl))); find = ""; repl = ""
        }) { Icon(Icons.Rounded.Add, "Add") }
    }
}

@Composable
private fun ColorsSection(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    var editing by remember { mutableStateOf<ColorRole?>(null) }
    val count = vm.currentOverrides.size
    ExpandableSection("Colors", if (count == 0) "Theme defaults · tap to customise every colour" else "$count custom for ${vm.look.label}", Icons.Rounded.ColorLens) {
        ColorRole.entries.groupBy { it.group }.forEach { (group, roles) ->
            Text(group, style = MaterialTheme.typography.labelLarge, color = scheme.primary, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
            roles.forEach { role ->
                val c = scheme.get(role)
                val custom = role in vm.currentOverrides
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(LocalLook.current.rowRadius)).clickable { editing = role }.padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(32.dp).clip(CircleShape).border(1.dp, scheme.outlineVariant, CircleShape).background(c))
                    Spacer(Modifier.width(12.dp))
                    Text(role.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    if (custom) Text("custom", style = MaterialTheme.typography.labelSmall, color = scheme.tertiary)
                }
            }
        }
        if (count > 0) OutlinedButton(onClick = { vm.resetColors() }, modifier = Modifier.fillMaxWidth()) { Text("Reset all colours for ${vm.look.label}") }
    }
    editing?.let { role ->
        ColorPickerDialog(
            role.label, scheme.get(role),
            onDismiss = { editing = null },
            onReset = { vm.setColor(role, null); editing = null },
            onPick = { vm.setColor(role, it); editing = null },
        )
    }
}

@Composable
private fun BackgroundShapesSection(vm: AppViewModel) {
    val b = vm.bgShapes
    ExpandableSection("Background shapes", if (b.enabled) "${b.count} shapes · blur ${b.blur.toInt()}" else "Off", Icons.Rounded.BubbleChart) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Show shapes", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(b.enabled, { vm.updateBgShapes(b.copy(enabled = it)) })
        }
        if (b.enabled) {
            SliderRow("Number of shapes", b.count.toFloat(), 1f..16f, { it.toInt().toString() }, steps = 14) { vm.updateBgShapes(b.copy(count = it.toInt())) }
            SliderRow("Rotate speed", b.rotateSpeed, 0f..90f, { "${it.toInt()}°/s" }) { vm.updateBgShapes(b.copy(rotateSpeed = it)) }
            SliderRow("Morph speed", b.morphSpeed, 0f..60f, { if (it < 0.5f) "off" else "${it.toInt()}/min" }) { vm.updateBgShapes(b.copy(morphSpeed = it)) }
            SliderRow("Blur", b.blur, 0f..80f, { "${it.toInt()} dp" }) { vm.updateBgShapes(b.copy(blur = it)) }
            SliderRow("Opacity", b.opacity, 0.05f..1f, { "${(it * 100).toInt()}%" }) { vm.updateBgShapes(b.copy(opacity = it)) }
        }
    }
}


@Composable
private fun DiagnosticsSection(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    var entries by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { entries = vm.diagnostics() }
    val crashes = entries?.count { it.second.startsWith("crash") } ?: 0
    val total = entries?.size ?: 0
    var open by remember { mutableStateOf(false) }
    ExpandableSection("Logs & crashes", if (total == 0) "Nothing logged" else "$total entries" + if (crashes > 0) " · $crashes crash${if (crashes > 1) "es" else ""}" else "", Icons.Rounded.BugReport) {
        Text("Failed requests to Root, errors and crashes are recorded here (on this phone only).", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { reload++; open = true }) { Text("View logs") }
            OutlinedButton(onClick = { vm.clearDiagnostics(); reload++ }, enabled = total > 0) { Text("Clear") }
        }
    }
    if (open) {
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val fmt = remember { java.text.SimpleDateFormat("MMM d HH:mm:ss", java.util.Locale.getDefault()) }
        val text = entries.orEmpty().joinToString("\n\n") { (ts, msg) -> "[" + fmt.format(java.util.Date(ts * 1000)) + "] " + msg }
        androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize().padding(12.dp), shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerHigh) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Logs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)) }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                        IconButton(onClick = {
                            ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "Share logs"))
                        }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton(onClick = { open = false }) { Icon(Icons.Rounded.Close, "Close") }
                    }
                    if (entries.isNullOrEmpty()) Text("Nothing logged yet.", color = scheme.onSurfaceVariant)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                        items(entries.orEmpty().size) { i ->
                            val (ts, msg) = entries!![i]
                            val crash = msg.startsWith("crash") || msg.startsWith("panic")
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                Text(fmt.format(java.util.Date(ts * 1000)), style = MaterialTheme.typography.labelSmall, color = if (crash) scheme.error else scheme.primary)
                                androidx.compose.foundation.text.selection.SelectionContainer {
                                    Text(msg, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace), maxLines = if (crash) 40 else 6)
                                }
                            }
                            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationSection(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    val styleLabel = mapOf("full" to "Picture + message", "message" to "Message only", "who" to "Who pinged you", "private" to "Private")
    ExpandableSection("Notifications", if (!vm.notifOn) "Off" else styleLabel[vm.notifStyle] ?: "", Icons.Rounded.Notifications) {
        @Composable
        fun toggle(title: String, sub: String, on: Boolean, key: String, enabled: Boolean = true) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).graphicsLayer { alpha = if (enabled) 1f else 0.45f }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(on, { vm.setPref(key, it) }, enabled = enabled)
            }
        }
        toggle("Notifications", "Show message notifications from Root", vm.notifOn, "notif_on")
        val on = vm.notifOn
        Text("Notify me for", style = MaterialTheme.typography.titleSmall, color = scheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        toggle("Direct messages", "DMs and group chats", vm.notifDms, "notif_dms", on)
        toggle("Mentions", "When someone @mentions you in a server", vm.notifMentions, "notif_mentions", on)
        toggle("Replies", "When someone replies to your message", vm.notifReplies, "notif_replies", on)
        toggle("@everyone / @here", "Server-wide pings", vm.notifEveryone, "notif_everyone", on)
        toggle("All server messages", "Every message in your servers (noisy)", vm.notifServer, "notif_server", on)
        Text("What to show", style = MaterialTheme.typography.titleSmall, color = scheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            styleLabel.forEach { (k, l) -> FilterChip(vm.notifStyle == k, { vm.setPref("notif_style", k) }, { Text(l) }, enabled = on) }
        }
        Text(
            when (vm.notifStyle) {
                "full" -> "Their profile picture, name and the message"
                "message" -> "Their name and the message, no picture"
                "who" -> "Only who pinged you and where, e.g. \"alex mentioned you in Cozy Corner · #general\""
                else -> "Just \"New message\": no names or text, hidden on the lock screen"
            },
            style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp),
        )
        toggle("Show server & channel", "Put \"Server · #channel\" on server notifications", vm.notifPlace, "notif_place", on && vm.notifStyle != "private")
        Text("Delivery", style = MaterialTheme.typography.titleSmall, color = scheme.primary, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        toggle("Stay connected in background", "Keeps notifications coming after you leave the app (adds a silent, minimised notification)", vm.notifBackground, "notif_background", on)
        val ctx = androidx.compose.ui.platform.LocalContext.current
        TextButton(onClick = {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }) { Text("Sound, vibration & lock screen (Android settings)") }
    }
}

@Composable
private fun ChatSection(vm: AppViewModel) {
    val scheme = MaterialTheme.colorScheme
    ExpandableSection("Chat", (if (vm.clock12h) "12-hour" else "24-hour") + " time · text ${(vm.textScale * 100).toInt()}%" + if (vm.compact) " · compact" else "", Icons.Rounded.ChatBubble) {
        @Composable
        fun toggle(title: String, sub: String, on: Boolean, key: String) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                Switch(on, { vm.setPref(key, it) })
            }
        }
        toggle("12-hour time", "Show 3:45 PM instead of 15:45", vm.clock12h, "clock_12h")
        toggle("Compact messages", "Smaller avatars and tighter spacing", vm.compact, "compact")
        toggle("Swipe to reply", "Swipe a message right-to-left to reply", vm.swipeReply, "swipe_reply")
        toggle("Channel icons", "Each channel's own picture (like Root), with a small type badge. Off = plain # symbols", vm.channelIcons, "channel_icons")
        toggle("Channel descriptions", "Show the channel's description under its name", vm.channelTopics, "channel_topics")
        toggle("Autoplay GIFs", "Animate GIFs in chat (off = still frame)", vm.autoplayGifs, "autoplay_gifs")
        SliderRow("Message text size", vm.textScale, 0.8f..1.4f, { "${(it * 100).toInt()}%" }) { vm.setPref("text_scale", it) }
        Text("Typing indicator", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            listOf("avatars" to "Pictures", "shapes" to "Shape", "dots" to "Dots", "text" to "Text").forEach { (k, l) ->
                FilterChip(vm.typingStyle == k, { vm.setPref("typing_style", k) }, { Text(l) })
            }
        }
        Text("Chat background", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        val pickBg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { u -> vm.setChatBgImage(u) } }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
            FilterChip(vm.chatBg == "none", { vm.setPref("chat_bg", "none") }, { Text("None") })
            FilterChip(vm.chatBg == "gradient", { vm.setPref("chat_bg", "gradient") }, { Text("Gradient") })
            FilterChip(vm.chatBg == "pattern", { vm.setPref("chat_bg", "pattern") }, { Text("Pattern") })
            FilterChip(vm.chatBg == "image", { pickBg.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, { Text(if (vm.chatBg == "image") "Change image" else "Image…") })
        }
        if (vm.chatBg == "image") {
            SliderRow("Dim", vm.chatBgDim, 0f..0.9f, { "${(it * 100).toInt()}%" }) { vm.setPref("chat_bg_dim", it) }
            SliderRow("Blur", vm.chatBgBlur, 0f..30f, { "${it.toInt()} dp" }) { vm.setPref("chat_bg_blur", it) }
            TextButton(onClick = { vm.setChatBgImage(null) }) { Text("Remove image") }
        }
    }
}


/** Soft rounded icon per plugin, with a gentle glow when enabled. */
@Composable
private fun PluginIcon(id: String, enabled: Boolean, core: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val icon = when (id) {
        "mutual_view" -> Icons.Rounded.Diversity3
        "message_logger" -> Icons.Rounded.History
        "clear_urls" -> Icons.Rounded.LinkOff
        "text_replace" -> Icons.Rounded.FindReplace
        "message_tags" -> Icons.Rounded.Sell
        "more_kaomoji" -> Icons.Rounded.SentimentSatisfied
        "send_timestamps" -> Icons.Rounded.Schedule
        "unindent" -> Icons.Rounded.FormatIndentDecrease
        "silent_typing" -> Icons.Rounded.KeyboardHide
        "no_reply_mention" -> Icons.Rounded.AlternateEmail
        "friends_since" -> Icons.Rounded.Cake
        "platform_indicators" -> Icons.Rounded.Devices
        "who_reacted" -> Icons.Rounded.AddReaction
        "view_raw" -> Icons.Rounded.Code
        "copy_user_urls" -> Icons.Rounded.Link
        "read_all" -> Icons.Rounded.DoneAll
        "pin_dms" -> Icons.Rounded.PushPin
        "relative_timestamps" -> Icons.Rounded.Update
        else -> if (core) Icons.Rounded.Memory else Icons.Rounded.Palette
    }
    val t by animateFloatAsState(if (enabled) 1f else 0f, Motion.effects(), label = "pi")
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier.padding(4.dp).size(44.dp)
            .softGlow(shape, scheme.primary, 10.dp, 0.45f * t)
            .background(androidx.compose.ui.graphics.lerp(scheme.surfaceContainerHighest, scheme.primary, t), shape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = androidx.compose.ui.graphics.lerp(scheme.onSurfaceVariant, scheme.onPrimary, t), modifier = Modifier.size(22.dp)) }
}
