//! Plugin engine. Ideas from Vencord's plugin set, rebuilt natively.
//!
//! Two kinds of plugins:
//! - *Core* plugins run in Rust and transform outgoing text or incoming events.
//! - *UI* plugins are just a registered flag + metadata; the Compose UI checks
//!   `enabled(id)` to switch the behaviour on.

mod text;

use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashMap;

#[derive(Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    Core,
    Ui,
}

#[derive(Clone, Serialize)]
pub struct Meta {
    pub id: &'static str,
    pub name: &'static str,
    pub description: &'static str,
    pub kind: Kind,
    pub default_on: bool,
}

pub const REGISTRY: &[Meta] = &[
    m("mutual_view", "Mutual View", "Shows mutual servers and mutual friends on every profile.", Kind::Ui, true),
    m("message_logger", "Message Logger", "Keeps deleted messages (shown struck through) and edit history.", Kind::Core, true),
    m("clear_urls", "Clear URLs", "Strips tracking parameters (utm_*, fbclid, si, ...) from links you send.", Kind::Core, true),
    m("text_replace", "Text Replace", "Find/replace rules applied to messages before sending.", Kind::Core, false),
    m("message_tags", "Message Tags", "Type {{name}} to insert a saved snippet.", Kind::Core, true),
    m("more_kaomoji", "More Kaomoji", "Adds /shrug, /tableflip, /unflip, /lenny and more.", Kind::Core, true),
    m("send_timestamps", "Send Timestamps", "Write [14:30] and it's sent as a timestamp that shows in everyone's local time.", Kind::Core, true),
    m("unindent", "Unindent", "Removes extra indentation from code blocks you send.", Kind::Core, true),
    m("silent_typing", "Silent Typing", "Don't tell others when you are typing.", Kind::Core, false),
    m("no_reply_mention", "No Reply Mention", "Replies don't ping the author by default.", Kind::Core, false),
    m("friends_since", "Friends Since", "Shows when you became friends on profiles.", Kind::Ui, true),
    m("platform_indicators", "Platform Indicators", "Shows whether people are on mobile, desktop or web.", Kind::Ui, true),
    m("who_reacted", "Who Reacted", "Shows avatars of who reacted under each reaction.", Kind::Ui, true),
    m("view_raw", "View Raw", "Long-press a message to see its raw text.", Kind::Ui, true),
    m("copy_user_urls", "Copy User URLs", "Adds 'Copy profile link' to profiles.", Kind::Ui, true),
    m("read_all", "Read All Notifications", "Adds a button that marks every server read.", Kind::Ui, true),
    m("pin_dms", "Pin DMs", "Pin conversations to the top of your DM list.", Kind::Ui, false),
    m("relative_timestamps", "Relative Timestamps", "Shows '5m ago' instead of clock times.", Kind::Ui, false),
];

const fn m(id: &'static str, name: &'static str, description: &'static str, kind: Kind, default_on: bool) -> Meta {
    Meta { id, name, description, kind, default_on }
}

/// Persisted plugin state.
#[derive(Default)]
pub struct Plugins {
    enabled: HashMap<String, bool>,
    config: HashMap<String, Value>,
}

/// What the UI should do after `on_send`.
#[derive(Serialize)]
pub struct Outgoing {
    pub text: String,
    pub mention_reply: bool,
}

impl Plugins {
    pub fn from_json(v: &Value) -> Self {
        let mut p = Plugins::default();
        if let Some(obj) = v.get("enabled").and_then(Value::as_object) {
            for (k, v) in obj {
                if let Some(b) = v.as_bool() {
                    p.enabled.insert(k.clone(), b);
                }
            }
        }
        if let Some(obj) = v.get("config").and_then(Value::as_object) {
            p.config = obj.iter().map(|(k, v)| (k.clone(), v.clone())).collect();
        }
        p.seed_defaults();
        p
    }

    fn seed_defaults(&mut self) {
        self.config.entry("message_tags".into()).or_insert_with(|| {
            json!({ "tags": { "gm": "good morning everyone ☀️", "brb": "be right back, grabbing snacks" } })
        });
        self.config.entry("text_replace".into()).or_insert_with(|| json!({ "rules": [{ "find": "teh", "replace": "the" }] }));
    }

    pub fn to_json(&self) -> Value {
        json!({ "enabled": self.enabled, "config": self.config })
    }

    pub fn enabled(&self, id: &str) -> bool {
        self.enabled
            .get(id)
            .copied()
            .unwrap_or_else(|| REGISTRY.iter().find(|m| m.id == id).is_some_and(|m| m.default_on))
    }

    pub fn set_enabled(&mut self, id: &str, on: bool) -> bool {
        if REGISTRY.iter().any(|m| m.id == id) {
            self.enabled.insert(id.to_string(), on);
            true
        } else {
            false
        }
    }

    pub fn set_config(&mut self, id: &str, cfg: Value) {
        self.config.insert(id.to_string(), cfg);
    }

    pub fn list(&self) -> Value {
        Value::Array(
            REGISTRY
                .iter()
                .map(|m| {
                    let mut v = serde_json::to_value(m).unwrap();
                    v["enabled"] = json!(self.enabled(m.id));
                    v["config"] = self.config.get(m.id).cloned().unwrap_or(Value::Null);
                    v
                })
                .collect(),
        )
    }

    /// Runs every enabled send-side plugin, in a fixed order.
    pub fn on_send(&self, input: &str, now_unix: i64, tz_offset_min: i32) -> Outgoing {
        let mut s = input.to_string();
        if self.enabled("more_kaomoji") {
            s = text::kaomoji(&s);
        }
        if self.enabled("message_tags") {
            if let Some(tags) = self.config.get("message_tags").and_then(|c| c.get("tags")).and_then(Value::as_object) {
                s = text::expand_tags(&s, tags);
            }
        }
        if self.enabled("text_replace") {
            if let Some(rules) = self.config.get("text_replace").and_then(|c| c.get("rules")).and_then(Value::as_array) {
                for r in rules {
                    if let (Some(f), Some(t)) = (r["find"].as_str(), r["replace"].as_str()) {
                        if !f.is_empty() {
                            s = s.replace(f, t);
                        }
                    }
                }
            }
        }
        if self.enabled("unindent") {
            s = text::unindent_code_blocks(&s);
        }
        if self.enabled("send_timestamps") {
            s = text::bracket_times(&s, now_unix, tz_offset_min);
        }
        if self.enabled("clear_urls") {
            s = text::clear_urls(&s);
        }
        Outgoing { text: s, mention_reply: !self.enabled("no_reply_mention") }
    }
}
