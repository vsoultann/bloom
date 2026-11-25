//! Backend abstraction. `MockBackend` gives realistic offline data so the UI
//! can be built and tested; a network backend implements the same trait.

use crate::model::*;
use std::collections::HashMap;

pub enum Event {
    Message(Message),
    Edit { id: Id, content: String, ts: i64 },
    Delete { id: Id },
    Typing { channel_id: Id, user_id: Id },
    /// Presence change.
    Status { user_id: Id, status: Status },
    /// Someone joined/updated their state in a voice channel.
    Voice { channel_id: Id, member: serde_json::Value },
    /// Someone left a voice channel.
    VoiceLeft { channel_id: Id, user_id: Id },
    /// Reaction added/removed by someone (applied to the cached message).
    Reaction { id: Id, emoji: String, user_id: Id, added: bool },
    /// The DM list changed (new conversation / new last message).
    DmsChanged,
    /// Server copy of a message we already show (e.g. our own send, now with attachments).
    Replace(Message),
    /// Someone is calling you in a DM / group.
    Ring { channel_id: Id, caller_id: Id },
    /// Someone declined a call you started.
    RingDeclined { channel_id: Id, user_id: Id },
    /// A profile changed (cosmetics, ...): the full updated user.
    User(User),
}

pub trait Backend: Send {
    fn me(&self) -> Id;
    fn users(&self) -> &HashMap<Id, User>;
    fn servers(&self) -> &[Server];
    fn friends_of(&self) -> &HashMap<Id, Vec<Id>>;
    /// May hit the network for real backends.
    fn messages(&mut self, channel: Id) -> Vec<Message>;
    fn send(&mut self, channel: Id, content: String, reply_to: Option<Id>, now: i64) -> Message;
    fn edit(&mut self, id: Id, content: String, now: i64) -> Option<Event>;
    fn delete(&mut self, id: Id) -> Option<Event>;
    fn react(&mut self, id: Id, emoji: &str);
    /// Local cache access, used by the core to apply events.
    fn insert(&mut self, m: Message);
    fn message_mut(&mut self, id: Id) -> Option<&mut Message>;
    fn remove(&mut self, id: Id);
    fn mark_read(&mut self, server: Option<Id>);
    /// Pull events that happened since the last call.
    fn poll(&mut self, now: i64) -> Vec<Event>;
    /// Server-side mutuals (friends, communities) if the backend provides them.
    fn remote_mutuals(&mut self, _user: Id) -> Option<(Vec<Id>, Vec<Id>)> {
        None
    }
    /// Direct-message conversations, most recent first.
    fn dms(&self) -> Vec<DirectMessage> {
        Vec::new()
    }
    /// Upload a file; returns an attachment token to pass to `send_with`.
    fn upload(&mut self, _path: &str, _name: &str, _mime: &str) -> Result<String, String> {
        Err("uploads need a Root account".into())
    }
    /// Send with attachments (upload tokens). Default: text only.
    fn send_with(&mut self, channel: Id, content: String, reply_to: Option<Id>, now: i64, _attachments: Vec<String>) -> Message {
        self.send(channel, content, reply_to, now)
    }
    /// Favourite GIF URLs.
    fn gif_favorites(&mut self) -> Vec<String> {
        Vec::new()
    }
    fn gif_favorite(&mut self, _url: &str, _add: bool) {}
    /// Custom emoji the user can use: (guid, shortcode, image url).
    fn custom_emoji(&mut self) -> Vec<(String, String, String)> {
        Vec::new()
    }
    /// Apply profile changes (only keys present are changed). Returns the updated self.
    /// Keys: username, description, status, avatar_path, banner_path, presence (online|idle|invisible).
    fn edit_profile(&mut self, changes: &serde_json::Value) -> Result<User, String> {
        let me = self.me();
        let mut u = self.users().get(&me).cloned().ok_or("not signed in")?;
        if let Some(v) = changes.get("username").and_then(|v| v.as_str()) { u.name = v.into(); u.display_name = v.into(); }
        if let Some(v) = changes.get("description").and_then(|v| v.as_str()) { u.bio = v.into(); }
        if let Some(v) = changes.get("status").and_then(|v| v.as_str()) { u.status_text = v.into(); }
        Ok(u)
    }
    /// Social & notifications (Root only). `op` = notifications | notifications_read |
    /// friend_request | friend_respond | friend_remove | dm_create | dm_add | upload_target.
    fn social(&mut self, _op: &str, _args: &serde_json::Value) -> Result<serde_json::Value, String> {
        Err("Needs a Root account".into())
    }
    /// Voice signalling (join/tracks/renegotiate/mute/leave/ice); Root only.
    fn voice(&mut self, _op: &str, _args: &serde_json::Value) -> Result<serde_json::Value, String> {
        Err("Voice channels need a Root account".into())
    }
    /// Extra messages to reply to on the next send (multi-reply). Default: ignored.
    fn set_extra_replies(&mut self, _ids: Vec<Id>) {}
    /// Instant start support (Root only): stepwise refresh, older history, read markers, cache.
    fn refresh(&mut self, _step: u32) -> Result<Option<u32>, String> { Ok(None) }
    fn older_messages(&mut self, _channel: Id) -> Result<Vec<Message>, String> { Ok(vec![]) }
    fn mark_viewed(&mut self, _channel: Id) {}
    fn cached_messages(&mut self, channel: Id) -> Vec<Message> { self.messages(channel) }
    fn save_cache(&self, _dir: &std::path::Path) {}
    /// Tell others we're typing in `channel`.
    fn set_typing(&mut self, _channel: Id) {}
    /// Last non-fatal error (e.g. a failed send), cleared when read.
    fn take_error(&mut self) -> Option<String> {
        None
    }
}

/// Small deterministic PRNG (xorshift64*) so mock data is stable across runs.
struct Rng(u64);
impl Rng {
    fn next(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545F4914F6CDD1D)
    }
    fn below(&mut self, n: u64) -> u64 {
        self.next() % n
    }
}

pub struct MockBackend {
    me: Id,
    users: HashMap<Id, User>,
    servers: Vec<Server>,
    friends: HashMap<Id, Vec<Id>>,
    msgs: HashMap<Id, Vec<Message>>,
    next_id: Id,
    rng: Rng,
    /// Scripted replies waiting to be delivered: (deliver_at, event).
    pending: Vec<(i64, Event)>,
}

const NAMES: &[(&str, &str, &str)] = &[
    ("you", "You", "they/them"),
    ("mira", "Mira", "she/her"),
    ("kenji", "Kenji", "he/him"),
    ("sol", "Sol", "they/them"),
    ("ava.dev", "Ava", "she/her"),
    ("rook", "Rook", "he/him"),
    ("juniper", "Juniper", "she/they"),
    ("theo", "Theo", "he/him"),
    ("nova", "Nova", "she/her"),
    ("pixel", "Pixel", "they/them"),
    ("lars", "Lars", "he/him"),
    ("ivy", "Ivy", "she/her"),
    ("orbit", "Orbit Bot", ""),
];

const BIOS: &[&str] = &[
    "building things that glow ✨",
    "rust evangelist, part-time cat",
    "probably listening to lofi",
    "designer by day, gamer by night",
    "ask me about keyboards",
    "coffee → code",
];

const SERVERS: &[(&str, &[&str])] = &[
    ("Rustaceans", &["general", "help", "showcase", "off-topic"]),
    ("Material Lab", &["announcements", "design-talk", "motion", "feedback"]),
    ("Night Owls", &["general", "music", "late-night-gaming"]),
    ("Pixel Garden", &["general", "art-share", "critique"]),
    ("Study Hall", &["general", "focus-room", "resources"]),
    ("Android Mods", &["news", "root-talk", "magisk", "themes"]),
];

const LINES: &[&str] = &[
    "has anyone tried the new shape morph APIs? they feel so smooth",
    "lol same",
    "I just pushed the fix, can someone review?",
    "that blur effect is unreal on 120hz",
    "gm everyone ☀️",
    "ok this is actually huge",
    "pretty sure it's a lifetime issue, try cloning the Arc",
    "who's up for a match later?",
    "the new theme looks so clean",
    "brb grabbing coffee",
    "can confirm, works on my S25",
    "drop the link pls",
    "honestly material you was the best design decision android made",
    "it compiles!! 🎉",
    "check pins for the guide",
];

impl MockBackend {
    pub fn new(now: i64) -> Self {
        let mut rng = Rng(0x9E3779B97F4A7C15);
        let mut users = HashMap::new();
        for (i, (name, display, pronouns)) in NAMES.iter().enumerate() {
            let id = i as Id + 1;
            let status = match (i, rng.below(5)) {
                (0, _) => Status::Online,
                (_, 0 | 1) => Status::Online,
                (_, 2) => Status::Idle,
                (_, 3) => Status::Dnd,
                _ => Status::Offline,
            };
            let platforms = match rng.below(3) {
                0 => vec![Platform::Mobile],
                1 => vec![Platform::Desktop, Platform::Mobile],
                _ => vec![Platform::Web],
            };
            users.insert(
                id,
                User {
                    id,
                    name: name.to_string(),
                    display_name: display.to_string(),
                    hue: ((i * 47 + 12) % 360) as u16,
                    status,
                    platforms,
                    bio: BIOS[i % BIOS.len()].to_string(),
                    pronouns: pronouns.to_string(),
                    friend_since: None,
                    avatar_url: None,
                    banner_url: None,
                    status_text: String::new(),
                bot: false,
                cosmetic: Default::default(),
                },
            );
        }
        let n = NAMES.len() as Id;

        // Friend graph: symmetric, random-ish but stable.
        let mut friends: HashMap<Id, Vec<Id>> = HashMap::new();
        for a in 1..=n {
            for b in (a + 1)..=n {
                if rng.below(100) < if a == 1 { 60 } else { 35 } {
                    friends.entry(a).or_default().push(b);
                    friends.entry(b).or_default().push(a);
                }
            }
        }
        for f in friends.get(&1).cloned().unwrap_or_default() {
            if let Some(u) = users.get_mut(&f) {
                u.friend_since = Some(now - (rng.below(900) as i64 + 20) * 86_400);
            }
        }

        let mut next_id: Id = 1000;
        let mut servers = Vec::new();
        let mut msgs: HashMap<Id, Vec<Message>> = HashMap::new();
        for (si, (name, chans)) in SERVERS.iter().enumerate() {
            let sid = next_id;
            next_id += 1;
            let mut members: Vec<Id> = vec![1];
            members.extend((2..=n).filter(|_| rng.below(100) < 55));
            let mut channels = Vec::new();
            for (ci, cname) in chans.iter().enumerate() {
                let cid = next_id;
                next_id += 1;
                let kind = if cname.contains("announce") || *cname == "news" {
                    ChannelKind::Announcement
                } else if cname.contains("gaming") || cname.contains("focus") {
                    ChannelKind::Voice
                } else {
                    ChannelKind::Text
                };
                channels.push(Channel {
                    id: cid,
                    server_id: sid,
                    name: cname.to_string(),
                    kind,
                    category: if ci < 2 { "Info".into() } else { "Chat".into() },
                    topic: format!("Welcome to #{cname}"),
                    unread: rng.below(3) == 0,
                    icon_url: None,
                });
                if kind != ChannelKind::Voice {
                    let mut list = Vec::new();
                    let count = 18 + rng.below(20);
                    let mut ts = now - 6 * 3600;
                    for _ in 0..count {
                        ts += 60 + rng.below(900) as i64;
                        let author = members[rng.below(members.len() as u64) as usize];
                        let mut reactions = Vec::new();
                        if rng.below(5) == 0 {
                            let emoji = ["🔥", "😂", "💜", "👀", "✨"][rng.below(5) as usize];
                            let users = members.iter().copied().filter(|_| rng.below(3) == 0).take(6).collect::<Vec<_>>();
                            if !users.is_empty() {
                                reactions.push(Reaction { emoji: emoji.into(), user_ids: users });
                            }
                        }
                        list.push(Message {
                            id: next_id,
                            channel_id: cid,
                            author_id: author,
                            content: LINES[rng.below(LINES.len() as u64) as usize].to_string(),
                            ts,
                            edited_ts: None,
                            reply_to: None,
                            reactions,
                            deleted: false,
                            edit_history: vec![],
                            system: None,
                            attachments: vec![],
                            reply_author: None,
                            reply_snippet: None,
                            more_replies: vec![],
                        });
                        next_id += 1;
                    }
                    msgs.insert(cid, list);
                }
            }
            servers.push(Server {
                id: sid,
                name: name.to_string(),
                hue: ((si * 61 + 200) % 360) as u16,
                shape: (si % 6) as u8,
                member_ids: members,
                channels,
                mentions: [0, 3, 0, 1, 0, 12][si % 6],
                icon_url: None,
                banner_url: None,
            });
        }

        MockBackend { me: 1, users, servers, friends, msgs, next_id, rng, pending: vec![] }
    }

    fn find_mut(&mut self, id: Id) -> Option<&mut Message> {
        self.msgs.values_mut().flat_map(|v| v.iter_mut()).find(|m| m.id == id)
    }

    fn members_of_channel(&self, channel: Id) -> Vec<Id> {
        self.servers
            .iter()
            .find(|s| s.channels.iter().any(|c| c.id == channel))
            .map(|s| s.member_ids.iter().copied().filter(|&u| u != self.me).collect())
            .unwrap_or_default()
    }
}

impl Backend for MockBackend {
    fn me(&self) -> Id {
        self.me
    }
    fn users(&self) -> &HashMap<Id, User> {
        &self.users
    }
    fn servers(&self) -> &[Server] {
        &self.servers
    }
    fn friends_of(&self) -> &HashMap<Id, Vec<Id>> {
        &self.friends
    }
    fn messages(&mut self, channel: Id) -> Vec<Message> {
        self.msgs.get(&channel).cloned().unwrap_or_default()
    }

    fn send(&mut self, channel: Id, content: String, reply_to: Option<Id>, now: i64) -> Message {
        let m = Message {
            id: self.next_id,
            channel_id: channel,
            author_id: self.me,
            content,
            ts: now,
            edited_ts: None,
            reply_to,
            reactions: vec![],
            deleted: false,
            edit_history: vec![],
            system: None,
            attachments: vec![],
            reply_author: None,
            reply_snippet: None,
            more_replies: vec![],
        };
        self.next_id += 1;
        self.msgs.entry(channel).or_default().push(m.clone());

        // Script a lively response: someone types, replies, and occasionally
        // edits or deletes it (so Message Logger has something to show).
        let members = self.members_of_channel(channel);
        if !members.is_empty() {
            let who = members[self.rng.below(members.len() as u64) as usize];
            self.pending.push((now, Event::Typing { channel_id: channel, user_id: who }));
            let id = self.next_id;
            self.next_id += 1;
            let reply = Message {
                id,
                channel_id: channel,
                author_id: who,
                content: LINES[self.rng.below(LINES.len() as u64) as usize].to_string(),
                ts: now + 2,
                edited_ts: None,
                reply_to: Some(m.id),
                reactions: vec![],
                deleted: false,
                edit_history: vec![],
                system: None,
                attachments: vec![],
                reply_author: None,
                reply_snippet: None,
                more_replies: vec![],
            };
            self.pending.push((now + 2, Event::Message(reply)));
            match self.rng.below(4) {
                0 => self.pending.push((now + 6, Event::Edit { id, content: "wait nvm, ignore that 😅".into(), ts: now + 6 })),
                1 => self.pending.push((now + 7, Event::Delete { id })),
                _ => {}
            }
        }
        m
    }

    fn edit(&mut self, id: Id, content: String, now: i64) -> Option<Event> {
        self.find_mut(id)?;
        Some(Event::Edit { id, content, ts: now })
    }

    fn delete(&mut self, id: Id) -> Option<Event> {
        self.find_mut(id)?;
        Some(Event::Delete { id })
    }

    fn insert(&mut self, m: Message) {
        self.msgs.entry(m.channel_id).or_default().push(m);
    }

    fn message_mut(&mut self, id: Id) -> Option<&mut Message> {
        self.find_mut(id)
    }

    fn remove(&mut self, id: Id) {
        for v in self.msgs.values_mut() {
            v.retain(|m| m.id != id);
        }
    }

    fn react(&mut self, id: Id, emoji: &str) {
        let me = self.me;
        if let Some(m) = self.find_mut(id) {
            match m.reactions.iter_mut().find(|r| r.emoji == emoji) {
                Some(r) => {
                    if let Some(p) = r.user_ids.iter().position(|&u| u == me) {
                        r.user_ids.remove(p);
                    } else {
                        r.user_ids.push(me);
                    }
                }
                None => m.reactions.push(Reaction { emoji: emoji.into(), user_ids: vec![me] }),
            }
            m.reactions.retain(|r| !r.user_ids.is_empty());
        }
    }

    fn mark_read(&mut self, server: Option<Id>) {
        for s in self.servers.iter_mut().filter(|s| server.is_none_or(|id| id == s.id)) {
            s.mentions = 0;
            for c in &mut s.channels {
                c.unread = false;
            }
        }
    }

    fn poll(&mut self, now: i64) -> Vec<Event> {
        let (due, later): (Vec<_>, Vec<_>) = std::mem::take(&mut self.pending).into_iter().partition(|(t, _)| *t <= now);
        self.pending = later;
        due.into_iter().map(|(_, e)| e).collect()
    }
}
