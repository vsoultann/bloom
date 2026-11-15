//! Root message decoding: the parts of `MessagePacket` the generated schema
//! couldn't recover (attachments, reply parents, system payloads), verified
//! against live responses, plus conversion of Root's `root://` markup into the
//! markers Bloom's renderer understands.

use super::proto::{MessageUuid, StringValue, Timestamp, UserUuid};
use prost::Message;

/// Root GUIDs carry milliseconds since 2020-01-01 in their top 48 bits.
pub const GUID_EPOCH_MS: i64 = 1_577_836_800_000;

pub fn guid_time(hi: u64) -> i64 {
    ((hi >> 16) as i64 + GUID_EPOCH_MS) / 1000
}

#[derive(Clone, PartialEq, Message)]
pub struct RawMessage {
    #[prost(message, optional, tag = "5")]
    pub user_id: Option<UserUuid>,
    #[prost(message, optional, tag = "6")]
    pub id: Option<MessageUuid>,
    #[prost(message, optional, tag = "7")]
    pub deleted_at: Option<Timestamp>,
    #[prost(message, optional, tag = "8")]
    pub edited_at: Option<Timestamp>,
    #[prost(string, tag = "10")]
    pub content: String,
    /// 1 = user message, 2 = system
    #[prost(int32, tag = "11")]
    pub message_type: i32,
    #[prost(message, optional, tag = "12")]
    pub payload: Option<Payload>,
    /// Assets referenced by `root://external/N` (index N).
    #[prost(message, repeated, tag = "13")]
    pub assets: Vec<AssetRef>,
    #[prost(message, repeated, tag = "15")]
    pub reactions: Vec<RawReaction>,
    #[prost(message, repeated, tag = "16")]
    pub parents: Vec<ParentRef>,
}

#[derive(Clone, PartialEq, Message)]
pub struct RawReaction {
    #[prost(string, tag = "10")]
    pub shortcode: String,
    #[prost(message, optional, tag = "11")]
    pub user_id: Option<super::proto::UserUuid>,
}

#[derive(Clone, PartialEq, Message)]
pub struct AssetRef {
    #[prost(string, tag = "1")]
    pub uri: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct ParentRef {
    #[prost(message, optional, tag = "1")]
    pub id: Option<MessageUuid>,
    #[prost(message, optional, tag = "2")]
    pub user_id: Option<UserUuid>,
    #[prost(message, optional, tag = "3")]
    pub snippet: Option<StringValue>,
}

#[derive(Clone, PartialEq, Message)]
pub struct Payload {
    #[prost(message, optional, tag = "9")]
    pub primary: Option<PayloadItem>,
}

#[derive(Clone, PartialEq, Message)]
pub struct NamedUser {
    #[prost(message, optional, tag = "4")]
    pub user_id: Option<UserUuid>,
    #[prost(string, tag = "5")]
    pub username: String,
}

#[derive(Clone, PartialEq, Message)]
pub struct NamedUsers {
    #[prost(message, repeated, tag = "4")]
    pub users: Vec<NamedUser>,
}

#[derive(Clone, PartialEq, Message)]
pub struct WrappedUser {
    #[prost(message, optional, tag = "4")]
    pub user: Option<NamedUser>,
}

#[derive(Clone, PartialEq, Message)]
pub struct Empty {}

/// One-of: exactly one of these is set.
#[derive(Clone, PartialEq, Message)]
pub struct PayloadItem {
    #[prost(message, optional, tag = "4")]
    pub community: Option<Empty>,
    #[prost(message, optional, tag = "5")]
    pub channel: Option<Empty>,
    #[prost(message, optional, tag = "7")]
    pub pinned: Option<Empty>,
    #[prost(message, optional, tag = "8")]
    pub unpinned: Option<Empty>,
    #[prost(message, optional, tag = "10")]
    pub banned: Option<NamedUser>,
    #[prost(message, optional, tag = "11")]
    pub joined: Option<NamedUser>,
    #[prost(message, optional, tag = "12")]
    pub kicked: Option<NamedUser>,
    #[prost(message, optional, tag = "13")]
    pub left: Option<NamedUser>,
    #[prost(message, optional, tag = "14")]
    pub call_started: Option<Empty>,
    #[prost(message, optional, tag = "15")]
    pub call_ended: Option<Empty>,
    #[prost(message, optional, tag = "16")]
    pub dm_joined: Option<NamedUsers>,
    #[prost(message, optional, tag = "17")]
    pub dm_left: Option<WrappedUser>,
}

/// Human-readable text for a system event; the author's name is prepended by the UI.
pub fn system_text(p: &Payload) -> String {
    let Some(i) = &p.primary else { return "did something".into() };
    let name = |u: &Option<NamedUser>| u.as_ref().map(|u| u.username.clone()).unwrap_or_default();
    if i.joined.is_some() {
        format!("{} joined the community", name(&i.joined))
    } else if i.left.is_some() {
        format!("{} left the community", name(&i.left))
    } else if i.kicked.is_some() {
        format!("{} was kicked", name(&i.kicked))
    } else if i.banned.is_some() {
        format!("{} was banned", name(&i.banned))
    } else if i.pinned.is_some() {
        "pinned a message".into()
    } else if i.unpinned.is_some() {
        "unpinned a message".into()
    } else if i.community.is_some() {
        "updated the community".into()
    } else if i.channel.is_some() {
        "updated a channel".into()
    } else if i.call_started.is_some() {
        "started a call".into()
    } else if i.call_ended.is_some() {
        "call ended".into()
    } else if let Some(j) = &i.dm_joined {
        format!("added {}", j.users.iter().map(|u| u.username.as_str()).collect::<Vec<_>>().join(", "))
    } else if let Some(l) = &i.dm_left {
        format!("{} left", name(&l.user))
    } else {
        "did something".into()
    }
}

/// A `[label](root://kind/target)` link or bare `root://kind/target` found in content.
#[derive(Debug, PartialEq)]
pub enum Token<'a> {
    Text(&'a str),
    Link { label: &'a str, kind: &'a str, target: &'a str },
}

/// Splits content into plain text and `root://` links (markdown-link or bare form).
pub fn tokenize(s: &str) -> Vec<Token<'_>> {
    let mut out = Vec::new();
    let mut rest = s;
    loop {
        // markdown form: [label](root://kind/target)
        let md = rest.find("](root://").and_then(|close| {
            let open = rest[..close].rfind('[')?;
            let after = &rest[close + 2..];
            let end = after.find(')')?;
            Some((open, close, close + 2 + end + 1, &rest[open + 1..close], &after["root://".len()..end]))
        });
        let bare = rest.find("root://");
        match (md, bare) {
            // the markdown link starts before any bare root:// that precedes it
            (Some((open, _close, end, label, uri)), Some(b)) if open < b => {
                if open > 0 {
                    out.push(Token::Text(&rest[..open]));
                }
                let (kind, target) = uri.split_once('/').unwrap_or((uri, ""));
                out.push(Token::Link { label, kind, target });
                rest = &rest[end..];
            }
            (_, Some(b)) => {
                if b > 0 {
                    out.push(Token::Text(&rest[..b]));
                }
                let uri_end = rest[b..].find(|c: char| c.is_whitespace()).map(|e| b + e).unwrap_or(rest.len());
                let uri = &rest[b + "root://".len()..uri_end];
                let (kind, target) = uri.split_once('/').unwrap_or((uri, ""));
                out.push(Token::Link { label: "", kind, target });
                rest = &rest[uri_end..];
            }
            _ => {
                if !rest.is_empty() {
                    out.push(Token::Text(rest));
                }
                break;
            }
        }
    }
    out
}

/// Bloom inline markers understood by the Kotlin renderer.
/// `⟦m:name⟧` mention, `⟦c:name⟧` channel, `⟦e:URL|name⟧` custom emoji.
pub fn mention(name: &str) -> String {
    format!("⟦m:{name}⟧")
}
pub fn channel(name: &str) -> String {
    format!("⟦c:{name}⟧")
}
pub fn emoji(url: &str, name: &str) -> String {
    format!("⟦e:{url}|{name}⟧")
}

/// `1f44d` or `1f469-200d-1f4bb` -> the emoji itself (Root links unicode emoji by code points).
pub fn unicode_emoji(target: &str) -> Option<String> {
    // Root's own format for standard emoji: ":name:" (JoyPixels-style names, optional _toneN)
    if target.len() > 2 && target.starts_with(':') && target.ends_with(':') {
        return crate::emoji::from_root_name(&target[1..target.len() - 1]);
    }
    if target.is_empty() || target.len() > 64 || !target.split('-').all(|p| (2..=6).contains(&p.len()) && p.chars().all(|c| c.is_ascii_hexdigit())) {
        return None;
    }
    target.split('-').map(|p| u32::from_str_radix(p, 16).ok().and_then(char::from_u32)).collect()
}

/// Root asset ids are base64url and carry the asset type in their tail
/// (e.g. `...aW1hZ2U` = "image"). Returns image/video/audio when recognisable.
pub fn kind_for_asset_uri(uri: &str) -> Option<super::super::model::AttachmentKind> {
    use base64::Engine;
    use super::super::model::AttachmentKind::*;
    let id = uri.rsplit('/').next()?;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(id.trim_end_matches('=')).ok()?;
    let tail = String::from_utf8_lossy(&bytes[bytes.len().saturating_sub(12)..]).to_ascii_lowercase();
    if tail.contains("image") || tail.contains("gif") {
        Some(Image)
    } else if tail.contains("video") {
        Some(Video)
    } else if tail.contains("audio") {
        Some(Audio)
    } else {
        Some(File)
    }
}

pub fn kind_for_name(name: &str) -> super::super::model::AttachmentKind {
    use super::super::model::AttachmentKind::*;
    let n = name.to_ascii_lowercase();
    let ext = n.rsplit('.').next().unwrap_or("");
    if n.starts_with("http://") || n.starts_with("https://") {
        Link
    } else {
        match ext {
            "png" | "jpg" | "jpeg" | "webp" | "gif" | "heic" | "avif" | "bmp" => Image,
            "mp4" | "mov" | "webm" | "mkv" | "m4v" => Video,
            "mp3" | "m4a" | "ogg" | "opus" | "wav" | "aac" | "flac" => Audio,
            _ => File,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tokenizes_markdown_and_bare_links() {
        let t = tokenize("hi [@ann](root://user/AbC) see [a.png](root://external/0) and root://emoji/x9 ok");
        assert_eq!(t[0], Token::Text("hi "));
        assert_eq!(t[1], Token::Link { label: "@ann", kind: "user", target: "AbC" });
        assert_eq!(t[3], Token::Link { label: "a.png", kind: "external", target: "0" });
        assert_eq!(t[5], Token::Link { label: "", kind: "emoji", target: "x9" });
        assert_eq!(t[6], Token::Text(" ok"));
    }

    #[test]
    fn unicode_emoji_codes() {
        assert_eq!(unicode_emoji("1f44d").as_deref(), Some("👍"));
        assert_eq!(unicode_emoji("1f469-200d-1f4bb").as_deref(), Some("👩‍💻"));
        assert_eq!(unicode_emoji("AbCdEfGhIjKlMnOpQrStUv"), None);
    }

    #[test]
    fn new_guid_layout() {
        let (hi, lo) = super::super::new_guid(30);
        assert_eq!(hi & 0xFF, 30, "type byte");
        assert_eq!((hi >> 8) & 0xF0, 0x80, "version nibble");
        assert_eq!(lo >> 62, 0b10, "variant bits");
        let t = guid_time(hi);
        assert!((t - crate::now()).abs() < 5);
    }

    #[test]
    fn guid_epoch() {
        // A message id observed live on 2026-11-14.
        let t = guid_time(14210632268024835);
        assert!((1_794_600_000..1_794_800_000).contains(&t), "{t}");
    }
}
