//! Data model shared by the core and (as JSON) the Kotlin UI.

use serde::{Deserialize, Serialize};

pub type Id = u64;

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Status {
    Online,
    Idle,
    Dnd,
    Offline,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum Platform {
    Mobile,
    Desktop,
    Web,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct User {
    pub id: Id,
    pub name: String,
    pub display_name: String,
    /// Hue (0-360) used to tint the generated avatar.
    pub hue: u16,
    pub status: Status,
    pub platforms: Vec<Platform>,
    pub bio: String,
    pub pronouns: String,
    /// Unix seconds when you became friends, if you are.
    pub friend_since: Option<i64>,
    /// Real profile picture (https), if the backend has one.
    #[serde(default)]
    pub avatar_url: Option<String>,
    #[serde(default)]
    pub banner_url: Option<String>,
    /// Custom status text.
    #[serde(default)]
    pub status_text: String,
    /// Root Bot (author id is an App GUID).
    #[serde(default)]
    pub bot: bool,
    /// Root cosmetics (name effect, profile background, what they own/wear).
    #[serde(default)]
    pub cosmetic: Cosmetic,
}

/// A solid colour (one entry) or a gradient, as ARGB, plus the gradient angle in degrees.
#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
pub struct Fill {
    pub colors: Vec<u32>,
    pub angle: f32,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
pub struct Cosmetic {
    /// Gradient/colour their name is drawn with.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<Fill>,
    /// Profile card background.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub profile_bg: Option<Fill>,
    /// Cosmetic ids (32 hex chars) of what they wear that needs Root's art bundle.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub frame: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub border: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub font: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub effect: Option<String>,
    /// What they wear, e.g. ["Name effect · Aurora", "Avatar frame · Halo"].
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub worn: Vec<String>,
}

#[derive(Clone, Copy, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ChannelKind {
    Text,
    Voice,
    Announcement,
    /// A Root App (polls, games, ...) — not a chat.
    App,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Channel {
    pub id: Id,
    pub server_id: Id,
    pub name: String,
    pub kind: ChannelKind,
    pub category: String,
    pub topic: String,
    pub unread: bool,
    /// Channel picture set in Root (signed image URL), if any.
    #[serde(default)]
    pub icon_url: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Server {
    pub id: Id,
    pub name: String,
    pub hue: u16,
    /// Index into the UI's shape set; each server gets its own "resting" shape.
    pub shape: u8,
    pub member_ids: Vec<Id>,
    pub channels: Vec<Channel>,
    pub mentions: u32,
    #[serde(default)]
    pub icon_url: Option<String>,
    #[serde(default)]
    pub banner_url: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Reaction {
    pub emoji: String,
    pub user_ids: Vec<Id>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Message {
    pub id: Id,
    pub channel_id: Id,
    pub author_id: Id,
    pub content: String,
    pub ts: i64,
    pub edited_ts: Option<i64>,
    pub reply_to: Option<Id>,
    pub reactions: Vec<Reaction>,
    /// Message Logger: set instead of removing the message.
    pub deleted: bool,
    /// Message Logger: previous versions, oldest first.
    pub edit_history: Vec<String>,
    /// System event text ("X joined", "pinned a message"), rendered as an event row.
    #[serde(default)]
    pub system: Option<String>,
    #[serde(default)]
    pub attachments: Vec<Attachment>,
    /// Reply preview, available even when the parent message isn't loaded.
    #[serde(default)]
    pub reply_author: Option<Id>,
    /// Further messages this one replies to (Root allows replying to several at once).
    #[serde(default)]
    pub more_replies: Vec<ReplyRef>,
    #[serde(default)]
    pub reply_snippet: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum AttachmentKind {
    Image,
    Video,
    Audio,
    File,
    /// Link with a preview image
    Link,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct Attachment {
    pub kind: AttachmentKind,
    /// Display name (file name or link URL)
    pub name: String,
    /// Resolved https URL for the asset (preview/full)
    pub url: Option<String>,
    /// For links: the target URL
    pub href: Option<String>,
    /// Still preview (video thumbnail)
    #[serde(default)]
    pub preview: Option<String>,
    pub width: u32,
    pub height: u32,
}

/// A direct-message conversation. Its `id` is used as the channel id for messages.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct DirectMessage {
    pub id: Id,
    /// Other participants (excluding you).
    pub user_ids: Vec<Id>,
    pub last_message: Option<String>,
    pub last_author: Option<Id>,
    pub last_activity: i64,
    pub unread: bool,
}

/// Lightweight reference used in lists (mutuals, members) so the UI
/// doesn't have to resolve ids itself.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ServerRef {
    pub id: Id,
    pub name: String,
    pub hue: u16,
    pub shape: u8,
    #[serde(default)]
    pub icon_url: Option<String>,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct UserRef {
    pub id: Id,
    pub display_name: String,
    pub hue: u16,
    pub status: Status,
}

impl From<&Server> for ServerRef {
    fn from(s: &Server) -> Self {
        ServerRef { id: s.id, name: s.name.clone(), hue: s.hue, shape: s.shape, icon_url: s.icon_url.clone() }
    }
}

impl From<&User> for UserRef {
    fn from(u: &User) -> Self {
        UserRef { id: u.id, display_name: u.display_name.clone(), hue: u.hue, status: u.status }
    }
}

/// A replied-to message beyond the first: id, author, short snippet.
#[derive(Serialize, Deserialize, Clone, Debug, Default, PartialEq)]
pub struct ReplyRef {
    pub id: Id,
    pub author: Option<Id>,
    pub snippet: Option<String>,
}
