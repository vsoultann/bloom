package app.bloom.core

import androidx.compose.runtime.Immutable
import org.json.JSONObject

enum class Status { ONLINE, IDLE, DND, OFFLINE }
enum class Platform { MOBILE, DESKTOP, WEB }
enum class ChannelKind { TEXT, VOICE, ANNOUNCEMENT, APP }

private fun status(s: String) = Status.valueOf(s.uppercase())

@Immutable
data class User(
    val id: Long,
    val name: String,
    val displayName: String,
    val hue: Int,
    val status: Status,
    val platforms: List<Platform>,
    val bio: String,
    val pronouns: String,
    val friendSince: Long?,
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val statusText: String = "",
    val bot: Boolean = false,
    /** status including any hidden now-playing part (for re-publishing) */
    val statusTextRaw: String = "",
    /** what they're listening to, if they use Bloom and share it */
    val nowPlaying: app.bloom.notify.NowPlaying? = null,
    /** Root cosmetics: name effect colours/gradient */
    val nameFill: Fill? = null,
    /** Root cosmetics: profile card background */
    val profileBg: Fill? = null,
    /** Root cosmetics they wear ("Name effect · Aurora", ...) */
    val worn: List<String> = emptyList(),
    /** Root cosmetic ids that need art bundles (see ui/components/CosmeticStore.kt) */
    val frameId: String? = null,
    val borderId: String? = null,
    val fontId: String? = null,
    val effectId: String? = null,
) {
    companion object {
        fun from(o: JSONObject) = User(
            id = o.getLong("id"),
            name = o.getString("name"),
            displayName = o.getString("display_name"),
            hue = o.getInt("hue"),
            status = status(o.getString("status")),
            platforms = o.getJSONArray("platforms").strings().map { Platform.valueOf(it.uppercase()) },
            bio = o.getString("bio"),
            pronouns = o.getString("pronouns"),
            friendSince = o.optLongOrNull("friend_since"),
            avatarUrl = o.optStringOrNull("avatar_url"),
            bannerUrl = o.optStringOrNull("banner_url"),
            statusText = app.bloom.notify.Hidden.strip(o.optString("status_text", "")),
            statusTextRaw = o.optString("status_text", ""),
            nowPlaying = app.bloom.notify.Hidden.decode(o.optString("status_text", "")),
            bot = o.optBoolean("bot", false),
            nameFill = o.optJSONObject("cosmetic")?.optJSONObject("name")?.let(Fill::from),
            profileBg = o.optJSONObject("cosmetic")?.optJSONObject("profile_bg")?.let(Fill::from),
            worn = o.optJSONObject("cosmetic")?.optJSONArray("worn")?.strings().orEmpty(),
            frameId = o.optJSONObject("cosmetic")?.optStringOrNull("frame"),
            borderId = o.optJSONObject("cosmetic")?.optStringOrNull("border"),
            fontId = o.optJSONObject("cosmetic")?.optStringOrNull("font"),
            effectId = o.optJSONObject("cosmetic")?.optStringOrNull("effect"),
        )
    }
}

/** A Root cosmetic fill: one colour, or a gradient at [angle] degrees. Colours are ARGB. */
@Immutable
data class Fill(val colors: List<Int>, val angle: Float) {
    val solid get() = colors.size == 1
    companion object {
        fun from(o: JSONObject): Fill? {
            val a = o.optJSONArray("colors") ?: return null
            val c = (0 until a.length()).map { a.getLong(it).toInt() }
            return if (c.isEmpty()) null else Fill(c, o.optDouble("angle", 0.0).toFloat())
        }
    }
}

@Immutable
data class Channel(
    val id: Long,
    val serverId: Long,
    val name: String,
    val kind: ChannelKind,
    val category: String,
    val topic: String,
    val unread: Boolean,
    val iconUrl: String? = null,
) {
    companion object {
        fun from(o: JSONObject) = Channel(
            o.getLong("id"), o.getLong("server_id"), o.getString("name"),
            ChannelKind.valueOf(o.getString("kind").uppercase()), o.getString("category"),
            o.getString("topic"), o.getBoolean("unread"), o.optStringOrNull("icon_url"),
        )
    }
}

@Immutable
data class Server(
    val id: Long,
    val name: String,
    val hue: Int,
    val shape: Int,
    val memberIds: List<Long>,
    val channels: List<Channel>,
    val mentions: Int,
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
) {
    val hasUnread get() = channels.any { it.unread }

    companion object {
        fun from(o: JSONObject) = Server(
            o.getLong("id"), o.getString("name"), o.getInt("hue"), o.getInt("shape"),
            o.getJSONArray("member_ids").longs(), o.getJSONArray("channels").map { Channel.from(it) },
            o.getInt("mentions"), o.optStringOrNull("icon_url"), o.optStringOrNull("banner_url"),
        )
    }
}

@Immutable
data class Reaction(val emoji: String, val userIds: List<Long>)

@Immutable
data class Message(
    val id: Long,
    val channelId: Long,
    val authorId: Long,
    val content: String,
    val ts: Long,
    val editedTs: Long?,
    val replyTo: Long?,
    val reactions: List<Reaction>,
    val deleted: Boolean,
    val editHistory: List<String>,
    val system: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val replyAuthor: Long? = null,
    val replySnippet: String? = null,
    /** further replied-to messages: (id, author, snippet) */
    val moreReplies: List<ReplyRef> = emptyList(),
) {
    companion object {
        fun from(o: JSONObject) = Message(
            id = o.getLong("id"),
            channelId = o.getLong("channel_id"),
            authorId = o.getLong("author_id"),
            content = o.getString("content"),
            ts = o.getLong("ts"),
            editedTs = o.optLongOrNull("edited_ts"),
            replyTo = o.optLongOrNull("reply_to"),
            reactions = o.getJSONArray("reactions").map { Reaction(it.getString("emoji"), it.getJSONArray("user_ids").longs()) },
            deleted = o.getBoolean("deleted"),
            editHistory = o.getJSONArray("edit_history").strings(),
            system = o.optStringOrNull("system"),
            attachments = o.optJSONArray("attachments")?.map { Attachment.from(it) } ?: emptyList(),
            replyAuthor = o.optLongOrNull("reply_author"),
            replySnippet = o.optStringOrNull("reply_snippet"),
            moreReplies = o.optJSONArray("more_replies")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { r -> ReplyRef(r.getLong("id"), r.optLongOrNull("author"), r.optStringOrNull("snippet")) } } } ?: emptyList(),
        )
    }
}

enum class AttachmentKind { IMAGE, VIDEO, AUDIO, FILE, LINK }

@Immutable
data class Attachment(val kind: AttachmentKind, val name: String, val url: String?, val href: String?, val width: Int, val height: Int, val preview: String? = null) {
    companion object {
        fun from(o: JSONObject) = Attachment(
            AttachmentKind.valueOf(o.getString("kind").uppercase()), o.getString("name"),
            o.optStringOrNull("url"), o.optStringOrNull("href"), o.optInt("width"), o.optInt("height"), o.optStringOrNull("preview"),
        )
    }
}

@Immutable
data class ServerRef(val id: Long, val name: String, val hue: Int, val shape: Int, val iconUrl: String? = null)

@Immutable
data class UserRef(val id: Long, val displayName: String, val hue: Int, val status: Status)

@Immutable
data class Mutuals(val servers: List<ServerRef>, val friends: List<UserRef>) {
    companion object {
        fun from(o: JSONObject) = Mutuals(
            o.getJSONArray("servers").map { ServerRef(it.getLong("id"), it.getString("name"), it.getInt("hue"), it.getInt("shape"), it.optStringOrNull("icon_url")) },
            o.getJSONArray("friends").map { UserRef(it.getLong("id"), it.getString("display_name"), it.getInt("hue"), status(it.getString("status"))) },
        )
    }
}

@Immutable
data class Plugin(
    val id: String,
    val name: String,
    val description: String,
    val isCore: Boolean,
    val enabled: Boolean,
    val config: JSONObject?,
) {
    companion object {
        fun from(o: JSONObject) = Plugin(
            o.getString("id"), o.getString("name"), o.getString("description"),
            o.getString("kind") == "core", o.getBoolean("enabled"), o.optJSONObject("config"),
        )
    }
}

sealed interface Event {
    data class NewMessage(val message: Message) : Event
    data class Update(val message: Message) : Event
    data class Remove(val id: Long) : Event
    data class Typing(val channelId: Long, val userId: Long) : Event
    data class StatusChange(val userId: Long, val status: Status) : Event
    data object DmsChanged : Event
    data class Voice(val channelId: Long, val member: JSONObject) : Event
    data class VoiceLeft(val channelId: Long, val userId: Long) : Event
    data class Ring(val channelId: Long, val callerId: Long) : Event
    data class RingDeclined(val channelId: Long, val userId: Long) : Event
    data class UserChanged(val user: User) : Event
    data object Unknown : Event

    companion object {
        fun from(o: JSONObject): Event = when (o.getString("type")) {
            "message" -> NewMessage(Message.from(o.getJSONObject("message")))
            "update" -> Update(Message.from(o.getJSONObject("message")))
            "remove" -> Remove(o.getLong("id"))
            "status" -> StatusChange(o.getLong("user_id"), status(o.getString("status")))
            "dms" -> DmsChanged
            "voice" -> Voice(o.getLong("channel_id"), o.getJSONObject("member"))
            "voice_left" -> VoiceLeft(o.getLong("channel_id"), o.getLong("user_id"))
            "ring" -> Ring(o.getLong("channel_id"), o.getLong("caller_id"))
            "ring_declined" -> RingDeclined(o.getLong("channel_id"), o.getLong("user_id"))
            "user" -> UserChanged(User.from(o.getJSONObject("user")))
            // only real typing events are typing; anything new is ignored instead of
            // throwing and taking the rest of the batch down with it
            "typing" -> Typing(o.getLong("channel_id"), o.getLong("user_id"))
            else -> if (o.has("channel_id") && o.has("user_id") && o.length() <= 3) Typing(o.getLong("channel_id"), o.getLong("user_id")) else Unknown
        }
    }
}

enum class AuthMode { SIGNED_OUT, DEMO, ROOT }

data class AuthState(val mode: AuthMode, val me: User?) {
    companion object {
        fun from(o: JSONObject) = AuthState(
            AuthMode.valueOf(o.getString("mode").uppercase()),
            o.optJSONObject("me")?.let { User.from(it) },
        )
    }
}

sealed interface SignInResult {
    data class Done(val state: AuthState) : SignInResult
    data class Challenge(val url: String) : SignInResult
}

@Immutable
data class DirectMessage(
    val id: Long,
    val userIds: List<Long>,
    val lastMessage: String?,
    val lastAuthor: Long?,
    val lastActivity: Long,
    val unread: Boolean,
) {
    companion object {
        fun from(o: JSONObject) = DirectMessage(
            o.getLong("id"), o.getJSONArray("user_ids").longs(), o.optStringOrNull("last_message"),
            o.optLongOrNull("last_author"), o.optLong("last_activity"), o.optBoolean("unread"),
        )
    }
}

@Immutable data class Gif(val preview: String, val url: String, val width: Int, val height: Int)
@Immutable data class CustomEmoji(val id: String, val shortcode: String, val url: String)
@Immutable data class EmojiEntry(val emoji: String, val names: List<String>, val category: String)

@Immutable
data class Notification(val id: String, val type: Int, val userId: Long?, val channelId: Long?, val viewed: Boolean, val ts: Long, val text: String?, val messageId: Long? = null, val place: String? = null) {
    val isFriendRequest get() = type == 1
    val isMention get() = type == 3 || type == 7 || type == 8
    companion object {
        fun from(o: JSONObject) = Notification(
            o.getString("id"), o.getInt("type"), o.optLongOrNull("user_id"), o.optLongOrNull("channel_id"),
            o.optBoolean("viewed"), o.optLong("ts"), o.optStringOrNull("text"),
            o.optLongOrNull("message_id"), o.optStringOrNull("place"),
        )
    }
}

data class ReplyRef(val id: Long, val author: Long?, val snippet: String?)
