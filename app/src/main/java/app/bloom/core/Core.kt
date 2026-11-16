package app.bloom.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** JNI bridge to the Rust core (core/src/lib.rs). */
object Native {
    init { System.loadLibrary("bloomcore") }
    @JvmStatic external fun call(method: String, args: String): String
}

class CoreException(msg: String) : Exception(msg)

/** Typed, coroutine-friendly wrapper around [Native.call]. Calls run off the main thread. */
object Core {
    private suspend fun raw(method: String, args: JSONObject = JSONObject()): Any? =
        withContext(Dispatchers.Default) {
            val res = JSONObject(Native.call(method, args.toString()))
            if (res.has("err")) throw CoreException(res.getString("err"))
            res.opt("ok")
        }

    private fun args(vararg pairs: Pair<String, Any?>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    suspend fun init(dataDir: String, tzOffsetMin: Int) {
        raw("init", args("data_dir" to dataDir, "tz_offset_min" to tzOffsetMin))
    }

    suspend fun authState() = AuthState.from(raw("auth_state") as JSONObject)

    /** Returns either the new auth state, or a Turnstile challenge URL the user must solve. */
    suspend fun signIn(username: String, password: String, turnstile: String?, device: String, osVersion: String): SignInResult {
        val o = raw("sign_in", args("username" to username, "password" to password, "turnstile" to turnstile, "device" to device, "os_version" to osVersion)) as JSONObject
        return if (o.has("challenge_url")) SignInResult.Challenge(o.getString("challenge_url")) else SignInResult.Done(AuthState.from(o))
    }

    suspend fun demo() = AuthState.from(raw("demo") as JSONObject)
    suspend fun signOut() = AuthState.from(raw("sign_out") as JSONObject)
    suspend fun takeError() = raw("take_error") as? String

    // diagnostics log (failed API calls, core errors/panics, app errors)
    suspend fun logs() = raw("logs") as? String ?: ""
    suspend fun clearLogs() = raw("clear_logs")
    suspend fun log(kind: String, text: String) = raw("log", args("kind" to kind, "text" to text))

    suspend fun me() = User.from(raw("me") as JSONObject)
    suspend fun users() = (raw("users") as JSONArray).map { User.from(it) }
    suspend fun friends() = (raw("friends") as JSONArray).map { User.from(it) }
    suspend fun dms() = (raw("dms") as JSONArray).map { DirectMessage.from(it) }
    suspend fun servers() = (raw("servers") as JSONArray).map { Server.from(it) }
    suspend fun messages(channel: Long) = (raw("messages", args("channel" to channel)) as JSONArray).map { Message.from(it) }

    suspend fun send(channel: Long, text: String, replyTo: Long?, attachments: List<String> = emptyList(), replyIds: List<Long> = emptyList()): Message {
        val o = raw("send", args("channel" to channel, "text" to text, "reply_to" to replyTo, "attachments" to JSONArray(attachments), "reply_ids" to JSONArray(replyIds))) as JSONObject
        return Message.from(o.getJSONObject("message"))
    }

    suspend fun previewSend(text: String) = raw("preview_send", args("text" to text)) as String
    suspend fun edit(id: Long, text: String) = Event.from(raw("edit", args("id" to id, "text" to text)) as JSONObject)
    suspend fun delete(id: Long) = Event.from(raw("delete", args("id" to id)) as JSONObject)
    suspend fun react(id: Long, emoji: String) = Message.from(raw("react", args("id" to id, "emoji" to emoji)) as JSONObject)
    suspend fun typing(channel: Long) = (raw("typing", args("channel" to channel)) as JSONObject).getBoolean("send")
    suspend fun markRead(server: Long?) { raw("mark_read", args("server" to server)) }
    /** Live events; one we can't read is skipped on its own instead of losing the whole batch. */
    suspend fun poll() = (raw("poll") as JSONArray).let { a -> (0 until a.length()).mapNotNull { i -> runCatching { Event.from(a.getJSONObject(i)) }.getOrNull() } }
    suspend fun mutuals(user: Long) = Mutuals.from(raw("mutuals", args("user" to user)) as JSONObject)

    suspend fun upload(path: String, name: String, mime: String) = raw("upload", args("path" to path, "name" to name, "mime" to mime)) as String
    suspend fun gifSearch(query: String) = (raw("gif_search", args("query" to query)) as JSONArray).map { Gif(it.getString("preview"), it.getString("url"), it.optInt("width"), it.optInt("height")) }
    suspend fun gifFavorites() = (raw("gif_favorites") as JSONArray).let { a -> List(a.length()) { a.getString(it) } }
    suspend fun gifFavorite(url: String, add: Boolean) { raw("gif_favorite", args("url" to url, "add" to add)) }
    suspend fun customEmoji() = (raw("custom_emoji") as JSONArray).map { CustomEmoji(it.getString("id"), it.getString("shortcode"), it.getString("url")) }
    suspend fun emojiCatalog() = (raw("emoji_catalog") as JSONArray).map { o ->
        EmojiEntry(o.getString("emoji"), o.getJSONArray("names").strings(), o.optString("category"))
    }

    /** Social / notifications ops (see core `social`). */
    suspend fun social(op: String, args: JSONObject = JSONObject()) = raw("social_$op", args)
    suspend fun notifications() = (social("notifications") as JSONArray).map { Notification.from(it) }

    /** Voice signalling: op = ice | join | tracks | renegotiate | mute | leave. */
    suspend fun voice(op: String, args: JSONObject = JSONObject()) = raw("voice_$op", args)

    suspend fun editProfile(changes: JSONObject) = User.from(raw("edit_profile", changes) as JSONObject)

    /** One background refresh step; returns the next step or null when done. */
    suspend fun refresh(step: Int): Int? = (raw("refresh", args("step" to step)) as JSONObject).let { if (it.isNull("next")) null else it.getInt("next") }
    suspend fun saveCache() { raw("save_cache") }
    suspend fun cachedMessages(channel: Long) = (raw("cached_messages", args("channel" to channel)) as JSONArray).map { Message.from(it) }
    suspend fun olderMessages(channel: Long) = (raw("older_messages", args("channel" to channel)) as JSONArray).map { Message.from(it) }
    suspend fun markViewed(channel: Long) { raw("mark_viewed", args("channel" to channel)) }

    suspend fun settings() = raw("settings") as JSONObject
    suspend fun setSetting(key: String, value: Any?) = raw("set_setting", args("key" to key, "value" to (value ?: JSONObject.NULL))) as JSONObject

    suspend fun plugins() = (raw("plugins") as JSONArray).map { Plugin.from(it) }
    suspend fun setPlugin(id: String, enabled: Boolean) =
        (raw("set_plugin", args("id" to id, "enabled" to enabled)) as JSONArray).map { Plugin.from(it) }
    suspend fun setPluginConfig(id: String, config: JSONObject) =
        (raw("set_plugin_config", args("id" to id, "config" to config)) as JSONArray).map { Plugin.from(it) }
}

internal inline fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = List(length()) { f(getJSONObject(it)) }
internal fun JSONArray.longs(): List<Long> = List(length()) { getLong(it) }
internal fun JSONArray.strings(): List<String> = List(length()) { getString(it) }
internal fun JSONObject.optLongOrNull(k: String): Long? = if (isNull(k)) null else optLong(k)
internal fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotEmpty() }
