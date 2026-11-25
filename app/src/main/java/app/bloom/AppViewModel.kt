package app.bloom

import app.bloom.notify.KeepAliveService
import androidx.compose.runtime.snapshotFlow
import app.bloom.ui.theme.Ui
import app.bloom.ui.custom.Fonts
import app.bloom.ui.custom.EmojiPacks
import androidx.compose.runtime.mutableIntStateOf
import app.bloom.notify.Notifier
import app.bloom.ui.screens.plainText
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.bloom.core.*
import app.bloom.core.AuthMode
import app.bloom.voice.VoiceManager
import app.bloom.ui.screens.Clock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import app.bloom.ui.theme.BgShapes
import app.bloom.ui.theme.ColorRole
import app.bloom.ui.theme.Look
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.TimeZone

/** A picked file waiting in the preview tray. */
private const val PUA_BASE = 0xE000

data class PendingAttachment(val uri: Uri, val name: String, val mime: String, val width: Int = 0, val height: Int = 0) {
    val isImage get() = mime.startsWith("image/")
    val isVideo get() = mime.startsWith("video/")
}

sealed interface Screen {
    data object Chat : Screen
    data object Friends : Screen
    data object Settings : Screen
    data object EditProfile : Screen
    data object Notifications : Screen
    data object Customize : Screen
    data object You : Screen
    data class ServerRoles(val serverId: Long) : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    /**
     * Every coroutine here runs with this: a failed Root call (or any bug) becomes a toast
     * and a log line instead of crashing the app.
     */
    private val safe = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        android.util.Log.e("Bloom", "coroutine failed", e)
        errorState.value = e.message ?: e.javaClass.simpleName
    }
    companion object {
        /** Bloom is on screen (set by MainActivity); used to skip notifications for the open chat. */
        @Volatile var visible = false
    }
    var ready by mutableStateOf(false); private set
    var me by mutableStateOf<User?>(null); private set
    val users = mutableStateMapOf<Long, User>()
    var servers by mutableStateOf<List<Server>>(emptyList()); private set
    var friends by mutableStateOf<List<User>>(emptyList()); private set
    var dms by mutableStateOf<List<DirectMessage>>(emptyList()); private set
    var plugins by mutableStateOf<List<Plugin>>(emptyList()); private set

    var screen by mutableStateOf<Screen>(Screen.Chat)
    var selectedServerId by mutableStateOf<Long?>(null); private set
    var selectedChannelId by mutableStateOf<Long?>(null); private set
    val messages = mutableStateListOf<Message>()
    var loadingMessages by mutableStateOf(false); private set

    /** channelId -> (userId -> expiry millis) */
    val typing = mutableStateMapOf<Long, Map<Long, Long>>()

    var profileUserId by mutableStateOf<Long?>(null); private set
    var profileMutuals by mutableStateOf<Mutuals?>(null); private set
    /** Messages you're replying to (you can pick several). */
    val replying = mutableStateListOf<Message>()
    var replyingTo: Message?
        get() = replying.lastOrNull()
        set(v) { if (v == null) replying.clear() else if (replying.none { it.id == v.id }) replying.add(v) else replying.removeAll { it.id == v.id } }
    var editing by mutableStateOf<Message?>(null)

    // ---------- Composer media ----------
    /** Picked but not yet sent; shown as a preview tray above the message box. */
    val pending = mutableStateListOf<PendingAttachment>()
    var uploading by mutableStateOf(false); private set

    /** Media being uploaded: shown as skeleton rows with a progress bar until sent. */
    class Outgoing(val key: Long, val items: List<PendingAttachment>, val text: String) {
        var progress by mutableStateOf(0f)
    }
    val outgoing = mutableStateListOf<Outgoing>()

    /** Uploads with live progress (HttpURLConnection, fixed-length streaming). Returns Root's token. */
    private suspend fun uploadWithProgress(file: File, name: String, mime: String, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val target = Core.social("upload_target") as JSONObject
        val bytes = file.length()
        fun digest(alg: String) = java.security.MessageDigest.getInstance(alg).let { md ->
            file.inputStream().use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            android.util.Base64.encodeToString(md.digest(), android.util.Base64.NO_WRAP)
        }
        val conn = (java.net.URL(target.getString("url")).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setFixedLengthStreamingMode(bytes)
            setRequestProperty("authorization", "Bearer " + target.getString("token"))
            setRequestProperty("content-md5", digest("MD5"))
            setRequestProperty("x-amz-meta-root-content-sha256", digest("SHA-256"))
            setRequestProperty("x-amz-meta-root-file-modification", java.time.Instant.ofEpochMilli(file.lastModified()).toString())
            setRequestProperty("x-amz-meta-root-file-name", name)
            setRequestProperty("content-type", mime.ifBlank { "application/octet-stream" })
            setRequestProperty("user-agent", "Bloom/${BuildConfig.VERSION_NAME} (Android)")
        }
        conn.outputStream.use { o ->
            file.inputStream().use { i ->
                val buf = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    o.write(buf, 0, n); sent += n
                    onProgress((sent.toFloat() / bytes).coerceIn(0f, 0.98f))
                }
            }
        }
        val code = conn.responseCode
        val body = try {
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        } finally { conn.disconnect() }
        if (code !in 200..299) error("Upload failed (HTTP $code)")
        runCatching { org.json.JSONTokener(body).nextValue() as String }.getOrElse { body.trim('"') }
    }
    var gifResults by mutableStateOf<List<Gif>>(emptyList()); private set
    var gifFavorites by mutableStateOf<List<String>>(emptyList()); private set
    var gifError by mutableStateOf<String?>(null); private set
    var customEmoji by mutableStateOf<List<CustomEmoji>>(emptyList()); private set
    var emojiCatalog by mutableStateOf<List<EmojiEntry>>(emptyList()); private set
    /** Voice/video calls. */
    val voice = VoiceManager(app)

    var notifications by mutableStateOf<List<Notification>>(emptyList()); private set
    var loadingNotifications by mutableStateOf(false); private set
    fun loadNotifications() = viewModelScope.launch(safe) {
        loadingNotifications = true
        notifications = runCatching { Core.notifications() }.getOrElse { error = it.message; notifications }
        runCatching { Core.users() }.getOrNull()?.let { putUsers(it) }
        loadingNotifications = false
    }
    /** Opens the channel/DM a notification points at and highlights the message. */
    fun openNotification(n: Notification) {
        val ch = n.channelId ?: return
        val server = servers.firstOrNull { s -> s.channels.any { it.id == ch } }
        when {
            server != null -> { selectedServerId = server.id; openChannel(ch) }
            dms.any { it.id == ch } -> openDm(ch)
            else -> { selectedServerId = null; openChannel(ch) }
        }
        screen = Screen.Chat
        n.messageId?.let { id -> viewModelScope.launch(safe) { delay(900); flash(id) } }
    }

    // ---- Customize: fonts & now playing ----
    var fontLoading by mutableStateOf<String?>(null); private set
    /** bumps when a font finished downloading/importing (previews reload) */
    var fontsReady by mutableIntStateOf(0); private set
    var npOn by mutableStateOf(false); private set
    val npApps = mutableStateListOf<String>()

    fun chooseFont(id: String) = viewModelScope.launch(safe) {
        val ctx = getApplication<Application>()
        if (!Fonts.isDownloaded(ctx, id)) {
            fontLoading = id
            val r = Fonts.download(ctx, id)
            fontLoading = null
            fontsReady++
            if (r.isFailure) { error = "Couldn't download that font: ${r.exceptionOrNull()?.message}"; return@launch }
        }
        Ui.fontId = id
        Ui.fontFamily = withContext(Dispatchers.IO) { Fonts.family(ctx, id, Ui.fontBold) }
        Core.setSetting("ui_font", id)
    }

    fun importFont(uri: Uri, name: String, done: () -> Unit) = viewModelScope.launch(safe) {
        Fonts.import(getApplication(), uri, name)
            .onSuccess { id -> fontsReady++; done(); chooseFont(id) }
            .onFailure { error = "That file isn't a font Android can read" }
    }

    fun toggleNpApp(pkg: String) {
        if (pkg in npApps) npApps.remove(pkg) else npApps.add(pkg)
        viewModelScope.launch(safe) { Core.setSetting("np_apps", org.json.JSONArray(npApps.toList())) }
    }

    // ---- per-person settings (long-press a friend / DM) ----
    val nicknames = mutableStateMapOf<Long, String>()
    /** "default" | "all" | "mentions" | "none" */
    val userNotif = mutableStateMapOf<Long, String>()
    /** epoch ms; -1 = until turned back on */
    val muteUntil = mutableStateMapOf<Long, Long>()
    val pinnedDms = mutableStateListOf<Long>()
    /** image url (without signature) → width/height of the decoded image */
    val mediaRatios = mutableStateMapOf<String, Float>()
    private val realNames = mutableMapOf<Long, String>()

    /** All user updates go through here so nicknames stick. */
    private fun putUsers(list: List<User>) = list.forEach { u ->
        realNames[u.id] = u.displayName
        users[u.id] = nicknames[u.id]?.let { u.copy(displayName = it) } ?: u
    }

    /** server id → mentionable roles (id, name, color) */
    val roles = mutableStateMapOf<Long, List<Triple<String, String, String>>>()
    fun loadRoles(serverId: Long) {
        if (serverId in roles) return
        roles[serverId] = emptyList()
        viewModelScope.launch(safe) {
            runCatching { Core.social("roles", JSONObject().put("server_id", serverId)) as org.json.JSONArray }.onSuccess { a ->
                roles[serverId] = (0 until a.length()).map { i -> a.getJSONObject(i).let { Triple(it.getString("id"), it.getString("name"), it.optString("color")) } }
            }
        }
    }

    // ---- server roles ----
    data class Role(val id: String, val name: String, val color: String, val members: Int, val mentionable: Boolean, val selfAssignable: Boolean)
    val serverRoles = mutableStateMapOf<Long, List<Role>>()
    /** (server, user) → role ids */
    val memberRoles = mutableStateMapOf<Pair<Long, Long>, List<String>>()

    fun loadServerRoles(serverId: Long, force: Boolean = false) = viewModelScope.launch(safe) {
        if (!force && serverRoles[serverId]?.isNotEmpty() == true) return@launch
        runCatching { Core.social("roles_all", JSONObject().put("server_id", serverId)) as org.json.JSONArray }
            .onSuccess { a ->
                serverRoles[serverId] = (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    Role(it.getString("id"), it.getString("name"), it.optString("color"), it.optInt("members"), it.optBoolean("mentionable"), it.optBoolean("self_assignable"))
                } }
            }.onFailure { error = "Couldn't load roles: ${it.message}" }
    }

    fun loadMemberRoles(serverId: Long, userId: Long) = viewModelScope.launch(safe) {
        loadServerRoles(serverId)
        runCatching { Core.social("member_roles", JSONObject().put("server_id", serverId).put("user_id", userId)) as org.json.JSONArray }
            .onSuccess { a -> memberRoles[serverId to userId] = (0 until a.length()).map { a.getString(it) } }
    }

    private fun roleOp(op: String, args: JSONObject, serverId: Long, after: suspend () -> Unit = {}) = viewModelScope.launch(safe) {
        runCatching { Core.social(op, args.put("server_id", serverId)) }
            .onSuccess { loadServerRoles(serverId, force = true).join(); after() }
            .onFailure { error = "Root said no: ${it.message}" }
    }
    fun createRole(serverId: Long, name: String, color: String) = roleOp("role_create", JSONObject().put("name", name).put("color", color), serverId)
    fun editRole(serverId: Long, roleId: String, name: String, color: String, mentionable: Boolean, selfAssignable: Boolean) =
        roleOp("role_edit", JSONObject().put("role_id", roleId).put("name", name).put("color", color).put("mentionable", mentionable).put("self_assignable", selfAssignable), serverId)
    fun deleteRole(serverId: Long, roleId: String) = roleOp("role_delete", JSONObject().put("role_id", roleId), serverId)
    /** role ids with a change in flight (spinner in the picker) */
    val pendingRoles = mutableStateListOf<String>()

    /** Optimistic: the picker updates at once; flips back if Root refuses. */
    fun setMemberRole(serverId: Long, userId: Long, roleId: String, on: Boolean) {
        val key = serverId to userId
        val before = memberRoles[key].orEmpty()
        memberRoles[key] = if (on) (before + roleId).distinct() else before - roleId
        pendingRoles.add(roleId)
        viewModelScope.launch(safe) {
            runCatching {
                Core.social(if (on) "member_role_add" else "member_role_remove", JSONObject().put("server_id", serverId).put("role_id", roleId).put("user_id", userId))
            }.onFailure { memberRoles[key] = before; error = "Root said no: ${it.message}" }
            pendingRoles.remove(roleId)
        }
    }

    /** People to offer after "@": this chat's authors first, then friends, then anyone known. */
    fun mentionCandidates(query: String): List<User> {
        val q = query.lowercase()
        val recent = messages.asReversed().map { it.authorId }.distinct()
        val dm = dms.firstOrNull { it.id == selectedChannelId }?.userIds.orEmpty()
        val order = (dm + recent + friends.map { it.id } + users.keys).distinct().filter { it != me?.id }
        return order.mapNotNull { users[it] }
            .filter { q.isEmpty() || it.displayName.lowercase().contains(q) || it.name.lowercase().contains(q) }
            .take(8)
    }

    /** Opens a profile from an @name in a message. */
    fun openMention(name: String) {
        val u = users.values.firstOrNull { it.name.equals(name, true) || realName(it.id).equals(name, true) || it.displayName.equals(name, true) }
        if (u != null) openProfile(u.id)
    }

    fun realName(id: Long) = realNames[id] ?: users[id]?.displayName ?: ""

    private fun saveMap(key: String, m: Map<Long, Any>) = viewModelScope.launch(safe) {
        Core.setSetting(key, JSONObject().apply { m.forEach { (k, v) -> put(k.toString(), v) } })
    }

    fun setNickname(id: Long, name: String?) {
        val n = name?.trim().orEmpty()
        if (n.isEmpty()) nicknames.remove(id) else nicknames[id] = n
        users[id]?.let { u -> users[id] = u.copy(displayName = nicknames[id] ?: realName(id)) }
        saveMap("nicknames", nicknames.toMap())
    }

    fun setUserNotif(id: Long, mode: String) {
        if (mode == "default") userNotif.remove(id) else userNotif[id] = mode
        saveMap("user_notif", userNotif.toMap())
    }

    /** minutes: null = until turned back on, 0 = unmute */
    fun muteUser(id: Long, minutes: Int?) {
        when (minutes) {
            0 -> muteUntil.remove(id)
            null -> muteUntil[id] = -1L
            else -> muteUntil[id] = System.currentTimeMillis() + minutes * 60_000L
        }
        saveMap("mute_until", muteUntil.toMap())
    }

    fun isMuted(id: Long): Boolean {
        val until = muteUntil[id] ?: return false
        return until == -1L || until > System.currentTimeMillis()
    }

    /** Most-used unicode emoji, most frequent first (Recent tab in the emoji panel). */
    var recentEmoji by mutableStateOf<List<String>>(emptyList()); private set
    private val emojiCounts = mutableMapOf<String, Int>()
    fun noteEmoji(e: String) {
        // custom emoji are session-local private-use characters; only real emoji are remembered
        if (e.isEmpty() || e.codePointAt(0) in 0xE000..0xF8FF) return
        emojiCounts[e] = (emojiCounts[e] ?: 0) + 1
        recentEmoji = emojiCounts.entries.sortedByDescending { it.value }.take(32).map { it.key }
        viewModelScope.launch(safe) { Core.setSetting("emoji_counts", JSONObject().apply { emojiCounts.entries.sortedByDescending { it.value }.take(64).forEach { (k, v) -> put(k, v) } }) }
    }

    /** Unsent text per chat. Saved (debounced) so drafts survive restarts. */
    val drafts = mutableStateMapOf<Long, String>()
    private var draftSave: Job? = null
    fun saveDraft(ch: Long, text: String) {
        if ((drafts[ch] ?: "") == text) return
        if (text.isBlank()) drafts.remove(ch) else drafts[ch] = text
        draftSave?.cancel()
        draftSave = viewModelScope.launch(safe) { delay(800); Core.setSetting("drafts", JSONObject().apply { drafts.forEach { (k, v) -> put(k.toString(), v) } }) }
    }

    fun togglePinDm(ch: Long) {
        if (ch in pinnedDms) pinnedDms.remove(ch) else pinnedDms.add(0, ch)
        if (!plugin("pin_dms")) togglePlugin("pin_dms", true)
        viewModelScope.launch(safe) { Core.setSetting("pinned_dms", org.json.JSONArray(pinnedDms.toList())) }
    }

    /** The 1:1 DM with someone, if there is one. */
    fun dmWith(userId: Long) = dms.firstOrNull { it.userIds == listOf(userId) }

    private val everyoneRx = Regex("⟦m:(All|Here|everyone|here)⟧", RegexOption.IGNORE_CASE)
    /** Mentions carry the name Root showed at the time: match your real name, username and current display name. */
    private fun mentionsMe(content: String): Boolean {
        val my = me ?: return false
        return listOfNotNull(realNames[my.id], my.displayName, my.name).filter { it.isNotBlank() }.distinct()
            .any { content.contains("⟦m:$it⟧", ignoreCase = true) }
    }
    private fun repliesToMe(m: Message): Boolean {
        val my = me?.id ?: return false
        return m.replyAuthor == my || m.moreReplies.any { it.author == my }
    }

    /** Message mentions you, pings everyone, or replies to you → highlighted in chat. */
    fun isForMe(m: Message): Boolean {
        if (me == null || m.authorId == me?.id) return false
        return mentionsMe(m.content) || repliesToMe(m) || everyoneRx.containsMatchIn(m.content)
    }

    /** Opens any channel or DM by id (from a system notification). */
    fun openFromNotification(ch: Long, messageId: Long) {
        val server = servers.firstOrNull { s -> s.channels.any { it.id == ch } }
        when {
            server != null -> { selectedServerId = server.id; openChannel(ch) }
            else -> openDm(ch)
        }
        screen = Screen.Chat
        Notifier.clear(getApplication(), ch)
        if (messageId != 0L) viewModelScope.launch(safe) { delay(900); flash(messageId) }
    }

    /** Decides whether a new message deserves a notification, following the settings. */
    private fun maybeNotify(m: Message, replyToMe: Boolean) {
        if (!notifOn || m.authorId == me?.id || m.system != null) return
        // already looking at it
        if (visible && m.channelId == selectedChannelId && screen == Screen.Chat) return
        val dm = dms.firstOrNull { it.id == m.channelId }
        val mentioned = mentionsMe(m.content)
        val everyone = everyoneRx.containsMatchIn(m.content)
        // per-person: muted, or their own notification level
        if (isMuted(m.authorId)) return
        when (userNotif[m.authorId]) {
            "none" -> return
            "mentions" -> if (!mentioned && !replyToMe) return
            "all" -> if (dm == null && !mentioned && !replyToMe && !everyone) {
                val author0 = users[m.authorId]
                val place0 = servers.firstNotNullOfOrNull { s -> s.channels.firstOrNull { it.id == m.channelId }?.let { "${s.name} · #${it.name}" } }
                viewModelScope.launch(safe) {
                    Notifier.show(getApplication(), Notifier.Item(m.channelId, m.id, author0?.displayName ?: "Someone", author0?.avatarUrl,
                        plainText(m.content), place0, Notifier.SERVER, "${author0?.displayName ?: "Someone"} posted" + (place0?.let { " in $it" } ?: "")),
                        Notifier.Prefs(notifStyle, notifPlace))
                }
                return
            }
        }
        val (why, label) = when {
            dm != null -> if (notifDms) Notifier.DMS to "messaged you" else return
            mentioned -> if (notifMentions) Notifier.MENTIONS to "mentioned you" else return
            replyToMe -> if (notifReplies) Notifier.MENTIONS to "replied to you" else return
            everyone -> if (notifEveryone) Notifier.PINGS to "pinged everyone" else return
            notifServer -> Notifier.SERVER to "posted"
            else -> return
        }
        val author = users[m.authorId]
        val place = if (dm != null) (if (dm.userIds.size > 1) dmName(dm) else null)
            else servers.firstNotNullOfOrNull { s -> s.channels.firstOrNull { it.id == m.channelId }?.let { "${s.name} · #${it.name}" } }
        val item = Notifier.Item(
            channelId = m.channelId, messageId = m.id,
            senderName = author?.displayName ?: "Someone", senderAvatarUrl = author?.avatarUrl,
            text = plainText(m.content).replace(Regex("⟦m:([^⟧]*)⟧"), "@$1").replace(Regex("⟦[a-z]:[^⟧]*⟧"), ""),
            place = place, why = why, whyLabel = "${author?.displayName ?: "Someone"} $label" + (place?.let { " in $it" } ?: ""),
        )
        viewModelScope.launch(safe) {
            Notifier.show(getApplication(), item, Notifier.Prefs(notifStyle, notifPlace))
        }
    }

    /** Crash reports (written by the crash handler) + the core's error log, newest first. */
    suspend fun diagnostics(): List<Pair<Long, String>> {
        val out = mutableListOf<Pair<Long, String>>()
        runCatching { Core.logs() }.getOrDefault("").lineSequence().filter { it.isNotBlank() }.forEach { l ->
            val parts = l.split('\t', limit = 3)
            out += (parts.getOrNull(0)?.toLongOrNull() ?: 0L) to (parts.getOrNull(1).orEmpty() + ": " + parts.getOrNull(2).orEmpty())
        }
        val crash = java.io.File(getApplication<Application>().filesDir, "logs/crash.log")
        if (crash.exists()) crash.readText().split("\n=== ").filter { it.isNotBlank() }.forEach { block ->
            val ts = block.removePrefix("=== ").substringBefore(' ').toLongOrNull() ?: 0L
            out += ts to ("crash: " + block.substringAfter('\n'))
        }
        return out.sortedByDescending { it.first }
    }

    fun clearDiagnostics() = viewModelScope.launch(safe) {
        Core.clearLogs()
        java.io.File(getApplication<Application>().filesDir, "logs/crash.log").delete()
    }

    fun markNotificationsRead() = viewModelScope.launch(safe) {
        runCatching { Core.social("notifications_read") }
        notifications = notifications.map { it.copy(viewed = true) }
    }
    fun respondFriend(n: Notification, accept: Boolean) = viewModelScope.launch(safe) {
        val user = n.userId ?: return@launch
        runCatching { Core.social("friend_respond", JSONObject().put("notification", n.id).put("user", user).put("accept", accept)) }
            .onSuccess { notifications = notifications - n; if (accept) { friends = Core.friends(); error = "Friend added" } }
            .onFailure { error = it.message }
    }
    fun addFriend(username: String) = viewModelScope.launch(safe) {
        runCatching { Core.social("friend_request", JSONObject().put("username", username)) }
            .onSuccess { error = "Friend request sent to $username" }.onFailure { error = it.message }
    }
    fun removeFriend(userId: Long) = viewModelScope.launch(safe) {
        runCatching { Core.social("friend_remove", JSONObject().put("user", userId)) }
            .onSuccess { friends = friends.filter { it.id != userId } }.onFailure { error = it.message }
    }
    fun createGroup(userIds: List<Long>) = viewModelScope.launch(safe) {
        runCatching { Core.social("dm_create", JSONObject().put("users", JSONArray(userIds))) }
            .onSuccess { r -> dms = Core.dms(); (r as? Number)?.toLong()?.let { openDm(it) } }
            .onFailure { error = it.message }
    }
    fun addToGroup(dm: Long, userIds: List<Long>) = viewModelScope.launch(safe) {
        runCatching { Core.social("dm_add", JSONObject().put("dm", dm).put("users", JSONArray(userIds))) }
            .onSuccess { dms = Core.dms() }.onFailure { error = it.message }
    }

    /** Live messages that mention you or reply to you (newest first). */
    val mentions = mutableStateListOf<Message>()

    /** Opens the channel/DM a message is in and jumps to it. */
    fun openMessage(m: Message) {
        val server = servers.firstOrNull { s -> s.channels.any { it.id == m.channelId } }
        if (server != null) { selectedServerId = server.id; openChannel(m.channelId) } else openDm(m.channelId)
        screen = Screen.Chat
        viewModelScope.launch(safe) { delay(600); flash(m.id) }
    }

    /** Ask the open chat to scroll to a message (and flash it). */
    var jumpTarget by mutableStateOf<Long?>(null)

    /** Message briefly highlighted after jumping to it from a reply. */
    var flashId by mutableStateOf<Long?>(null)
    fun flash(id: Long) = viewModelScope.launch(safe) { flashId = id; delay(1400); if (flashId == id) flashId = null }

    /** Image/video opened full screen. */
    var viewer by mutableStateOf<Attachment?>(null)
    private var errorState = mutableStateOf<String?>(null)
    /** Error shown in the toast; every one is also written to the diagnostics log. */
    var error: String?
        get() = errorState.value
        set(v) {
            errorState.value = v
            if (v != null) viewModelScope.launch(safe) { runCatching { Core.log("ui", v) } }
        }

    // Appearance
    var look by mutableStateOf(Look.MATERIAL); private set
    var amoled by mutableStateOf(false); private set
    var myAvatar by mutableStateOf<ImageBitmap?>(null); private set
    /** Per-look colour overrides (each look keeps its own). */
    var colorOverrides by mutableStateOf<Map<Look, Map<ColorRole, Color>>>(emptyMap()); private set
    var bgShapes by mutableStateOf(BgShapes()); private set
    val currentOverrides get() = colorOverrides[look].orEmpty()
    private val avatarFile get() = File(getApplication<Application>().filesDir, "avatar.png")

    val selectedServer get() = servers.firstOrNull { it.id == selectedServerId }
    val selectedChannel: Channel?
        get() = selectedServer?.channels?.firstOrNull { it.id == selectedChannelId }
            ?: dms.firstOrNull { it.id == selectedChannelId }?.let { Channel(it.id, 0, dmName(it), ChannelKind.TEXT, "Direct message", "", false) }

    fun dmName(d: DirectMessage) = d.userIds.mapNotNull { users[it]?.displayName }.joinToString(", ").ifEmpty { "Direct message" }

    fun plugin(id: String) = plugins.firstOrNull { it.id == id }?.enabled == true

    private var lastTypingSent = 0L
    private var profileJob: Job? = null

    init {
        viewModelScope.launch(safe) {
            val tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
            Core.init(app.filesDir.absolutePath, tz)
            val settings = Core.settings()
            look = Look.of(settings.optString("theme", "material"))
            amoled = settings.optBoolean("amoled", false)
            klipyKey = settings.optString("klipy_key", "")
            clock12h = settings.optBoolean("clock_12h", !android.text.format.DateFormat.is24HourFormat(app))
            textScale = settings.optDouble("text_scale", 1.0).toFloat()
            compact = settings.optBoolean("compact", false)
            swipeReply = settings.optBoolean("swipe_reply", true)
            autoplayGifs = settings.optBoolean("autoplay_gifs", true)
            typingStyle = settings.optString("typing_style", "avatars")
            channelIcons = settings.optBoolean("channel_icons", true)
            channelTopics = settings.optBoolean("channel_topics", true)
            EmojiPacks.load(getApplication())
            npOn = settings.optBoolean("np_on", false)
            settings.optJSONArray("np_apps")?.let { a -> for (i in 0 until a.length()) npApps.add(a.getString(i)) }
            Ui.let { u ->
                u.livingIcons = settings.optString("ui_living", "always")
                u.statusBubble = settings.optString("ui_bubble", "colour")
                u.emojiPack = settings.optString("ui_emoji", "system")
                u.fontId = settings.optString("ui_font", "system")
                u.fontScope = settings.optString("ui_font_scope", "all")
                u.fontBold = settings.optInt("ui_font_bold", 0)
                u.letterSpacing = settings.optDouble("ui_letter", 0.0).toFloat()
                u.fontFamily = withContext(Dispatchers.IO) { Fonts.family(getApplication(), u.fontId, u.fontBold) }
                u.animations = settings.optBoolean("ui_animations", true)
                u.speed = settings.optDouble("ui_speed", 1.0).toFloat()
                u.elementBlur = settings.optBoolean("ui_element_blur", true)
                u.glass = settings.optBoolean("ui_glass", true)
                u.blurStrength = settings.optDouble("ui_blur", 24.0).toFloat()
                u.rounding = settings.optDouble("ui_rounding", 1.0).toFloat()
                u.messageEnter = settings.optString("ui_msg_enter", "rise")
                u.hideStatusBar = settings.optBoolean("ui_hide_status", false)
                u.haptics = settings.optBoolean("ui_haptics", true)
            }
            settings.optJSONObject("nicknames")?.let { o -> o.keys().forEach { k -> k.toLongOrNull()?.let { nicknames[it] = o.getString(k) } } }
            settings.optJSONObject("user_notif")?.let { o -> o.keys().forEach { k -> k.toLongOrNull()?.let { userNotif[it] = o.getString(k) } } }
            settings.optJSONObject("mute_until")?.let { o -> o.keys().forEach { k -> k.toLongOrNull()?.let { muteUntil[it] = o.getLong(k) } } }
            settings.optJSONObject("emoji_counts")?.let { o -> o.keys().forEach { k -> emojiCounts[k] = o.optInt(k) }; recentEmoji = emojiCounts.entries.sortedByDescending { it.value }.take(32).map { it.key } }
            settings.optJSONObject("drafts")?.let { o -> o.keys().forEach { k -> k.toLongOrNull()?.let { drafts[it] = o.getString(k) } } }
            settings.optJSONArray("pinned_dms")?.let { a -> for (i in 0 until a.length()) pinnedDms.add(a.getLong(i)) }
            notifOn = settings.optBoolean("notif_on", true)
            notifDms = settings.optBoolean("notif_dms", true)
            notifMentions = settings.optBoolean("notif_mentions", true)
            notifReplies = settings.optBoolean("notif_replies", true)
            notifEveryone = settings.optBoolean("notif_everyone", true)
            notifServer = settings.optBoolean("notif_server", false)
            notifStyle = settings.optString("notif_style", "full")
            notifPlace = settings.optBoolean("notif_place", true)
            notifBackground = settings.optBoolean("notif_background", false)
            if (notifOn && notifBackground) KeepAliveService.set(getApplication(), true)
            chatBg = settings.optString("chat_bg", "gradient")
            chatBgDim = settings.optDouble("chat_bg_dim", 0.55).toFloat()
            chatBgBlur = settings.optDouble("chat_bg_blur", 0.0).toFloat()
            if (chatBg == "image") chatBgImage = withContext(Dispatchers.IO) { bgFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
            Clock.use12h = clock12h
            colorOverrides = Look.entries.associateWith { l ->
                val o = settings.optJSONObject("colors_${l.key}") ?: JSONObject()
                ColorRole.entries.mapNotNull { r -> if (o.has(r.name)) r to Color(o.getLong(r.name).toInt()) else null }.toMap()
            }
            settings.optJSONObject("bg_shapes")?.let { o ->
                bgShapes = BgShapes(
                    enabled = o.optBoolean("enabled", true),
                    count = o.optInt("count", 6),
                    rotateSpeed = o.optDouble("rotate", 10.0).toFloat(),
                    morphSpeed = o.optDouble("morph", 12.0).toFloat(),
                    blur = o.optDouble("blur", 24.0).toFloat(),
                    opacity = o.optDouble("opacity", 0.35).toFloat(),
                )
            }
            myAvatar = withContext(Dispatchers.IO) {
                avatarFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
            }
            plugins = Core.plugins()
            val state = Core.authState()
            authMode = state.mode
            if (state.mode != AuthMode.SIGNED_OUT) {
                // first run after sign-in has no snapshot yet: fetch the profile first
                if (state.me == null) runCatching { Core.refresh(0) }
                loadAll()
                startRefresh()
            }
            ready = true
        }
    }

    // ---------- Account ----------
    var authMode by mutableStateOf(AuthMode.SIGNED_OUT); private set
    var signingIn by mutableStateOf(false); private set
    var loginError by mutableStateOf<String?>(null)
    /** Set when Root wants a Turnstile check; the UI shows the page and calls [submitTurnstile]. */
    var challengeUrl by mutableStateOf<String?>(null); private set
    private var pendingLogin: Pair<String, String>? = null
    private var pollJob: Job? = null

    private var refreshJob: Job? = null
    var refreshing by mutableStateOf(false); private set

    /** Refreshes everything from Root in small steps while the cached UI is already usable. */
    fun startRefresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(safe) {
            refreshing = true
            var step: Int? = 0
            while (step != null) {
                step = runCatching { Core.refresh(step) }.getOrElse { e ->
                    if (e.message?.contains("expired") == true) { error = e.message; signOut() }
                    null
                }
                // apply results as they arrive
                runCatching {
                    servers = Core.servers().let { fresh -> fresh.map { s -> s.copy(channels = s.channels.map { c -> if (c.id == selectedChannelId) c.copy(unread = false) else c }) } }
                    if (step == null || step <= 2) { me = Core.me(); Core.users().let { putUsers(it) } }
                }
            }
            runCatching {
                friends = Core.friends()
                dms = Core.dms()
                Core.users().let { putUsers(it) }
                if (selectedServerId == null && selectedChannelId == null) servers.firstOrNull()?.let { selectServer(it.id) }
                Core.saveCache()
            }
            refreshing = false
            // keep the snapshot fresh while the app is open
            while (isActive) { delay(60_000); runCatching { Core.saveCache() } }
        }
    }

    fun saveCacheNow() = viewModelScope.launch(safe) { runCatching { Core.saveCache() } }

    private suspend fun loadAll() {
        me = Core.me()
        voice.myId = me?.id ?: 0
        users.clear()
        Core.users().let { putUsers(it) }
        servers = Core.servers()
        friends = Core.friends()
        dms = runCatching { Core.dms() }.getOrDefault(emptyList())
        plugins = Core.plugins()
        selectedServerId = null
        selectedChannelId = null
        messages.clear()
        // reopen where you left off (server channel or DM), else the first server
        val lastCh = runCatching { Core.settings().optLong("last_channel", 0L) }.getOrDefault(0L)
        val lastServer = servers.firstOrNull { s -> s.channels.any { it.id == lastCh } }
        when {
            lastServer != null -> { selectedServerId = lastServer.id; selectChannel(lastCh) }
            lastCh != 0L && dms.any { it.id == lastCh } -> { selectedServerId = null; selectChannel(lastCh) }
            else -> servers.firstOrNull()?.let { selectServer(it.id) }
        }
        pollJob?.cancel()
        pollJob = viewModelScope.launch(safe) { pollLoop() }
        me?.id?.let { id -> viewModelScope.launch(safe) { refreshUser(id) } }
    }

    fun signIn(username: String, password: String) {
        if (username.isBlank() || password.isEmpty()) { loginError = "Enter your username and password"; return }
        pendingLogin = username to password
        attemptSignIn(null)
    }

    fun submitTurnstile(token: String) {
        challengeUrl = null
        attemptSignIn(token)
    }

    fun cancelTurnstile(reason: String? = null) {
        challengeUrl = null
        signingIn = false
        loginError = reason ?: "Verification cancelled"
    }

    private fun attemptSignIn(turnstile: String?) {
        val (u, p) = pendingLogin ?: return
        signingIn = true
        loginError = null
        viewModelScope.launch(safe) {
            runCatching {
                Core.signIn(u, p, turnstile, android.os.Build.MODEL ?: "Android", android.os.Build.VERSION.RELEASE ?: "")
            }.onSuccess { r ->
                when (r) {
                    is SignInResult.Challenge -> challengeUrl = r.url // keep spinner while the user verifies
                    is SignInResult.Done -> {
                        pendingLogin = null
                        authMode = r.state.mode
                        loadAll()
                        signingIn = false
                    }
                }
            }.onFailure {
                signingIn = false
                loginError = it.message ?: "Sign in failed"
            }
        }
    }

    fun tryDemo() = viewModelScope.launch(safe) {
        authMode = Core.demo().mode
        loadAll()
    }

    fun signOut() = viewModelScope.launch(safe) {
        voice.leave()
        pollJob?.cancel()
        authMode = Core.signOut().mode
        screen = Screen.Chat
        me = null
        servers = emptyList()
        friends = emptyList()
        messages.clear()
        users.clear()
        selectedServerId = null
        selectedChannelId = null
        // nothing from this account may leak into the next sign-in
        dms = emptyList()
        mentions.clear()
        notifications = emptyList()
        typing.clear()
        replying.clear()
        editing = null
        pending.clear()
        incomingCall = null
        profileUserId = null
        roles.clear(); serverRoles.clear(); memberRoles.clear()
        realNames.clear()
    }

    init {
        viewModelScope.launch(safe) {
            snapshotFlow { voice.error }.collect { e -> if (e != null) { error = "Call: $e"; runCatching { Core.log("voice", e) } } }
        }
    }

    private suspend fun pollLoop() {
        while (viewModelScope.isActive) {
            delay(400)
            val now = System.currentTimeMillis()
            runCatching { Core.takeError() }.getOrNull()?.let { error = it }
            for (ev in runCatching { Core.poll() }.getOrDefault(emptyList())) when (ev) {
                is Event.NewMessage -> {
                    if (ev.message.authorId !in users) runCatching { Core.users() }.getOrNull()?.let { putUsers(it) }
                    val replyToMe = repliesToMe(ev.message)
                    if (ev.message.authorId != me?.id && (replyToMe || mentionsMe(ev.message.content))) {
                        mentions.add(0, ev.message)
                        if (mentions.size > 100) mentions.removeAt(mentions.lastIndex)
                    }
                    if (ev.message.channelId != selectedChannelId) {
                        servers = servers.map { s -> s.copy(channels = s.channels.map { if (it.id == ev.message.channelId) it.copy(unread = true) else it }) }
                    }
                    clearTyping(ev.message.channelId, ev.message.authorId)
                    // keep DM previews/order current
                    if (dms.any { it.id == ev.message.channelId }) runCatching { dms = Core.dms() }
                    // keep time order and never add a message twice
                    if (ev.message.channelId == selectedChannelId && messages.none { it.id == ev.message.id }) {
                        val at = messages.indexOfFirst { it.ts > ev.message.ts }.let { if (it < 0) messages.size else it }
                        messages.add(at, ev.message)
                    }
                    maybeNotify(ev.message, replyToMe)
                }
                is Event.Update -> { applySentShapes(ev.message); replace(ev.message) }
                is Event.Remove -> messages.removeAll { it.id == ev.id }
                is Event.Typing -> {
                    if (ev.userId !in users) runCatching { Core.users() }.getOrNull()?.let { putUsers(it) }
                    typing[ev.channelId] = (typing[ev.channelId] ?: emptyMap()) + (ev.userId to now + 8_000)
                }
                is Event.StatusChange -> users[ev.userId]?.let { users[ev.userId] = it.copy(status = ev.status) }
                is Event.Voice -> {
                    if (ev.member.optLong("user_id") !in users) runCatching { Core.users() }.getOrNull()?.let { putUsers(it) }
                    voice.onMember(ev.channelId, ev.member)
                }
                is Event.VoiceLeft -> voice.onLeft(ev.channelId, ev.userId)
                is Event.Ring -> if (voice.channelId != ev.channelId) {
                    if (ev.callerId !in users) runCatching { Core.users() }.getOrNull()?.let { putUsers(it) }
                    incomingCall = IncomingCall(ev.channelId, ev.callerId, System.currentTimeMillis())
                    if (!visible) app.bloom.notify.Notifier.ringing(getApplication(), ev.channelId, users[ev.callerId]?.displayName ?: "Someone", users[ev.callerId]?.avatarUrl)
                }
                is Event.UserChanged -> {
                    putUsers(listOf(ev.user.let { u -> users[u.id]?.let { old -> u.copy(status = old.status) } ?: u }))
                    if (ev.user.id == me?.id) me = users[ev.user.id]
                }
                is Event.RingDeclined -> if (voice.channelId == ev.channelId) error = "${users[ev.userId]?.displayName ?: "They"} declined the call"
                Event.Unknown -> {}
                Event.DmsChanged -> {
                    dms = runCatching { Core.dms() }.getOrDefault(dms)
                    // keep the open conversation marked read
                    selectedChannelId?.let { open -> dms = dms.map { if (it.id == open) it.copy(unread = false) else it } }
                }
            }
            // expire stale typing indicators
            typing.keys.toList().forEach { c ->
                val alive = typing[c].orEmpty().filterValues { it > now }
                if (alive.isEmpty()) typing.remove(c) else if (alive.size != typing[c]?.size) typing[c] = alive
            }
        }
    }

    private fun clearTyping(channel: Long, user: Long) {
        typing[channel]?.let { typing[channel] = it - user }
    }

    private fun replace(m: Message) {
        val i = messages.indexOfFirst { it.id == m.id }
        if (i >= 0) messages[i] = m
    }

    fun selectServer(id: Long?) {
        screen = Screen.Chat
        selectedServerId = id
        val s = servers.firstOrNull { it.id == id } ?: return
        (s.channels.firstOrNull { it.kind == ChannelKind.TEXT } ?: s.channels.firstOrNull { it.kind == ChannelKind.ANNOUNCEMENT })
            ?.let { selectChannel(it.id) }
    }

    /** Bumped when the user explicitly opens a channel; the UI slides the chat in. */
    var channelOpens by mutableStateOf(0); private set

    var noMoreHistory by mutableStateOf(false); private set
    /** the keyboard's height (px above the nav bar), learned once it's been open; panels match it */
    var keyboardHeightPx by mutableIntStateOf(0)
    /** bumped when the whole message list is replaced (no move animations for that swap) */
    var listEpoch by mutableIntStateOf(0); private set
    var loadingOlder by mutableStateOf(false); private set

    /** Loads older history when you scroll to the top. */
    fun loadOlder() {
        val ch = selectedChannelId ?: return
        if (loadingOlder || noMoreHistory || messages.isEmpty()) return
        loadingOlder = true
        viewModelScope.launch(safe) {
            val result = runCatching { Core.olderMessages(ch) }
            runCatching { Core.users() }.getOrNull()?.let { putUsers(it) }
            if (selectedChannelId == ch) result.onSuccess { older ->
                val have = messages.map { it.id }.toHashSet()
                val fresh = older.filter { it.id !in have }
                // only a successful, empty answer means the real start of the chat
                if (older.isEmpty()) noMoreHistory = true else if (fresh.isNotEmpty()) messages.addAll(0, fresh)
            }.onFailure { error = "Couldn't load older messages: ${it.message}" }
            // brief pause after a failure so a dead connection isn't hammered
            if (result.isFailure) delay(2000)
            loadingOlder = false
        }
    }

    fun openDm(id: Long) {
        selectedServerId = null
        dms = dms.map { if (it.id == id) it.copy(unread = false) else it }
        openChannel(id)
    }

    // ---- calls ----
    data class IncomingCall(val channelId: Long, val callerId: Long, val at: Long)
    var incomingCall by mutableStateOf<IncomingCall?>(null)
    /** the call is shown full screen (otherwise as the floating pill) */
    var callExpanded by mutableStateOf(false)

    /** Start (or join) a call and show it full screen. */
    fun startCall(channelId: Long, title: String) { callExpanded = true; voice.join(channelId, title) }

    fun acceptCall() {
        val c = incomingCall ?: return
        incomingCall = null
        app.bloom.notify.Notifier.clear(getApplication(), c.channelId + 7_000_000)
        openDm(c.channelId)
        startCall(c.channelId, users[c.callerId]?.displayName ?: "Call")
    }

    fun declineCall() {
        val c = incomingCall ?: return
        incomingCall = null
        app.bloom.notify.Notifier.clear(getApplication(), c.channelId + 7_000_000)
        viewModelScope.launch(safe) { runCatching { Core.social("ring_decline", JSONObject().put("channel", c.channelId)) } }
    }

    /** Opens the You page and closes the side panel so the page (and the flying avatar) is in view. */
    fun openYou() { screen = Screen.You; channelOpens++ }

    /** Opens a channel of a given community (quick switcher, search). */
    fun openChannelIn(serverId: Long, id: Long) {
        selectedServerId = serverId
        openChannel(id)
    }

    fun openChannel(id: Long) {
        selectChannel(id)
        channelOpens++
    }

    fun selectChannel(id: Long) {
        screen = Screen.Chat
        selectedChannelId = id
        viewModelScope.launch(safe) { runCatching { Core.setSetting("last_channel", id) } }
        replyingTo = null
        editing = null
        pins = null
        listEpoch++          // clearing is a swap too: nothing may fade out over the next chat
        messages.clear()
        loadingMessages = true
        noMoreHistory = false
        viewModelScope.launch(safe) {
            // instant: whatever we have cached
            runCatching { Core.cachedMessages(id) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { cached ->
                if (selectedChannelId == id) { listEpoch++; messages.clear(); messages.addAll(cached) }
            }
            val list = runCatching { Core.messages(id) }.getOrElse { error = it.message; emptyList() }
            launch { runCatching { Core.markViewed(id) } }
            // Real backends fetch author profiles alongside messages.
            runCatching { Core.users() }.getOrNull()?.let { putUsers(it) }
            if (selectedChannelId == id && (list.isNotEmpty() || messages.isEmpty())) {
                // keep older history that's already on screen (from the saved copy) instead of
                // dropping it — dropped items made the list jump and ghost while they faded
                val oldestFresh = list.minOfOrNull { it.ts }
                val keep = if (oldestFresh == null) emptyList() else messages.filter { it.ts < oldestFresh && list.none { f -> f.id == it.id } }
                val merged = (keep + list).sortedBy { it.ts }.distinctBy { it.id }
                if (merged.map { it.id } != messages.map { it.id }) {
                    listEpoch++
                    messages.clear()
                    messages.addAll(merged)
                } else {
                    // same messages: update in place (edits, reactions) without a swap
                    merged.forEachIndexed { i, m -> if (messages[i] != m) messages[i] = m }
                }
            }
            // a short first page (deleted messages count against it) doesn't prove we're at
            // the start: ask for one more page; the core only answers empty at the real start
            if (selectedChannelId == id && list.size < 50) loadOlder()
            if (selectedChannelId == id) loadingMessages = false
            // opening a channel reads it
            servers = servers.map { s -> s.copy(channels = s.channels.map { if (it.id == id) it.copy(unread = false) else it }) }
        }
    }

    fun addAttachments(uris: List<Uri>) {
        val cr = getApplication<Application>().contentResolver
        uris.forEach { uri ->
            var name = "file"
            runCatching {
                cr.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) name = c.getString(0) ?: name }
            }
            val mime = cr.getType(uri) ?: "application/octet-stream"
            // real dimensions so the upload skeleton has the photo's own shape
            var w = 0; var h = 0
            runCatching {
                if (mime.startsWith("image/")) {
                    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    cr.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
                    w = o.outWidth; h = o.outHeight
                } else if (mime.startsWith("video/")) {
                    android.media.MediaMetadataRetriever().use { r ->
                        r.setDataSource(getApplication(), uri)
                        w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                        h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                        val rot = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (rot == 90 || rot == 270) { val x = w; w = h; h = x }
                    }
                }
            }
            if (pending.none { it.uri == uri }) pending.add(PendingAttachment(uri, name, mime, w, h))
        }
    }

    fun removeAttachment(p: PendingAttachment) { pending.remove(p) }

    var savingProfile by mutableStateOf(false); private set

    /** Saves only what changed; images are copied to cache for the core to upload. */
    fun saveProfile(username: String?, bio: String?, status: String?, presence: String?, avatar: Uri?, banner: Uri?, done: () -> Unit) {
        viewModelScope.launch(safe) {
            savingProfile = true
            val tmp = mutableListOf<File>()
            runCatching {
                val o = JSONObject()
                username?.let { o.put("username", it) }
                bio?.let { o.put("description", it) }
                status?.let { o.put("status", it) }
                presence?.let { o.put("presence", it) }
                avatar?.let { val f = toCacheFile(PendingAttachment(it, "avatar.jpg", "image/jpeg")); tmp += f; o.put("avatar_path", f.absolutePath) }
                banner?.let { val f = toCacheFile(PendingAttachment(it, "banner.jpg", "image/jpeg")); tmp += f; o.put("banner_path", f.absolutePath) }
                val u = Core.editProfile(o)
                me = u
                putUsers(listOf(u))
            }.onSuccess { done() }.onFailure { error = it.message }
            tmp.forEach { it.delete() }
            savingProfile = false
        }
    }

    /** Copies a content:// URI to a cache file so the Rust core can read it. */
    private suspend fun toCacheFile(p: PendingAttachment): File = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, "upload").apply { mkdirs() }
        val f = File(dir, "${System.nanoTime()}-${p.name.replace('/', '_')}")
        getApplication<Application>().contentResolver.openInputStream(p.uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        f
    }

    fun sendGif(url: String) {
        val channel = selectedChannelId ?: return
        viewModelScope.launch(safe) { runCatching { messages.add(Core.send(channel, url, replyingTo?.id)) }.onFailure { error = it.message } }
        replyingTo = null
    }

    fun loadGifs(query: String) = viewModelScope.launch(safe) {
        gifError = null
        if (gifFavorites.isEmpty()) gifFavorites = runCatching { Core.gifFavorites() }.getOrDefault(emptyList())
        gifResults = runCatching { Core.gifSearch(query) }.getOrElse { gifError = it.message; emptyList() }
    }

    fun toggleGifFavorite(url: String) = viewModelScope.launch(safe) {
        val add = url !in gifFavorites
        gifFavorites = if (add) listOf(url) + gifFavorites else gifFavorites - url
        runCatching { Core.gifFavorite(url, add) }
    }

    fun loadEmoji() = viewModelScope.launch(safe) {
        if (emojiCatalog.isEmpty()) emojiCatalog = runCatching { Core.emojiCatalog() }.getOrDefault(emptyList())
        if (customEmoji.isEmpty()) {
            customEmoji = runCatching { Core.customEmoji() }.getOrDefault(emptyList())
            prefetchEmoji()
        }
    }

    /** Warm the image cache so custom emoji appear instantly in the panel and in chat. */
    private fun prefetchEmoji() {
        val ctx = getApplication<Application>()
        val loader = coil3.SingletonImageLoader.get(ctx)
        customEmoji.forEach { e -> loader.enqueue(coil3.request.ImageRequest.Builder(ctx).data(e.url).build()) }
    }

    var saving by mutableStateOf(false); private set

    /** Saves an image or video to the gallery (videos are converted from Root's stream to MP4). */
    fun saveMedia(a: Attachment) {
        val url = a.url ?: return
        val ctx = getApplication<Application>()
        saving = true
        viewModelScope.launch(safe) {
            runCatching {
                val isVideo = a.kind == AttachmentKind.VIDEO
                val stamp = System.currentTimeMillis()
                val file = File(File(ctx.cacheDir, "captures").apply { mkdirs() }, if (isVideo) "bloom-$stamp.mp4" else "bloom-$stamp.img")
                if (isVideo && url.contains(".m3u8")) {
                    app.bloom.voice.MediaExport.hlsToMp4(ctx, url, file)
                } else withContext(Dispatchers.IO) {
                    java.net.URL(url).openStream().use { i -> file.outputStream().use { i.copyTo(it) } }
                }
                withContext(Dispatchers.IO) {
                    val mime = if (isVideo) "video/mp4" else (ctx.contentResolver.getType(Uri.fromFile(file)) ?: "image/jpeg")
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, if (isVideo) "bloom-$stamp.mp4" else "bloom-$stamp.jpg")
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) "Movies/Bloom" else "Pictures/Bloom")
                    }
                    val col = if (isVideo) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    val out = ctx.contentResolver.insert(col, values) ?: error("Couldn't create the file in your gallery")
                    ctx.contentResolver.openOutputStream(out)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
                    file.delete()
                }
                error = if (isVideo) "Saved to Movies/Bloom" else "Saved to Pictures/Bloom"
            }.onFailure { error = "Couldn't save: ${it.message}" }
            saving = false
        }
    }

    /** Uploads a recorded voice message and sends it. */
    fun sendVoice(file: File, durationMs: Long, samples: List<Float> = emptyList()) {
        val channel = selectedChannelId ?: return
        if (durationMs < 600) { file.delete(); return }
        viewModelScope.launch(safe) {
            uploading = true
            runCatching {
                // Root's own name format makes it a voice message (duration + waveform) everywhere
                val name = app.bloom.ui.screens.VoiceNames.encode(durationMs, samples)
                val token = Core.upload(file.absolutePath, name, "audio/mp4")
                messages.add(Core.send(channel, "", replyingTo?.id, listOf(token)))
            }.onFailure { error = it.message }
            replyingTo = null
            uploading = false
            file.delete()
        }
    }

    fun send(text: String) {
        val channel = selectedChannelId ?: return
        if (pending.isNotEmpty() && editing == null) {
            val toSend = pending.toList()
            pending.clear()
            val reply = replyingTo?.id
            val replyIds = replying.map { it.id }
            replying.clear()
            val job = Outgoing(System.nanoTime(), toSend, text)
            outgoing.add(job)
            viewModelScope.launch(safe) {
                runCatching {
                    // each file's share of the bar is its share of the total size
                    val files = toSend.map { it to toCacheFile(it) }
                    val total = files.sumOf { it.second.length() }.coerceAtLeast(1)
                    var done = 0L
                    val tokens = files.map { (p, f) ->
                        try {
                            val t = uploadWithProgress(f, p.name, p.mime) { frac -> job.progress = (done + f.length() * frac) / total }
                            done += f.length()
                            t
                        } finally { f.delete() }
                    }
                    job.progress = 1f
                    val m = Core.send(channel, expandCustomEmoji(text), reply, tokens, replyIds)
                    rememberShapes(m, toSend)
                    if (selectedChannelId == channel && messages.none { it.id == m.id }) messages.add(m)
                }.onFailure { error = it.message; pending.addAll(0, toSend) }
                outgoing.remove(job)
            }
            return
        }
        sendText(channel, expandCustomEmoji(text))
    }

    /** The files we just sent have known shapes: use them so media never flashes a placeholder ratio. */
    private val sentShapes = mutableListOf<Float>()
    private fun rememberShapes(m: Message, sent: List<PendingAttachment>) {
        val shapes = sent.filter { it.isImage || it.isVideo }.map { if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height else 0f }
        val media = m.attachments.filter { it.kind == app.bloom.core.AttachmentKind.IMAGE || it.kind == app.bloom.core.AttachmentKind.VIDEO }
        if (media.isEmpty()) { synchronized(sentShapes) { sentShapes.clear(); sentShapes.addAll(shapes) }; return }
        media.forEachIndexed { i, a -> val r = shapes.getOrNull(i) ?: 0f; if (r > 0f) a.url?.let { mediaRatios[it.substringBefore('?')] = r } }
    }

    /** When the server copy of our message arrives later (with real URLs), apply the shapes we kept. */
    private fun applySentShapes(m: Message) {
        if (m.authorId != me?.id) return
        val shapes = synchronized(sentShapes) { sentShapes.toList().also { sentShapes.clear() } }.ifEmpty { return }
        m.attachments.filter { it.kind == app.bloom.core.AttachmentKind.IMAGE || it.kind == app.bloom.core.AttachmentKind.VIDEO }
            .forEachIndexed { i, a -> val r = shapes.getOrNull(i) ?: 0f; if (r > 0f) a.url?.let { mediaRatios[it.substringBefore('?')] = r } }
    }

    /** Custom emoji live in the text box as one private-use character each. */
    fun emojiChar(index: Int): String = (PUA_BASE + index).toChar().toString()
    fun customForChar(c: Char): CustomEmoji? = (c.code - PUA_BASE).takeIf { it in customEmoji.indices }?.let { customEmoji[it] }

    /**
     * Typed `:shortcode:` and picked custom emoji → Root's `[:name:](root://emoji/ID)` links.
     * Typed shortcodes are converted to placeholders FIRST, then every placeholder is
     * expanded exactly once (doing it the other way round wrapped links twice).
     */
    fun expandCustomEmoji(text: String): String {
        if (customEmoji.isEmpty()) return text
        var t = text
        if (':' in t) customEmoji.forEachIndexed { i, e -> t = t.replace(":${e.shortcode}:", emojiChar(i)) }
        val sb = StringBuilder()
        for (c in t) {
            val e = customForChar(c)
            if (e != null) sb.append("[:${e.shortcode}:](root://emoji/${e.id})") else sb.append(c)
        }
        return sb.toString()
    }

    /** Reaction code for a picked emoji: custom placeholder → its id, unicode as-is. */
    fun reactionCode(picked: String): String = picked.singleOrNull()?.let { customForChar(it)?.id } ?: picked

    private fun sendText(channel: Long, text: String) {
        val editTarget = editing
        val reply = replyingTo?.id
        val replyIds = replying.map { it.id }
        replying.clear()
        editing = null
        viewModelScope.launch(safe) {
            runCatching {
                if (editTarget != null) {
                    (Core.edit(editTarget.id, text) as? Event.Update)?.let { replace(it.message) }
                } else {
                    messages.add(Core.send(channel, text, reply, emptyList(), replyIds))
                }
            }.onFailure { error = it.message }
        }
    }

    fun onTyping() {
        val now = System.currentTimeMillis()
        if (now - lastTypingSent < 8_000) return
        lastTypingSent = now
        val ch = selectedChannelId ?: return
        viewModelScope.launch(safe) { runCatching { Core.typing(ch) } }
    }

    fun delete(m: Message) = viewModelScope.launch(safe) {
        when (val ev = Core.delete(m.id)) {
            is Event.Remove -> messages.removeAll { it.id == ev.id }
            is Event.Update -> replace(ev.message)
            else -> {}
        }
    }

    fun react(m: Message, emoji: String) = viewModelScope.launch(safe) {
        if (emoji.isNotEmpty() && !emoji[0].isLetterOrDigit()) noteEmoji(emoji)
        runCatching { Core.react(m.id, emoji) }.onSuccess { replace(it) }.onFailure { error = "Couldn't react: ${it.message}" }
    }

    fun readAll() = viewModelScope.launch(safe) {
        Core.markRead(null)
        servers = Core.servers()
    }

    fun openProfile(userId: Long) {
        profileUserId = userId
        profileMutuals = null
        profileJob?.cancel()
        // Never leave the spinner hanging: on failure show "none in common" and surface the error.
        profileJob = viewModelScope.launch(safe) {
            // full profile first (bio, banner, cosmetics now come from Root's User/Get), quietly
            launch { refreshUser(userId) }
            profileMutuals = runCatching { Core.mutuals(userId) }.getOrElse { error = it.message; Mutuals(emptyList(), emptyList()) }
        }
    }

    /** Fetches someone's full profile (User/Get) and merges it in. Failures are logged, not shown. */
    suspend fun refreshUser(userId: Long) {
        runCatching { Core.social("user_get", JSONObject().put("user", userId)) as JSONObject }
            .onSuccess { o -> putUsers(listOf(User.from(o).let { fresh -> users[userId]?.let { old -> fresh.copy(status = old.status) } ?: fresh })); if (userId == me?.id) me = users[userId] }
            .onFailure { runCatching { Core.log("profile", "user_get: ${it.message}") } }
    }

    // ---- pinned messages ----
    var pins by mutableStateOf<List<Message>?>(null); private set
    var pinsLoading by mutableStateOf(false); private set
    fun loadPins() {
        val ch = selectedChannelId ?: return
        pinsLoading = true
        viewModelScope.launch(safe) {
            val r = runCatching { Core.social("pins", JSONObject().put("channel", ch)) as JSONArray }
            runCatching { Core.users() }.getOrNull()?.let { putUsers(it) }
            if (selectedChannelId == ch) pins = r.map { a -> (0 until a.length()).map { Message.from(a.getJSONObject(it)) } }
                .getOrElse { error = "Couldn't load pinned messages: ${it.message}"; emptyList() }
            pinsLoading = false
        }
    }
    fun closePins() { pins = null }
    fun setPinned(m: Message, pin: Boolean) = viewModelScope.launch(safe) {
        runCatching { Core.social(if (pin) "pin" else "unpin", JSONObject().put("id", m.id).put("channel", m.channelId)) }
            .onSuccess {
                error = if (pin) "Pinned" else "Unpinned"
                pins = pins?.let { list -> if (pin) (listOf(m) + list).distinctBy { it.id } else list.filter { it.id != m.id } }
            }
            .onFailure { error = "Root said no: ${it.message}" }
    }

    fun closeProfile() { profileUserId = null }

    fun togglePlugin(id: String, on: Boolean) = viewModelScope.launch(safe) { plugins = Core.setPlugin(id, on) }

    fun setPluginConfig(id: String, cfg: JSONObject) = viewModelScope.launch(safe) { plugins = Core.setPluginConfig(id, cfg) }

    fun chooseLook(l: Look) {
        look = l
        viewModelScope.launch(safe) { Core.setSetting("theme", l.key) }
    }

    fun setColor(role: ColorRole, c: Color?) {
        val cur = currentOverrides.toMutableMap()
        if (c == null) cur.remove(role) else cur[role] = c
        colorOverrides = colorOverrides + (look to cur)
        saveColors()
    }

    fun resetColors() {
        colorOverrides = colorOverrides + (look to emptyMap())
        saveColors()
    }

    private var saveColorsJob: Job? = null
    private fun saveColors() {
        val l = look
        val o = JSONObject().apply { colorOverrides[l].orEmpty().forEach { (r, c) -> put(r.name, c.toArgb().toLong() and 0xFFFFFFFFL) } }
        saveColorsJob?.cancel()
        saveColorsJob = viewModelScope.launch(safe) { delay(250); Core.setSetting("colors_${l.key}", o) }
    }

    private var saveBgJob: Job? = null
    fun updateBgShapes(b: BgShapes) {
        bgShapes = b
        // sliders fire continuously; debounce the disk write
        saveBgJob?.cancel()
        saveBgJob = viewModelScope.launch(safe) {
            delay(300)
            Core.setSetting("bg_shapes", JSONObject().put("enabled", b.enabled).put("count", b.count).put("rotate", b.rotateSpeed.toDouble())
                .put("morph", b.morphSpeed.toDouble()).put("blur", b.blur.toDouble()).put("opacity", b.opacity.toDouble()))
        }
    }

    var klipyKey by mutableStateOf(""); private set

    // Chat preferences
    var clock12h by mutableStateOf(false); private set
    var textScale by mutableStateOf(1f); private set
    var compact by mutableStateOf(false); private set
    var swipeReply by mutableStateOf(true); private set
    var autoplayGifs by mutableStateOf(true); private set
    var channelIcons by mutableStateOf(true); private set
    var channelTopics by mutableStateOf(true); private set
    // notifications
    var notifOn by mutableStateOf(true); private set
    var notifDms by mutableStateOf(true); private set
    var notifMentions by mutableStateOf(true); private set
    var notifReplies by mutableStateOf(true); private set
    var notifEveryone by mutableStateOf(true); private set
    var notifServer by mutableStateOf(false); private set
    /** "full" | "message" | "who" | "private" */
    var notifStyle by mutableStateOf("full"); private set
    var notifPlace by mutableStateOf(true); private set
    var notifBackground by mutableStateOf(false); private set
    /** "avatars" (pfps in morphing shapes) | "shapes" | "dots" | "text" */
    var typingStyle by mutableStateOf("avatars"); private set
    /** "none" | "gradient" | "image" */
    var chatBg by mutableStateOf("gradient"); private set
    var chatBgDim by mutableStateOf(0.55f); private set
    var chatBgBlur by mutableStateOf(0f); private set
    var chatBgImage by mutableStateOf<ImageBitmap?>(null); private set
    private val bgFile get() = File(getApplication<Application>().filesDir, "chat_bg.jpg")
    fun setChatBgImage(uri: Uri?) = viewModelScope.launch(safe) {
        if (uri == null) { withContext(Dispatchers.IO) { bgFile.delete() }; chatBgImage = null; setPref("chat_bg", "gradient"); return@launch }
        runCatching {
            chatBgImage = withContext(Dispatchers.IO) {
                val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)) { d, info, _ ->
                    val sc = 1600f / maxOf(info.size.width, info.size.height); if (sc < 1f) d.setTargetSize((info.size.width * sc).toInt(), (info.size.height * sc).toInt())
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                bgFile.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                src.asImageBitmap()
            }
            setPref("chat_bg", "image")
        }.onFailure { error = "Couldn't use that image" }
    }
    fun setPref(key: String, value: Any) {
        when (key) {
            "clock_12h" -> clock12h = value as Boolean
            "text_scale" -> textScale = (value as Number).toFloat()
            "compact" -> compact = value as Boolean
            "swipe_reply" -> swipeReply = value as Boolean
            "autoplay_gifs" -> autoplayGifs = value as Boolean
            "typing_style" -> typingStyle = value as String
            "channel_icons" -> channelIcons = value as Boolean
            "channel_topics" -> channelTopics = value as Boolean
            "ui_animations" -> Ui.animations = value as Boolean
            "ui_speed" -> Ui.speed = (value as Number).toFloat()
            "ui_element_blur" -> Ui.elementBlur = value as Boolean
            "ui_glass" -> Ui.glass = value as Boolean
            "ui_blur" -> Ui.blurStrength = (value as Number).toFloat()
            "ui_rounding" -> Ui.rounding = (value as Number).toFloat()
            "ui_msg_enter" -> Ui.messageEnter = value as String
            "ui_hide_status" -> Ui.hideStatusBar = value as Boolean
            "ui_haptics" -> Ui.haptics = value as Boolean
            "ui_living" -> Ui.livingIcons = value as String
            "ui_bubble" -> Ui.statusBubble = value as String
            "ui_emoji" -> Ui.emojiPack = value as String
            "ui_font_scope" -> Ui.fontScope = value as String
            "ui_font_bold" -> { Ui.fontBold = (value as Number).toInt(); Ui.fontFamily = Fonts.family(getApplication(), Ui.fontId, Ui.fontBold) }
            "ui_letter" -> Ui.letterSpacing = (value as Number).toFloat()
            "np_on" -> npOn = value as Boolean
            "notif_on" -> { notifOn = value as Boolean; KeepAliveService.set(getApplication(), notifOn && notifBackground) }
            "notif_dms" -> notifDms = value as Boolean
            "notif_mentions" -> notifMentions = value as Boolean
            "notif_replies" -> notifReplies = value as Boolean
            "notif_everyone" -> notifEveryone = value as Boolean
            "notif_server" -> notifServer = value as Boolean
            "notif_style" -> notifStyle = value as String
            "notif_place" -> notifPlace = value as Boolean
            "notif_background" -> { notifBackground = value as Boolean; KeepAliveService.set(getApplication(), notifOn && notifBackground) }
            "chat_bg" -> chatBg = value as String
            "chat_bg_dim" -> chatBgDim = (value as Number).toFloat()
            "chat_bg_blur" -> chatBgBlur = (value as Number).toFloat()
        }
        Clock.use12h = clock12h
        viewModelScope.launch(safe) { Core.setSetting(key, value) }
    }
    fun saveKlipyKey(k: String) {
        klipyKey = k
        viewModelScope.launch(safe) { Core.setSetting("klipy_key", k.trim().ifEmpty { null }) }
    }

    fun chooseAmoled(on: Boolean) {
        amoled = on
        viewModelScope.launch(safe) { Core.setSetting("amoled", on) }
    }

    /** Decodes, centre-crops and downsizes the picked image to 256px, then stores it privately. */
    fun setAvatar(uri: Uri?) = viewModelScope.launch(safe) {
        if (uri == null) {
            withContext(Dispatchers.IO) { avatarFile.delete() }
            myAvatar = null
            return@launch
        }
        runCatching {
            val bmp = withContext(Dispatchers.IO) {
                val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getApplication<Application>().contentResolver, uri)) { d, info, _ ->
                    val s = info.size
                    val scale = 512f / minOf(s.width, s.height)
                    if (scale < 1f) d.setTargetSize((s.width * scale).toInt(), (s.height * scale).toInt())
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                val side = minOf(src.width, src.height)
                val cropped = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                val out = Bitmap.createScaledBitmap(cropped, 256, 256, true)
                avatarFile.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                out
            }
            myAvatar = bmp.asImageBitmap()
        }.onFailure { error = "Couldn't load that image" }
    }

    suspend fun preview(text: String) = runCatching { Core.previewSend(text) }.getOrDefault(text)
}
