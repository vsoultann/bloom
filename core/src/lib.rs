//! Bloom core: all state, data, and plugin logic lives here in Rust.
//! The Kotlin UI talks to it through one JNI entry point, `Native.call`,
//! with a method name and JSON args, and gets JSON back.

pub mod backend;
pub mod emoji;
pub mod gifs;
pub mod grpcweb;
pub mod log;
pub mod model;
pub mod mutuals;
pub mod plugins;
pub mod rootapi;

use backend::{Backend, Event, MockBackend};
use model::{Id, Message};
use plugins::Plugins;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

#[derive(Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Mode {
    SignedOut,
    Demo,
    Root,
}

pub struct App {
    backend: Box<dyn Backend>,
    mode: Mode,
    plugins: Plugins,
    /// Free-form UI settings (theme, amoled, avatar path, ...), persisted as JSON.
    settings: serde_json::Map<String, Value>,
    data_dir: PathBuf,
    tz_offset_min: i32,
}

static APP: Mutex<Option<App>> = Mutex::new(None);

pub(crate) fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

fn id(v: &Value, key: &str) -> Result<Id, String> {
    v.get(key).and_then(Value::as_u64).ok_or_else(|| format!("missing '{key}'"))
}

fn text<'a>(v: &'a Value, key: &str) -> Result<&'a str, String> {
    v.get(key).and_then(Value::as_str).ok_or_else(|| format!("missing '{key}'"))
}

impl App {
    pub fn new(data_dir: PathBuf, tz_offset_min: i32) -> Self {
        let plugins = std::fs::read_to_string(data_dir.join("plugins.json"))
            .ok()
            .and_then(|s| serde_json::from_str::<Value>(&s).ok())
            .map(|v| Plugins::from_json(&v))
            .unwrap_or_else(|| Plugins::from_json(&Value::Null));
        let settings = std::fs::read_to_string(data_dir.join("settings.json"))
            .ok()
            .and_then(|s| serde_json::from_str::<Value>(&s).ok())
            .and_then(|v| v.as_object().cloned())
            .unwrap_or_default();
        let mut app = App { backend: Box::new(MockBackend::new(now())), mode: Mode::SignedOut, plugins, settings, data_dir, tz_offset_min };
        if let Some(s) = rootapi::Session::load(&app.data_dir) {
            // Instant start: show the last snapshot, then the UI runs `refresh` steps in the background.
            let snap = rootapi::Snapshot::load(&app.data_dir).unwrap_or_default();
            let mut rb = rootapi::RootBackend::from_snapshot(&s, app.dump_dir(), snap);
            rb.mark_bots();
            rb.clean_emoji_names();
            app.backend = Box::new(rb);
            app.mode = Mode::Root;
        }
        app
    }

    /// Raw API responses are kept here while testing so the schema can be verified.
    fn dump_dir(&self) -> Option<PathBuf> {
        // Off by default now that decoding is verified; can be re-enabled for debugging.
        self.settings.get("debug_dump").and_then(Value::as_bool).unwrap_or(false).then(|| self.data_dir.join("rootdump"))
    }

    fn auth_state(&self) -> Value {
        let me = (self.mode != Mode::SignedOut).then(|| self.backend.users().get(&self.backend.me()).cloned()).flatten();
        json!({ "mode": self.mode, "me": me })
    }

    fn save_settings(&self) {
        let _ = std::fs::create_dir_all(&self.data_dir);
        let _ = std::fs::write(self.data_dir.join("settings.json"), Value::Object(self.settings.clone()).to_string());
    }

    fn save_plugins(&self) {
        let _ = std::fs::create_dir_all(&self.data_dir);
        let _ = std::fs::write(self.data_dir.join("plugins.json"), self.plugins.to_json().to_string());
    }

    /// Applies an event to the cache, honouring Message Logger. Returns the
    /// JSON the UI should receive.
    fn apply(&mut self, ev: Event) -> Value {
        let logger = self.plugins.enabled("message_logger");
        let me = self.backend.me();
        match ev {
            Event::Message(m) => {
                self.backend.insert(m.clone());
                json!({ "type": "message", "message": m })
            }
            Event::Edit { id, content, ts } => match self.backend.message_mut(id) {
                Some(m) => {
                    if logger && m.content != content {
                        let old = std::mem::replace(&mut m.content, content);
                        m.edit_history.push(old);
                    } else {
                        m.content = content;
                    }
                    m.edited_ts = Some(ts);
                    json!({ "type": "update", "message": m })
                }
                None => Value::Null,
            },
            Event::Delete { id } => {
                let keep = logger && self.backend.message_mut(id).is_some_and(|m| m.author_id != me);
                if keep {
                    let m = self.backend.message_mut(id).unwrap();
                    m.deleted = true;
                    json!({ "type": "update", "message": m })
                } else {
                    self.backend.remove(id);
                    json!({ "type": "remove", "id": id })
                }
            }
            Event::Typing { channel_id, user_id } => json!({ "type": "typing", "channel_id": channel_id, "user_id": user_id }),
            Event::Status { user_id, status } => json!({ "type": "status", "user_id": user_id, "status": status }),
            Event::DmsChanged => json!({ "type": "dms" }),
            Event::Replace(m) => {
                if let Some(cur) = self.backend.message_mut(m.id) {
                    *cur = m.clone();
                }
                json!({ "type": "update", "message": m })
            }
            Event::Voice { channel_id, member } => json!({ "type": "voice", "channel_id": channel_id, "member": member }),
            Event::VoiceLeft { channel_id, user_id } => json!({ "type": "voice_left", "channel_id": channel_id, "user_id": user_id }),
            Event::Ring { channel_id, caller_id } => json!({ "type": "ring", "channel_id": channel_id, "caller_id": caller_id }),
            Event::RingDeclined { channel_id, user_id } => json!({ "type": "ring_declined", "channel_id": channel_id, "user_id": user_id }),
            Event::Reaction { id, emoji, user_id, added } => match self.backend.message_mut(id) {
                Some(m) => {
                    match m.reactions.iter_mut().find(|r| r.emoji == emoji) {
                        Some(r) => {
                            r.user_ids.retain(|u| *u != user_id);
                            if added {
                                r.user_ids.push(user_id);
                            }
                        }
                        None if added => m.reactions.push(model::Reaction { emoji, user_ids: vec![user_id] }),
                        None => {}
                    }
                    m.reactions.retain(|r| !r.user_ids.is_empty());
                    json!({ "type": "update", "message": m })
                }
                None => Value::Null,
            },
        }
    }

    pub fn dispatch(&mut self, method: &str, a: &Value) -> Result<Value, String> {
        // Account methods work while signed out.
        match method {
            "auth_state" => return Ok(self.auth_state()),
            "sign_in" => {
                let user = text(a, "username")?;
                let pass = text(a, "password")?;
                let turnstile = a.get("turnstile").and_then(Value::as_str).unwrap_or("");
                let device = a.get("device").and_then(Value::as_str).unwrap_or("Android");
                let os = a.get("os_version").and_then(Value::as_str).unwrap_or("");
                return match rootapi::sign_in(&self.data_dir, user, pass, turnstile, device, os)? {
                    rootapi::SignIn::Challenge(url) => Ok(json!({ "challenge_url": url })),
                    rootapi::SignIn::Ok(session) => {
                        // Save first: a later failure shouldn't force another password + security check.
                        session.save(&self.data_dir);
                        // start empty; the UI runs the refresh steps (first one loads your profile)
                        let mut b = rootapi::RootBackend::from_snapshot(&session, self.dump_dir(), Default::default());
                        b.refresh(0)?;
                        self.backend = Box::new(b);
                        self.mode = Mode::Root;
                        Ok(self.auth_state())
                    }
                };
            }
            "demo" => {
                self.backend = Box::new(MockBackend::new(now()));
                self.mode = Mode::Demo;
                return Ok(self.auth_state());
            }
            "sign_out" => {
                rootapi::Session::clear(&self.data_dir);
                let _ = std::fs::remove_file(self.data_dir.join("cache.json"));
                self.backend = Box::new(MockBackend::new(now()));
                self.mode = Mode::SignedOut;
                return Ok(self.auth_state());
            }
            "emoji_catalog" => return Ok(json!(emoji::catalog())),
            "settings" | "set_setting" | "plugins" | "set_plugin" | "set_plugin_config" | "preview_send" => {}
            _ if self.mode == Mode::SignedOut => return Err("signed out".into()),
            _ => {}
        }
        if method == "take_error" {
            return Ok(json!(self.backend.take_error()));
        }
        let b = &mut self.backend;
        Ok(match method {
            "me" => json!(b.users().get(&b.me())),
            "users" => json!(b.users().values().collect::<Vec<_>>()),
            "servers" => json!(b.servers()),
            "dms" => json!(b.dms()),
            "messages" => json!(b.messages(id(a, "channel")?)),
            "friends" => {
                let me = b.me();
                let mut list: Vec<_> = b
                    .friends_of()
                    .get(&me)
                    .into_iter()
                    .flatten()
                    .filter_map(|f| b.users().get(f))
                    .collect();
                list.sort_by_key(|u| (u.status as u8, u.display_name.to_lowercase()));
                json!(list)
            }
            "mutuals" => {
                let other = id(a, "user")?;
                match b.remote_mutuals(other) {
                    Some((friends, communities)) => json!({
                        "servers": b.servers().iter().filter(|s| communities.contains(&s.id)).map(model::ServerRef::from).collect::<Vec<_>>(),
                        "friends": friends.iter().filter_map(|f| b.users().get(f)).map(model::UserRef::from).collect::<Vec<_>>(),
                    }),
                    None => json!(mutuals::compute(b.me(), other, b.servers(), b.friends_of(), b.users())),
                }
            }
            "send" => {
                let channel = id(a, "channel")?;
                let raw = text(a, "text")?;
                let out = self.plugins.on_send(raw, now(), self.tz_offset_min);
                let has_atts = a.get("attachments").and_then(Value::as_array).is_some_and(|v| !v.is_empty());
                if out.text.trim().is_empty() && !has_atts {
                    return Err("empty message".into());
                }
                let reply_to = a.get("reply_to").and_then(Value::as_u64);
                let extra: Vec<Id> = a.get("reply_ids").and_then(Value::as_array).map(|v| v.iter().filter_map(Value::as_u64).filter(|x| Some(*x) != reply_to).collect()).unwrap_or_default();
                self.backend.set_extra_replies(extra);
                let atts: Vec<String> = a.get("attachments").and_then(Value::as_array).map(|v| v.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default();
                let m: Message = self.backend.send_with(channel, out.text, reply_to, now(), atts);
                json!({ "message": m, "mention_reply": out.mention_reply })
            }
            "preview_send" => {
                let out = self.plugins.on_send(text(a, "text")?, now(), self.tz_offset_min);
                json!(out.text)
            }
            "edit" => {
                let ev = b.edit(id(a, "id")?, text(a, "text")?.to_string(), now()).ok_or("no such message")?;
                self.apply(ev)
            }
            "delete" => {
                let ev = b.delete(id(a, "id")?).ok_or("no such message")?;
                self.apply(ev)
            }
            "react" => {
                let mid = id(a, "id")?;
                b.react(mid, text(a, "emoji")?);
                json!(b.message_mut(mid))
            }
            "typing" => {
                let send = !self.plugins.enabled("silent_typing");
                if send {
                    if let Ok(ch) = id(a, "channel") {
                        self.backend.set_typing(ch);
                    }
                }
                json!({ "send": send })
            }
            "mark_read" => {
                b.mark_read(a.get("server").and_then(Value::as_u64));
                json!(true)
            }
            "poll" => {
                let evs = b.poll(now());
                Value::Array(evs.into_iter().map(|e| self.apply(e)).filter(|v| !v.is_null()).collect())
            }
            "settings" => Value::Object(self.settings.clone()),
            "set_setting" => {
                let key = text(a, "key")?.to_string();
                match a.get("value") {
                    Some(Value::Null) | None => self.settings.remove(&key),
                    Some(v) => self.settings.insert(key, v.clone()),
                };
                self.save_settings();
                Value::Object(self.settings.clone())
            }
            "upload" => json!(b.upload(text(a, "path")?, text(a, "name")?, a.get("mime").and_then(Value::as_str).unwrap_or(""))?),
            "edit_profile" => json!(b.edit_profile(a)?),
            "refresh" => json!({ "next": b.refresh(a.get("step").and_then(Value::as_u64).unwrap_or(0) as u32)? }),
            "save_cache" => {
                b.save_cache(&self.data_dir);
                json!(true)
            }
            "cached_messages" => json!(b.cached_messages(id(a, "channel")?)),
            "older_messages" => json!(b.older_messages(id(a, "channel")?)?),
            "mark_viewed" => {
                b.mark_viewed(id(a, "channel")?);
                json!(true)
            }
            m if m.starts_with("voice_") => b.voice(&m["voice_".len()..], a)?,
            m if m.starts_with("social_") => b.social(&m["social_".len()..], a)?,
            "gif_favorites" => json!(b.gif_favorites()),
            "gif_favorite" => {
                b.gif_favorite(text(a, "url")?, a.get("add").and_then(Value::as_bool).unwrap_or(true));
                json!(true)
            }
            "custom_emoji" => json!(b.custom_emoji().into_iter().map(|(id, code, url)| json!({"id": id, "shortcode": code, "url": url})).collect::<Vec<_>>()),
            "gif_search" => {
                let key = self.settings.get("klipy_key").and_then(Value::as_str).unwrap_or("").trim().to_string();
                if key.is_empty() {
                    return Err("Add a GIF API key in Settings → GIFs".into());
                }
                let q = a.get("query").and_then(Value::as_str).unwrap_or("").trim();
                gifs::search(&key, q)?
            }
            "plugins" => self.plugins.list(),
            "set_plugin" => {
                let pid = text(a, "id")?;
                let on = a.get("enabled").and_then(Value::as_bool).ok_or("missing 'enabled'")?;
                if !self.plugins.set_enabled(pid, on) {
                    return Err(format!("unknown plugin {pid}"));
                }
                self.save_plugins();
                self.plugins.list()
            }
            "set_plugin_config" => {
                self.plugins.set_config(text(a, "id")?, a.get("config").cloned().unwrap_or(Value::Null));
                self.save_plugins();
                self.plugins.list()
            }
            _ => return Err(format!("unknown method {method}")),
        })
    }
}

/// Entry point used by both JNI and tests.
pub fn call(method: &str, args: &str) -> String {
    let args: Value = serde_json::from_str(args).unwrap_or(Value::Null);
    let mut guard = match APP.lock() {
        Ok(g) => g,
        Err(p) => p.into_inner(),
    };
    let res = if method == "init" {
        let dir = args.get("data_dir").and_then(Value::as_str).unwrap_or(".").to_string();
        let tz = args.get("tz_offset_min").and_then(Value::as_i64).unwrap_or(0) as i32;
        if guard.is_none() {
            log::init(&dir);
            *guard = Some(App::new(PathBuf::from(dir), tz));
        } else if let Some(app) = guard.as_mut() {
            app.tz_offset_min = tz;
        }
        Ok(json!(true))
    } else if method == "logs" {
        Ok(json!(log::read()))
    } else if method == "clear_logs" {
        log::clear();
        Ok(json!(true))
    } else if method == "log" {
        // the app records its own events (e.g. crashes) here too
        log::write(args.get("kind").and_then(Value::as_str).unwrap_or("app"), args.get("text").and_then(Value::as_str).unwrap_or(""));
        Ok(json!(true))
    } else {
        // a panic in the core must not take the app down; log it and report an error
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| match guard.as_mut() {
            Some(app) => app.dispatch(method, &args),
            None => Err("core not initialised".into()),
        }));
        match r {
            Ok(v) => v,
            Err(p) => {
                let msg = p.downcast_ref::<String>().cloned().or_else(|| p.downcast_ref::<&str>().map(|s| s.to_string())).unwrap_or_else(|| "panic".into());
                log::write("panic", &format!("{method}: {msg}"));
                Err(format!("internal error in {method}"))
            }
        }
    };
    if let Err(e) = &res {
        if !matches!(method, "poll" | "take_error") { log::write("error", &format!("{method}: {e}")); }
    }
    match res {
        Ok(v) => json!({ "ok": v }).to_string(),
        Err(e) => json!({ "err": e }).to_string(),
    }
}

#[cfg(target_os = "android")]
mod jni_bridge {
    use jni::objects::{JClass, JString};
    use jni::sys::jstring;
    use jni::JNIEnv;

    #[no_mangle]
    pub extern "system" fn Java_app_bloom_core_Native_call<'l>(
        mut env: JNIEnv<'l>,
        _class: JClass<'l>,
        method: JString<'l>,
        args: JString<'l>,
    ) -> jstring {
        let method: String = env.get_string(&method).map(Into::into).unwrap_or_default();
        let args: String = env.get_string(&args).map(Into::into).unwrap_or_default();
        let out = super::call(&method, &args);
        env.new_string(out).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn end_to_end() {
        let dir = std::env::temp_dir().join("bloom-test");
        let _ = std::fs::remove_dir_all(&dir);
        let r: Value = serde_json::from_str(&call("init", &json!({"data_dir": dir}).to_string())).unwrap();
        assert_eq!(r["ok"], true);
        let st: Value = serde_json::from_str(&call("auth_state", "{}")).unwrap();
        assert_eq!(st["ok"]["mode"], "signed_out");
        let blocked: Value = serde_json::from_str(&call("servers", "{}")).unwrap();
        assert_eq!(blocked["err"], "signed out");
        let d: Value = serde_json::from_str(&call("demo", "{}")).unwrap();
        assert_eq!(d["ok"]["mode"], "demo");

        let servers: Value = serde_json::from_str(&call("servers", "{}")).unwrap();
        let ch = servers["ok"][0]["channels"][0]["id"].as_u64().unwrap();

        let sent: Value = serde_json::from_str(&call(
            "send",
            &json!({"channel": ch, "text": "/shrug https://x.io/a?utm_source=q"}).to_string(),
        ))
        .unwrap();
        assert_eq!(sent["ok"]["message"]["content"], "¯\\_(ツ)_/¯ https://x.io/a");

        let st: Value = serde_json::from_str(&call("set_setting", r#"{"key":"theme","value":"discord"}"#)).unwrap();
        assert_eq!(st["ok"]["theme"], "discord");
        assert!(std::fs::read_to_string(dir.join("settings.json")).unwrap().contains("discord"));

        let other = servers["ok"][0]["member_ids"][1].as_u64().unwrap();
        let m: Value = serde_json::from_str(&call("mutuals", &json!({"user": other}).to_string())).unwrap();
        assert!(m["ok"]["servers"].as_array().unwrap().iter().any(|s| s["id"] == servers["ok"][0]["id"]));
    }
}
