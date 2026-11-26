//! Root (rootapp.com) backend over its gRPC-web API.
//!
//! Sign-in goes to the Connect server (`connect.ConnectService/PasswordSignIn`).
//! If Cloudflare Turnstile is required the server answers FAILED_PRECONDITION
//! with a `turnstile-challenge-url` header; the UI shows that page so the user
//! solves it, then sign-in is retried with the token. The result carries a
//! client token and the user's hub server, which serves every other call.

pub mod cosmetics;

/// Profile fetch that runs its network part without holding the core lock.
pub struct UserGetJob {
    client: Client,
    user: (u64, u64),
}

pub struct UserGetDone {
    user: (u64, u64),
    resp: cosmetics::UserGetResponse,
    assets: Vec<(String, String, i64, u32, u32)>,
}

impl UserGetJob {
    /// Network only: User/Get, then the picture/banner addresses (AssetGrpcService/Get).
    pub fn run(self) -> Result<UserGetDone, String> {
        let (h, l) = self.user;
        let resp: cosmetics::UserGetResponse = self.client
            .unary("/root.UserGrpcService/Get", &cosmetics::UserGetRequest { user_id: Some(proto::UserUuid { high64: h, low64: l }), language_code: "en".into() })
            .map_err(|e| e.to_string())?;
        let uris: Vec<String> = [resp.profile_picture_asset_uri.clone(), resp.banner_asset_uri.clone()].into_iter().flatten()
            .map(|v| v.value).filter(|v| v.starts_with("root://asset/")).collect();
        let mut assets = vec![];
        if !uris.is_empty() {
            if let Ok(body) = self.client.raw("/root.AssetGrpcService/Get", &prost::Message::encode_to_vec(&AssetGetReq { uris })) {
                let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
                assets = parse_asset_map(&payload);
            }
        }
        Ok(UserGetDone { user: self.user, resp, assets })
    }
}
pub mod hub;
pub mod messages;
pub mod voice;
pub mod proto;
pub mod wire;

use crate::backend::{Backend, Event};
use crate::grpcweb::{self, Client};
use crate::model::*;
use proto::*;
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

pub const CONNECT_URL: &str = "https://connect.rootapp.com";
/// Fallback API host (from the official app's build config) if sign-in doesn't return one.
pub const API_URL: &str = "https://api.rootapp.com";
pub const CLIENT_VERSION: &str = concat!("bloom-", env!("CARGO_PKG_VERSION"));
pub const USER_AGENT: &str = concat!("Bloom/", env!("CARGO_PKG_VERSION"), " (Android)");

/// Persisted login (in the app's private storage).
#[derive(Serialize, Deserialize, Clone, Default)]
pub struct Session {
    pub client_token: String,
    /// gRPC-web API base (from `web_api_url`).
    #[serde(default)]
    pub api: String,
    /// Live notifications WebSocket (`hub_server_info`, e.g. wss://hub-0.rootapp.com/notifications).
    pub hub: String,
    pub username: String,
    pub device_hi: u64,
    pub device_lo: u64,
}

impl Session {
    pub fn load(dir: &Path) -> Option<Session> {
        let s: Session = serde_json::from_str(&std::fs::read_to_string(dir.join("session.json")).ok()?).ok()?;
        (!s.client_token.is_empty()).then_some(s)
    }
    pub fn save(&self, dir: &Path) {
        let _ = std::fs::create_dir_all(dir);
        let _ = std::fs::write(dir.join("session.json"), serde_json::to_string(self).unwrap_or_default());
    }
    pub fn clear(dir: &Path) {
        let _ = std::fs::remove_file(dir.join("session.json"));
    }
}

/// Stable per-install device id (Root ties sessions to a device).
pub fn device_id(dir: &Path) -> (u64, u64) {
    let p = dir.join("device_id");
    if let Some((h, l)) = std::fs::read_to_string(&p).ok().and_then(|s| {
        let (a, b) = s.trim().split_once(':')?;
        Some((a.parse().ok()?, b.parse().ok()?))
    }) {
        return (h, l);
    }
    let (h, l) = (random_u64(), random_u64());
    let _ = std::fs::create_dir_all(dir);
    let _ = std::fs::write(&p, format!("{h}:{l}"));
    (h, l)
}

static COUNTER: AtomicU64 = AtomicU64::new(0x9E37_79B9_7F4A_7C15);
fn random_u64() -> u64 {
    // Not cryptographic: only for device / idempotency ids.
    let t = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos() as u64).unwrap_or(0);
    let mut x = t ^ COUNTER.fetch_add(0x2545_F491_4F6C_DD1D, Ordering::Relaxed);
    x ^= x >> 33;
    x = x.wrapping_mul(0xff51_afd7_ed55_8ccd);
    x ^= x >> 33;
    x = x.wrapping_mul(0xc4ce_b9fe_1a85_ec53);
    x ^ (x >> 33)
}

/// A Root GUID laid out like the official client's `newGuid`:
/// bytes 0-5 = ms since 2020-01-01, byte 6 = 0x80 | 4 random bits,
/// byte 7 = GUID type, byte 8 = 0x80 | 6 random bits, bytes 9-15 random.
pub fn new_guid(kind: u8) -> (u64, u64) {
    let ms = (std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0) - messages::GUID_EPOCH_MS).max(0) as u64;
    let r1 = random_u64();
    let r2 = random_u64();
    let hi = ((ms & 0xFFFF_FFFF_FFFF) << 16) | ((0x80 | (r1 & 0x0F)) << 8) | kind as u64;
    let lo = ((0x80 | ((r2 >> 58) & 0x3F)) << 56) | (r2 & 0x00FF_FFFF_FFFF_FFFF);
    (hi, lo)
}

/// GUID type 30 = CommandIdempotency.
fn context() -> RootContext {
    let (h, l) = new_guid(30);
    RootContext { command_id: Some(CommandIdempotencyUuid { high64: h, low64: l }) }
}

pub enum SignIn {
    Ok(Session),
    /// User must solve this Turnstile page, then call sign-in again with the token.
    Challenge(String),
}

pub fn sign_in(dir: &Path, username: &str, password: &str, turnstile: &str, device_name: &str, os_version: &str) -> Result<SignIn, String> {
    let (hi, lo) = device_id(dir);
    let c = Client::new(CONNECT_URL);
    let req = PasswordSignInRequest {
        username: username.trim().to_string(),
        password: password.to_string(),
        device: Some(DeviceDescription {
            device_name: device_name.to_string(),
            os: "android".into(),
            client_version: CLIENT_VERSION.into(),
            os_version: os_version.to_string(),
            is_mobile: true,
        }),
        turnstile_token: turnstile.to_string(),
        device_id: Some(DeviceUuid { high64: hi, low64: lo }),
    };
    match c.unary::<_, PasswordSignInResponse>("/connect.ConnectService/PasswordSignIn", &req) {
        Ok(r) => {
            let a = r.auth.ok_or("sign-in returned no session")?;
            if a.client_token.is_empty() {
                return Err("Root didn't return a session. Try again.".into());
            }
            Ok(SignIn::Ok(Session {
                client_token: a.client_token,
                api: normalize_api(&a.web_api_url),
                hub: a.hub_server_info.trim().to_string(),
                username: username.trim().to_string(),
                device_hi: hi,
                device_lo: lo,
            }))
        }
        Err(grpcweb::Error::Status(s)) if s.code == 9 && s.challenge_url.is_some() => Ok(SignIn::Challenge(s.challenge_url.unwrap())),
        Err(grpcweb::Error::Status(s)) if s.code == 16 || s.code == 5 || s.code == 3 || s.code == 7 => {
            Err(match s.message.as_str() {
                "root error 300" => "Root rejected the request. Check your username and password.".into(),
                m if m.is_empty() || m.starts_with("root error") => "Wrong username or password.".into(),
                m => m.to_string(),
            })
        }
        Err(grpcweb::Error::Status(s)) if s.code == 9 => Err("Root needs a security check but didn't send one. Try again.".into()),
        Err(e) => Err(e.to_string()),
    }
}

fn normalize_api(u: &str) -> String {
    let u = u.trim().trim_end_matches('/');
    if u.is_empty() || u.starts_with("ws") {
        API_URL.to_string()
    } else if u.starts_with("http://") || u.starts_with("https://") {
        u.to_string()
    } else {
        format!("https://{u}")
    }
}

/// Maps Root's 128-bit UUIDs to the app's u64 ids (and back).
#[derive(Default)]
struct Ids {
    to_local: HashMap<(u64, u64), Id>,
    to_root: HashMap<Id, (u64, u64)>,
    next: Id,
}

impl Ids {
    fn local(&mut self, hi: u64, lo: u64) -> Id {
        if let Some(id) = self.to_local.get(&(hi, lo)) {
            return *id;
        }
        self.next += 1;
        let id = self.next;
        self.to_local.insert((hi, lo), id);
        self.to_root.insert(id, (hi, lo));
        id
    }
    fn root(&self, id: Id) -> Option<(u64, u64)> {
        self.to_root.get(&id).copied()
    }
}

macro_rules! uuid_of {
    ($o:expr) => {
        $o.as_ref().map(|u| (u.high64, u.low64))
    };
}

fn hue_of(hi: u64, lo: u64) -> u16 {
    ((hi ^ lo.rotate_left(17)) % 360) as u16
}

fn ts(t: &Option<Timestamp>) -> i64 {
    t.as_ref().map(|t| t.seconds).unwrap_or(0)
}

pub struct RootBackend {
    hub: Client,
    me: Id,
    me_user: Option<User>,
    ids: Ids,
    users: HashMap<Id, User>,
    servers: Vec<Server>,
    friends: HashMap<Id, Vec<Id>>,
    msgs: HashMap<Id, Vec<Message>>,
    /// channel id -> community id (local ids)
    channel_community: HashMap<Id, Id>,
    active_channel: Option<Id>,
    last_refresh: i64,
    /// seconds between fallback polls; grows while the network is down
    poll_every: i64,
    pending: Vec<Event>,
    pub last_error: Option<String>,
    /// root://asset/... -> (https url, expires_at unix)
    asset_cache: HashMap<String, (String, i64)>,
    dms: Vec<DirectMessage>,
    /// Live notifications socket (None until started).
    hub_conn: Option<hub::Hub>,
    /// Communities we've attached to (so the hub sends their channel events).
    attached: std::collections::HashSet<Id>,
    typing_sent: HashMap<Id, i64>,
    /// Additional parents for the next send (multi-reply).
    extra_replies: Vec<Id>,
    /// paging info from the last MessageList call
    last_page_raw: usize,
    last_page_oldest: Option<i64>,
    /// DM conversation ids (local) — messages there have no community.
    dm_ids: std::collections::HashSet<Id>,
    /// emoji id -> (https url, shortcode)
    emoji_cache: HashMap<String, (String, String)>,
    /// root://asset uri -> thumbnail url (videos)
    asset_thumbs: HashMap<String, String>,
    /// root://asset uri -> (width, height)
    asset_dims: HashMap<String, (u32, u32)>,
    /// shortcode -> url, for custom emoji typed as plain :name:
    emoji_by_code: HashMap<String, String>,
    /// Custom emoji you can use (Root's ListMine): (id, shortcode, url). Only these go in the panel.
    my_emoji: Vec<(String, String, String)>,
}

/// Everything needed to show the app instantly on the next launch.
#[derive(Serialize, Deserialize, Default)]
pub struct Snapshot {
    me: Id,
    ids: Vec<(u64, u64, Id)>,
    next_id: Id,
    users: Vec<User>,
    servers: Vec<Server>,
    friends: Vec<(Id, Vec<Id>)>,
    dms: Vec<DirectMessage>,
    channel_community: Vec<(Id, Id)>,
    assets: Vec<(String, String, i64)>,
    emoji: Vec<(String, String, String)>,
    #[serde(default)]
    my_emoji: Vec<(String, String, String)>,
    msgs: Vec<(Id, Vec<Message>)>,
}

impl Snapshot {
    pub fn load(dir: &Path) -> Option<Snapshot> {
        serde_json::from_slice(&std::fs::read(dir.join("cache.json")).ok()?).ok()
    }
}

impl RootBackend {
    /// Instant start from the on-disk snapshot (no network). Call `refresh` afterwards.
    pub fn from_snapshot(session: &Session, dump_dir: Option<PathBuf>, snap: Snapshot) -> Self {
        let api = if session.api.is_empty() { API_URL } else { session.api.as_str() };
        let mut hub = Client::new(api);
        hub.token = Some(session.client_token.clone());
        hub.dump_dir = dump_dir;
        let mut ids = Ids::default();
        for (h, l, id) in &snap.ids {
            ids.to_local.insert((*h, *l), *id);
            ids.to_root.insert(*id, (*h, *l));
        }
        ids.next = snap.next_id.max(snap.ids.iter().map(|x| x.2).max().unwrap_or(0));
        let now = crate::now();
        let mut b = RootBackend {
            hub,
            me: snap.me,
            me_user: None,
            ids,
            users: snap.users.into_iter().map(|u| (u.id, u)).collect(),
            servers: snap.servers,
            friends: snap.friends.into_iter().collect(),
            msgs: snap.msgs.into_iter().collect(),
            channel_community: snap.channel_community.into_iter().collect(),
            active_channel: None,
            last_refresh: 0,
            poll_every: 4,
            pending: vec![],
            last_error: None,
            asset_cache: snap.assets.into_iter().filter(|a| a.2 > now + 3600).map(|(k, u, e)| (k, (u, e))).collect(),
            asset_thumbs: HashMap::new(),
            asset_dims: HashMap::new(),
            emoji_by_code: snap.emoji.iter().map(|(_, u, c)| (c.clone(), u.clone())).collect(),
            my_emoji: snap.my_emoji.clone(),
            emoji_cache: snap.emoji.into_iter().map(|(k, u, c)| (k, (u, c))).collect(),
            dm_ids: snap.dms.iter().map(|d| d.id).collect(),
            dms: snap.dms,
            hub_conn: None,
            attached: Default::default(),
            typing_sent: HashMap::new(),
            extra_replies: vec![],
            last_page_raw: 0,
            last_page_oldest: None,
        };
        b.me_user = b.users.get(&b.me).cloned();
        if session.hub.starts_with("ws") {
            b.hub_conn = Some(hub::Hub::start(session.hub.clone(), session.client_token.clone()));
        }
        b
    }

    pub fn snapshot(&self) -> Snapshot {
        // keep the 30 most recently active conversations' last 60 messages
        let mut chans: Vec<(&Id, &Vec<Message>)> = self.msgs.iter().filter(|(_, v)| !v.is_empty()).collect();
        chans.sort_by_key(|(_, v)| std::cmp::Reverse(v.last().map(|m| m.ts).unwrap_or(0)));
        Snapshot {
            me: self.me,
            ids: self.ids.to_root.iter().map(|(id, (h, l))| (*h, *l, *id)).collect(),
            next_id: self.ids.next,
            users: self.users.values().cloned().collect(),
            servers: self.servers.clone(),
            friends: self.friends.iter().map(|(k, v)| (*k, v.clone())).collect(),
            dms: self.dms.clone(),
            channel_community: self.channel_community.iter().map(|(a, b)| (*a, *b)).collect(),
            assets: self.asset_cache.iter().map(|(k, (u, e))| (k.clone(), u.clone(), *e)).collect(),
            emoji: self.emoji_cache.iter().map(|(k, (u, c))| (k.clone(), u.clone(), c.clone())).collect(),
            my_emoji: self.my_emoji.clone(),
            msgs: chans.into_iter().take(30).map(|(id, v)| (*id, v.iter().rev().take(60).rev().cloned().collect())).collect(),
        }
    }

    pub fn save_snapshot(&self, dir: &Path) {
        if let Ok(b) = serde_json::to_vec(&self.snapshot()) {
            let tmp = dir.join("cache.json.tmp");
            if std::fs::write(&tmp, b).is_ok() {
                let _ = std::fs::rename(tmp, dir.join("cache.json"));
            }
        }
    }

    /// One background refresh step (each is a single short network call so the
    /// UI is never blocked for long). Returns the next step, or None when done.
    pub fn refresh(&mut self, step: u32) -> Result<Option<u32>, String> {
        let n = self.servers.len() as u32;
        match step {
            0 => {
                self.load_self().map_err(|e| if e.is_unauthenticated() { "Your Root session expired. Sign in again.".to_string() } else { e.to_string() })?;
                Ok(Some(1))
            }
            1 => {
                self.load_community_list();
                Ok(Some(2))
            }
            s if s >= 2 && s < 2 + n => {
                let sid = self.servers[(s - 2) as usize].id;
                let chans = self.load_channels(sid);
                if !chans.is_empty() {
                    if let Some(srv) = self.servers.iter_mut().find(|x| x.id == sid) {
                        srv.channels = chans;
                    }
                }
                Ok(Some(s + 1))
            }
            s if s == 2 + n => {
                self.load_friends();
                Ok(Some(s + 1))
            }
            s if s == 3 + n => {
                self.load_dms();
                Ok(Some(s + 1))
            }
            s if s == 4 + n => {
                self.refresh_known_users();
                Ok(Some(s + 1))
            }
            _ => {
                // custom emoji from all your servers, so :name: renders everywhere
                let list = self.fetch_custom_emoji();
                if !list.is_empty() {
                    self.emoji_by_code = list.iter().map(|(_, code, url)| (code.clone(), url.clone())).collect();
                    self.my_emoji = list;
                }
                Ok(None)
            }
        }
    }

    /// Loads older history and merges it into the cache. Returns only the new, older messages.
    /// Next page of older history. Only returns empty when Root has nothing older:
    /// pages made entirely of messages we already have (same-second timestamps) or of
    /// deleted messages are skipped by stepping the cursor further back.
    pub fn older_messages(&mut self, channel: Id) -> Result<Vec<Message>, String> {
        let oldest = self.msgs.get(&channel).and_then(|v| v.iter().map(|m| m.ts).min());
        let Some(mut cursor) = oldest else { return Ok(vec![]) };
        let known: std::collections::HashSet<Id> = self.msgs.get(&channel).map(|v| v.iter().map(|m| m.id).collect()).unwrap_or_default();
        let mut fresh: Vec<Message> = vec![];
        for _ in 0..10 {
            let older = self.fetch_messages_before(channel, Some(cursor)).map_err(|e| e.to_string())?;
            fresh = older.into_iter().filter(|m| !known.contains(&m.id)).collect();
            if !fresh.is_empty() || self.last_page_raw == 0 { break }
            // nothing new on this page: move the cursor past it and try again
            let next = self.last_page_oldest.unwrap_or(cursor).min(cursor) - 1;
            if next >= cursor { break }
            cursor = next;
        }
        fresh.sort_by_key(|m| m.ts);
        let entry = self.msgs.entry(channel).or_default();
        let mut merged = fresh.clone();
        merged.extend(entry.drain(..));
        *entry = merged;
        Ok(fresh)
    }

    /// Tells Root you've read a channel/DM up to now.
    pub fn mark_viewed(&mut self, channel: Id) {
        if let Some((container, community)) = self.container(channel) {
            let req = ViewTimeReq { context: Some(context()), container_id: Some(container), community_id: community };
            let _ = self.hub.raw("/root.v2.MessageGrpcService/SetViewTime", &prost::Message::encode_to_vec(&req));
        }
        if let Some(d) = self.dms.iter_mut().find(|d| d.id == channel) {
            d.unread = false;
        }
        for s in &mut self.servers {
            for c in &mut s.channels {
                if c.id == channel {
                    c.unread = false;
                }
            }
        }
    }

    /// Cached messages for a channel (instant), if any.
    pub fn cached_messages(&self, channel: Id) -> Vec<Message> {
        // saved snapshots from older versions may be out of order; always hand back time order
        let mut v = self.msgs.get(&channel).cloned().unwrap_or_default();
        v.sort_by_key(|m| m.ts);
        v.dedup_by_key(|m| m.id);
        v
    }

    pub fn connect(session: &Session, dump_dir: Option<PathBuf>) -> Result<Self, String> {
        let api = if session.api.is_empty() { API_URL } else { session.api.as_str() };
        let mut hub = Client::new(api);
        hub.token = Some(session.client_token.clone());
        hub.dump_dir = dump_dir;
        let mut b = RootBackend {
            hub,
            me: 0,
            me_user: None,
            ids: Ids::default(),
            users: HashMap::new(),
            servers: vec![],
            friends: HashMap::new(),
            msgs: HashMap::new(),
            channel_community: HashMap::new(),
            active_channel: None,
            last_refresh: 0,
            poll_every: 4,
            pending: vec![],
            last_error: None,
            asset_cache: HashMap::new(),
            dms: vec![],
            hub_conn: None,
            attached: Default::default(),
            typing_sent: HashMap::new(),
            extra_replies: vec![],
            last_page_raw: 0,
            last_page_oldest: None,
            dm_ids: Default::default(),
            emoji_cache: HashMap::new(),
            emoji_by_code: HashMap::new(),
            my_emoji: vec![],
            asset_thumbs: HashMap::new(),
            asset_dims: HashMap::new(),
        };
        b.load_self().map_err(|e| {
            if e.is_unauthenticated() { "Your Root session expired. Sign in again.".to_string() } else { e.to_string() }
        })?;
        b.load_communities();
        b.load_friends();
        b.load_dms();
        if session.hub.starts_with("ws") {
            b.hub_conn = Some(hub::Hub::start(session.hub.clone(), session.client_token.clone()));
        }
        Ok(b)
    }

    pub fn load_self(&mut self) -> Result<(), grpcweb::Error> {
        let r: UserSelfResponse = self.hub.unary("/root.UserGrpcService/GetSelf", &UserGetSelfRequest {})?;
        let (hi, lo) = uuid_of!(r.user_id).unwrap_or((0, 0));
        let id = self.ids.local(hi, lo);
        self.me = id;
        let u = User {
            id,
            name: r.username.clone(),
            display_name: r.username.clone(),
            hue: hue_of(hi, lo),
            status: Status::Online,
            platforms: vec![Platform::Mobile],
            bio: { let raw = r.description.clone().map(|d| d.value).unwrap_or_default(); self.render_bio(&raw) },
            pronouns: String::new(),
            friend_since: None,
            avatar_url: None,
            banner_url: None,
            status_text: r.user_defined_status.clone().map(|d| d.value).unwrap_or_default(),
                bot: false,
                cosmetic: Default::default(),
        };
        let pics: Vec<String> = [r.profile_picture_asset_uri.clone(), r.banner_asset_uri.clone()].into_iter().flatten().map(|v| v.value).collect();
        self.resolve_assets(&pics);
        let mut u = u;
        u.avatar_url = r.profile_picture_asset_uri.as_ref().and_then(|v| self.asset_cache.get(&v.value)).map(|x| x.0.clone());
        u.banner_url = r.banner_asset_uri.as_ref().and_then(|v| self.asset_cache.get(&v.value)).map(|x| x.0.clone());
        // GetSelf carries no cosmetics (and since Root 0.9.46 maybe no bio/banner): keep what User/Get gave us
        if let Some(old) = self.users.get(&id) {
            u.cosmetic = old.cosmetic.clone();
            if u.bio.is_empty() { u.bio = old.bio.clone(); }
            if u.banner_url.is_none() { u.banner_url = old.banner_url.clone(); }
        }
        self.users.insert(id, u.clone());
        self.me_user = Some(u);
        Ok(())
    }

    fn load_communities(&mut self) {
        self.load_community_list();
        for i in 0..self.servers.len() {
            let sid = self.servers[i].id;
            self.servers[i].channels = self.load_channels(sid);
        }
    }

    /// Community list only; keeps already-known channel lists so the UI doesn't flicker.
    fn load_community_list(&mut self) {
        let body = match self.hub.raw("/root.CommunityGrpcService/ListMine", &prost::Message::encode_to_vec(&CommunityListMineRequest {})) {
            Ok(b) => b,
            Err(e) => {
                self.last_error = Some(e.to_string());
                return;
            }
        };
        let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
        let members: Vec<CommunityMemberResponse> =
            wire::find_all(&payload, |c: &CommunityMemberResponse| c.community.as_ref().is_some_and(|p| !p.name.is_empty() && p.community_id.is_some()));
        let mut servers = Vec::new();
        for (i, m) in members.iter().enumerate() {
            let p = m.community.as_ref().unwrap();
            let (hi, lo) = uuid_of!(p.community_id).unwrap();
            let sid = self.ids.local(hi, lo);
            if servers.iter().any(|s: &Server| s.id == sid) {
                continue;
            }
            servers.push(Server {
                id: sid,
                name: p.name.clone(),
                hue: hue_of(hi, lo),
                shape: (i % 6) as u8,
                member_ids: vec![self.me],
                channels: vec![],
                mentions: 0,
                icon_url: p.picture_asset_uri.as_ref().map(|v| v.value.clone()),
                banner_url: p.banner_asset_uri.as_ref().map(|v| v.value.clone()),
            });
        }
        // icon_url currently holds the root:// uri; resolve them all in one call
        let uris: Vec<String> = servers.iter().flat_map(|s| [s.icon_url.clone(), s.banner_url.clone()]).flatten().collect();
        self.resolve_assets(&uris);
        for s in &mut servers {
            s.icon_url = s.icon_url.as_ref().and_then(|u| self.asset_cache.get(u)).map(|x| x.0.clone());
            s.banner_url = s.banner_url.as_ref().and_then(|u| self.asset_cache.get(u)).map(|x| x.0.clone());
        }
        for s in &mut servers {
            if let Some(old) = self.servers.iter().find(|o| o.id == s.id) {
                s.channels = old.channels.clone();
            }
        }
        self.servers = servers;
    }

    fn load_friends(&mut self) {
        let Ok(body) = self.hub.raw("/root.FriendshipGroupGrpcService/List", &prost::Message::encode_to_vec(&messages::Empty {})) else { return };
        let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
        let fs: Vec<FriendshipRef> = wire::find_all(&payload, |f: &FriendshipRef| f.friend_user_id.is_some() && f.id.is_some());
        let ids: Vec<Id> = fs.iter().filter_map(|f| f.friend_user_id.as_ref()).map(|u| self.ids.local(u.high64, u.low64)).collect();
        self.ensure_users(ids.clone());
        let me = self.me;
        for f in &ids {
            self.friends.entry(*f).or_default().push(me);
        }
        self.friends.insert(me, ids);
    }

    fn load_dms(&mut self) {
        let Ok(body) = self.hub.raw("/root.DirectMessageGrpcService/List", &prost::Message::encode_to_vec(&messages::Empty {})) else { return };
        let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
        let list: Vec<DmInfo> = wire::find_all(&payload, |d: &DmInfo| d.id.is_some() && !d.members.is_empty());
        let me = self.me;
        let mut people = Vec::new();
        let mut out = Vec::new();
        for d in list {
            let u = d.id.as_ref().unwrap();
            let id = self.ids.local(u.high64, u.low64);
            self.dm_ids.insert(id);
            let others: Vec<Id> = d.members.iter().map(|m| self.ids.local(m.high64, m.low64)).filter(|x| *x != me).collect();
            people.extend(others.iter().copied());
            let last_activity = ts(&d.last_activity_at);
            let (preview, author) = match &d.last_message {
                Some(m) if m.deleted_at.is_none() => {
                    let a = m.user_id.as_ref().map(|u| self.ids.local(u.high64, u.low64));
                    if let Some(a) = a { people.push(a) }
                    let text = if m.message_type == 2 { m.payload.as_ref().map(messages::system_text) } else if m.content.is_empty() { Some("sent an attachment".into()) } else { Some(self.render_content(&m.content, &m.assets).0) };
                    (text, a)
                }
                _ => (None, None),
            };
            out.push(DirectMessage {
                id,
                user_ids: others,
                last_message: preview,
                last_author: author,
                last_activity,
                unread: last_activity > ts(&d.user_last_viewed_at),
            });
        }
        out.sort_by_key(|d| std::cmp::Reverse(d.last_activity));
        self.ensure_users(people);
        for d in &mut out {
            if let Some(t) = &d.last_message {
                if t.contains("⟦u:") {
                    d.last_message = Some(self.fill_mentions(t));
                }
            }
        }
        self.dms = out;
    }

    fn load_channels(&mut self, server: Id) -> Vec<Channel> {
        let Some((hi, lo)) = self.ids.root(server) else { return vec![] };
        let req = ChannelGroupListRequest { community_id: Some(CommunityUuid { high64: hi, low64: lo }), include_channels: true };
        let body = match self.hub.raw("/root.ChannelGroupGrpcService/List", &prost::Message::encode_to_vec(&req)) {
            Ok(b) => b,
            Err(e) => {
                self.last_error = Some(e.to_string());
                return vec![];
            }
        };
        let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
        let groups: Vec<ChannelGroupResponse> =
            wire::find_all(&payload, |g: &ChannelGroupResponse| g.id.is_some() && !g.name.is_empty());
        let chans: Vec<ChannelResponse> = wire::find_all(&payload, |c: &ChannelResponse| c.id.is_some() && !c.name.is_empty() && c.community_id.is_some());
        // channel pictures, resolved in one batch
        let icon_uris: Vec<String> = chans.iter().filter_map(|c| c.icon_asset_uri.as_ref().map(|u| u.value.clone())).filter(|u| !u.is_empty()).collect();
        self.resolve_assets(&icon_uris);
        let group_name: HashMap<(u64, u64), String> =
            groups.iter().filter_map(|g| Some((uuid_of!(g.id)?, g.name.clone()))).collect();
        let group_pos: HashMap<(u64, u64), f32> = groups.iter().filter_map(|g| Some((uuid_of!(g.id)?, g.position))).collect();
        let mut out: Vec<((f32, f32), Channel)> = chans
            .iter()
            .map(|c| {
                let (h, l) = uuid_of!(c.id).unwrap();
                let id = self.ids.local(h, l);
                self.channel_community.insert(id, server);
                let category = uuid_of!(c.channel_group_id).and_then(|g| group_name.get(&g).cloned()).unwrap_or_else(|| "Channels".into());
                let unread = ts(&c.last_activity_at) > ts(&c.user_last_viewed_at);
                // Root's ChannelType: 0 Unspecified, 1 Text, 2 ThreadedText, 4 Voice, 8 App.
                let kind = match c.channel_type {
                    4 => ChannelKind::Voice,
                    8 => ChannelKind::App, // Root Apps run inside Root's own client
                    _ => ChannelKind::Text,
                };
                // ungrouped channels first, then categories in order, then channels in order
                let gpos = uuid_of!(c.channel_group_id).and_then(|g| group_pos.get(&g).copied()).unwrap_or(f32::MIN);
                ((gpos, c.position), Channel {
                    id,
                    server_id: server,
                    name: c.name.clone(),
                    kind,
                    category,
                    topic: c.description.as_ref().map(|d| d.value.clone()).unwrap_or_default(),
                    unread,
                    icon_url: c.icon_asset_uri.as_ref().and_then(|u| self.asset_cache.get(&u.value)).map(|a| a.0.clone()),
                })
            })
            .collect();
        out.sort_by(|a, b| a.0 .0.total_cmp(&b.0 .0).then(a.0 .1.total_cmp(&b.0 .1)));
        out.into_iter().map(|(_, c)| c).collect()
    }

    fn fetch_messages(&mut self, channel: Id) -> Result<Vec<Message>, grpcweb::Error> {
        self.fetch_messages_before(channel, None)
    }

    /// Up to 50 messages older than `before` (unix seconds), or the newest 50.
    fn fetch_messages_before(&mut self, channel: Id, before: Option<i64>) -> Result<Vec<Message>, grpcweb::Error> {
        let Some((ch, cl)) = self.ids.root(channel) else { return Ok(vec![]) };
        let community = self.channel_community.get(&channel).and_then(|s| self.ids.root(*s));
        if community.is_none() && !self.dm_ids.contains(&channel) {
            return Ok(vec![]);
        }
        let req = MessageListRequest {
            container_id: Some(MessageContainerUuid { high64: ch, low64: cl }),
            community_id: community.map(|(h, l)| CommunityUuid { high64: h, low64: l }),
            // Root's MessageDirectionTake: 1 Newer, 2 Older, 3 Both. Take the 50 older than "now".
            message_direction_take: 2,
            date_at: Some(Timestamp { seconds: before.unwrap_or(crate::now() + 60), nanos: 0 }),
            limit: Some(Int32Value { value: 50 }),
        };
        let body = self.hub.raw("/root.v2.MessageGrpcService/List", &prost::Message::encode_to_vec(&req))?;
        let (frames, status) = grpcweb::unframe(&body)?;
        if let Some(s) = status.filter(|s| s.code != 0) {
            return Err(grpcweb::Error::Status(s));
        }
        let payload = frames.into_iter().next().unwrap_or_default();
        // response.5 = container, its repeated field 11 = messages (verified live)
        let raws: Vec<messages::RawMessage> = wire::find_all(&payload, |m: &messages::RawMessage| m.id.is_some() && m.user_id.is_some());
        // remember how much Root returned (incl. deleted) and how far back it reached, for paging
        self.last_page_raw = raws.len();
        self.last_page_oldest = raws.iter().filter_map(|m| m.id.as_ref()).map(|u| messages::guid_time(u.high64)).min();
        let live: Vec<messages::RawMessage> = raws.into_iter().filter(|m| m.deleted_at.is_none()).collect();
        // Resolve everything the messages reference in as few calls as possible.
        let mut uris: Vec<String> = live.iter().flat_map(|m| m.assets.iter().map(|a| a.uri.clone())).collect();
        uris.sort();
        uris.dedup();
        self.resolve_assets(&uris);
        let mut emoji_ids: Vec<String> = live
            .iter()
            .flat_map(|m| messages::tokenize(&m.content).into_iter().filter_map(|t| match t {
                messages::Token::Link { kind: "emoji", target, .. } if !target.starts_with(':') && messages::unicode_emoji(target).is_none() => Some(target.to_string()),
                _ => None,
            }).collect::<Vec<_>>())
            .collect();
        emoji_ids.extend(live.iter().flat_map(|m| Self::emoji_refs(m)));
        emoji_ids.extend(live.iter().flat_map(|m| m.reactions.iter().filter_map(|r| Self::custom_reaction_id(&r.shortcode))));
        self.resolve_emoji(&emoji_ids);
        let mentioned: Vec<Id> = live
            .iter()
            .flat_map(|m| messages::tokenize(&m.content).into_iter().filter_map(|t| match t {
                messages::Token::Link { kind: "user", target, .. } => Some(target.to_string()),
                _ => None,
            }).collect::<Vec<_>>())
            .filter_map(|g| parse_guid(&g))
            .map(|(h, l)| self.ids.local(h, l))
            .collect();

        let mut out: Vec<Message> = Vec::with_capacity(live.len());
        for p in &live {
            let (uh, ul) = uuid_of!(p.user_id).unwrap();
            let author = self.ids.local(uh, ul);
            let (mh, ml) = uuid_of!(p.id).unwrap();
            let (content, attachments) = self.render_content(&p.content, &p.assets);
            let parent = p.parents.first();
            out.push(Message {
                id: self.ids.local(mh, ml),
                channel_id: channel,
                author_id: author,
                content,
                ts: messages::guid_time(mh),
                edited_ts: p.edited_at.as_ref().map(|t| t.seconds).filter(|e| *e > messages::guid_time(mh) + 10),
                reply_to: parent.and_then(|r| r.id.as_ref()).map(|u| self.ids.local(u.high64, u.low64)),
                reactions: self.reactions_of(p),
                deleted: false,
                edit_history: vec![],
                system: (p.message_type == 2).then(|| p.payload.as_ref().map(messages::system_text).unwrap_or_else(|| "did something".into())),
                attachments,
                reply_author: parent.and_then(|r| r.user_id.as_ref()).map(|u| self.ids.local(u.high64, u.low64)),
                reply_snippet: parent.and_then(|r| r.snippet.as_ref()).map(|s| s.value.clone()),
                more_replies: p.parents.iter().skip(1).filter_map(|r| {
                    let id = r.id.as_ref()?;
                    Some(ReplyRef { id: self.ids.local(id.high64, id.low64), author: r.user_id.as_ref().map(|u| self.ids.local(u.high64, u.low64)), snippet: r.snippet.as_ref().map(|s| s.value.clone()) })
                }).collect(),
            });
        }
        out.sort_by_key(|m| m.ts);
        out.dedup_by_key(|m| m.id);
        let mut need: Vec<Id> = out.iter().map(|m| m.author_id).collect();
        need.extend(out.iter().filter_map(|m| m.reply_author));
        need.extend(mentioned);
        self.ensure_users(need);
        // Now that names are known, turn mention placeholders into names.
        for m in &mut out {
            if m.content.contains("⟦u:") {
                m.content = self.fill_mentions(&m.content);
            }
        }
        Ok(out)
    }

    /// Fetches profiles for any authors we haven't seen yet.
    fn ensure_users(&mut self, ids: Vec<Id>) {
        self.fetch_users(ids, false)
    }

    /// Re-fetches everyone we already know, so cached names/pictures/statuses don't go stale.
    fn refresh_known_users(&mut self) {
        let mut ids: Vec<Id> = self.users.keys().copied().filter(|id| *id != self.me).collect();
        ids.sort_unstable();
        for chunk in ids.chunks(100) {
            self.fetch_users(chunk.to_vec(), true);
        }
    }

    fn fetch_users(&mut self, ids: Vec<Id>, force: bool) {
        let missing: Vec<UserUuid> = ids
            .into_iter()
            .filter(|id| force || !self.users.contains_key(id))
            .filter_map(|id| self.ids.root(id))
            .collect::<std::collections::HashSet<_>>()
            .into_iter()
            .map(|(h, l)| UserUuid { high64: h, low64: l })
            .collect();
        if missing.is_empty() {
            return;
        }
        let req = UserGetExtendedUsersByIdRequest { user_ids: missing.clone() };
        let found: Vec<UserResponse> = match self.hub.raw("/root.UserGrpcService/GetExtendedUsersById", &prost::Message::encode_to_vec(&req)) {
            Ok(body) => {
                let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
                wire::find_all(&payload, |u: &UserResponse| u.user_id.is_some() && !u.username.is_empty())
            }
            Err(e) => {
                self.last_error = Some(e.to_string());
                vec![]
            }
        };
        let pics: Vec<String> = found
            .iter()
            .flat_map(|u| [u.profile_picture_asset_uri.clone(), u.banner_asset_uri.clone()])
            .flatten()
            .map(|v| v.value)
            .collect();
        self.resolve_assets(&pics);
        // Bots (author GUID type 9 = App): picture falls back to the app's store icon.
        let mut bot_icons: HashMap<(u64, u64), String> = HashMap::new();
        for u in found.iter().filter(|u| u.user_id.as_ref().is_some_and(|x| x.high64 & 0xFF == 9) && u.profile_picture_asset_uri.as_ref().is_none_or(|p| p.value.is_empty() || !(p.value.starts_with("https://") || p.value.starts_with("root://asset/")))) {
            let id = u.user_id.as_ref().unwrap();
            let req = AppStoreGetReq { id: Some(EmojiUuid { high64: id.high64, low64: id.low64 }) };
            let r = self.hub.raw("/root.AppStoreGrpcService/Get", &prost::Message::encode_to_vec(&req));
            if let Ok(b) = r {
                let p = grpcweb::unframe(&b).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
                if let Ok(app) = <AppInfo as prost::Message>::decode(p.as_slice()) {
                    if !app.icon_asset_uri.is_empty() {
                        bot_icons.insert((id.high64, id.low64), app.icon_asset_uri);
                    }
                }
            }
        }
        let icon_uris: Vec<String> = bot_icons.values().cloned().collect();
        self.resolve_assets(&icon_uris);
        for u in found {
            let (h, l) = uuid_of!(u.user_id).unwrap();
            let id = self.ids.local(h, l);
            let bot_icon = bot_icons.get(&(h, l)).and_then(|uri| self.asset_cache.get(uri)).map(|x| x.0.clone());
            let bio_text = { let raw = u.description.clone().map(|d| d.value).unwrap_or_default(); self.render_bio(&raw) };
            let pic = |v: &Option<StringValue>| {
                v.as_ref().and_then(|v| {
                    if v.value.starts_with("https://") { Some(v.value.clone()) } else { self.asset_cache.get(&v.value).map(|x| x.0.clone()) }
                })
            };
            let name_fx = cosmetics::name_effect(&u.active_visible_cosmetics);
            let user = User {
                id,
                name: u.username.clone(),
                display_name: u.username.clone(),
                hue: hue_of(h, l),
                status: Status::Offline,
                platforms: vec![],
                bio: bio_text,
                pronouns: String::new(),
                friend_since: None,
                avatar_url: pic(&u.profile_picture_asset_uri).or(bot_icon),
                banner_url: pic(&u.banner_asset_uri),
                status_text: u.user_defined_status.clone().map(|d| d.value).unwrap_or_default(),
                bot: h & 0xFF == 9,
                cosmetic: {
                    let mut c = Cosmetic { name: name_fx, ..Default::default() };
                    cosmetics::apply_visible_ids(&mut c, u.active_visible_cosmetics.iter().map(|v| (v.r#type, cosmetics::hex_id(&v.id))));
                    c
                },
            };
            // Since Root 0.9.46 user lists carry no bio/banner (they come from User/Get):
            // keep what we already learned instead of blanking it.
            let user = match self.users.get(&id) {
                Some(old) => User {
                    status: old.status,
                    friend_since: old.friend_since.or(user.friend_since),
                    bio: if user.bio.is_empty() { old.bio.clone() } else { user.bio },
                    banner_url: user.banner_url.or_else(|| old.banner_url.clone()),
                    // lists carry only name font/effect: keep frame, border, background we learned from User/Get
                    cosmetic: Cosmetic { profile_bg: old.cosmetic.profile_bg.clone(), worn: old.cosmetic.worn.clone(), frame: old.cosmetic.frame.clone(), border: old.cosmetic.border.clone(), ..user.cosmetic },
                    ..user
                },
                None => user,
            };
            self.users.insert(id, user);
        }
        // Placeholders so the UI never shows a blank author.
        for u in missing {
            let id = self.ids.local(u.high64, u.low64);
            self.users.entry(id).or_insert_with(|| User {
                id,
                name: "unknown".into(),
                display_name: "Unknown user".into(),
                hue: hue_of(u.high64, u.low64),
                status: Status::Offline,
                platforms: vec![],
                bio: String::new(),
                pronouns: String::new(),
                friend_since: None,
                avatar_url: None,
                banner_url: None,
                status_text: String::new(),
                bot: self.ids.root(id).is_some_and(|(h, _)| h & 0xFF == 9),
                cosmetic: Default::default(),
            });
        }
    }

    /// Mutual servers/friends straight from Root (`GetConnectionBetween`).
    pub fn connection_between(&mut self, user: Id) -> Option<(Vec<Id>, Vec<Id>)> {
        let (h, l) = self.ids.root(user)?;
        let r: UserConnectionResponse =
            self.hub.unary("/root.UserGrpcService/GetConnectionBetween", &UserConnectionBetweenRequest { user_id: Some(UserUuid { high64: h, low64: l }) }).ok()?;
        let friends: Vec<Id> = r.friends.iter().map(|u| self.ids.local(u.high64, u.low64)).collect();
        let communities: Vec<Id> = r.communities.iter().map(|c| self.ids.local(c.high64, c.low64)).collect();
        self.ensure_users(friends.clone());
        Some((friends, communities))
    }

    /// Converts Root markup to Bloom markers and collects attachments.
    fn encode_mentions(&self, src: &str) -> String {
        if !src.contains("⟦@") { return src.to_string() }
        let mut out = String::with_capacity(src.len() + 32);
        let mut rest = src;
        while let Some(i) = rest.find("⟦@") {
            out.push_str(&rest[..i]);
            let after = &rest[i + "⟦@".len()..];
            let Some(end) = after.find('⟧') else { out.push_str(&rest[i..]); rest = ""; break };
            let body = &after[..end];
            if let Some(id) = body.strip_prefix("u:").and_then(|x| x.parse::<u64>().ok()) {
                match (self.ids.root(id), self.users.get(&id)) {
                    (Some((h, l)), Some(u)) => out.push_str(&format!("[@{}](root://user/{})", u.name, dashed_guid(h, l))),
                    _ => out.push_str(&format!("@{}", self.users.get(&id).map(|u| u.name.as_str()).unwrap_or("someone"))),
                }
            } else if let Some(r) = body.strip_prefix("r:") {
                let (target, name) = r.split_once('|').unwrap_or((r, r));
                out.push_str(&format!("[@{name}](root://role/{target})"));
            } else {
                out.push_str(&rest[i..i + "⟦@".len() + end + '⟧'.len_utf8()]);
            }
            rest = &after[end + '⟧'.len_utf8()..];
        }
        out.push_str(rest);
        out
    }

    /// Keeps a DM's last-message preview current (and the list ordered by activity).
    fn bump_dm(&mut self, m: &Message) {
        let Some(d) = self.dms.iter_mut().find(|d| d.id == m.channel_id) else { return };
        if m.ts < d.last_activity { return }
        d.last_message = Some(if m.content.trim().is_empty() && !m.attachments.is_empty() { "sent an attachment".into() } else { m.content.clone() });
        d.last_author = Some(m.author_id);
        d.last_activity = m.ts;
        self.dms.sort_by_key(|d| std::cmp::Reverse(d.last_activity));
    }

    /// Drops emoji names that a broken build learned from URLs ("https" etc.).
    pub fn clean_emoji_names(&mut self) {
        for (_, e) in self.emoji_cache.iter_mut() {
            if e.1.len() < 2 || e.1.eq_ignore_ascii_case("http") || e.1.eq_ignore_ascii_case("https") { e.1.clear() }
        }
        // cached messages rendered by that build carry broken tokens: drop them, they refetch
        for (_, list) in self.msgs.iter_mut() { list.retain(|m| !m.content.contains("⟦e⟦") && !m.content.contains("⟦e:https|") && !m.content.contains(":https:")) }
    }

    /// Bots are GUID type 9 (Root "App" ids); applies to users from any source, incl. the snapshot.
    pub fn mark_bots(&mut self) {
        let ids: Vec<Id> = self.users.keys().copied().collect();
        for id in ids {
            if self.ids.root(id).is_some_and(|(h, _)| h & 0xFF == 9) {
                if let Some(u) = self.users.get_mut(&id) { u.bot = true }
            }
        }
    }

    /// Debug: low byte of a user's high GUID half (Root encodes the id type there).
    pub fn debug_guid_type(&self, id: Id) -> String {
        self.ids.root(id).map(|(h, l)| format!("h{:02x}/{:02x}", h & 0xFF, (h >> 8) & 0xFF) + &format!(" l{:02x}", l >> 56)).unwrap_or_default()
    }

    /// How many people are in a voice channel right now (Root's WebRtc List).
    pub fn voice_count(&mut self, channel: Id) -> Option<usize> {
        let (container, community) = self.container(channel)?;
        let req = VoiceListReq { community_id: community, container_id: Some(container) };
        let body = self.hub.raw("/root.WebRtcGrpcService/List", &prost::Message::encode_to_vec(&req)).ok()?;
        let payload = grpcweb::unframe(&body).ok()?.0.into_iter().next().unwrap_or_default();
        let r = <VoiceListResp as prost::Message>::decode(payload.as_slice()).ok()?;
        Some(r.members.len())
    }

    /// Debug: raw Resolve response for one id
    pub fn debug_resolve_raw(&mut self, id: &str) -> String {
        let Some((h, l)) = parse_guid(id) else { return "bad id".into() };
        let req = EmojiResolveReq { ids: vec![EmojiUuid { high64: h, low64: l }] };
        match self.hub.raw("/root.CommunityEmojiGrpcService/Resolve", &prost::Message::encode_to_vec(&req)) {
            Ok(body) => {
                let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
                let mut out = format!("{} bytes: ", payload.len());
                for b in payload.iter().take(160) { if b.is_ascii_graphic() { out.push(*b as char) } else { out.push_str(&format!("\\x{:02x}", b)) } }
                out
            }
            Err(e) => format!("err {e}"),
        }
    }

    /// Debug: try to resolve one emoji id; returns (code, has_url)
    pub fn debug_resolve(&mut self, id: &str) -> Option<(String, bool)> {
        self.resolve_emoji(&[id.to_string()]);
        self.emoji_cache.get(id).map(|(u, c)| (c.clone(), !u.is_empty()))
    }

    /// Custom emoji ids a message lists alongside its text (root://emoji/ID refs).
    fn emoji_refs(m: &messages::RawMessage) -> Vec<String> {
        m.assets.iter().filter_map(|a| a.uri.strip_prefix("root://emoji/")).filter(|id| !id.starts_with(':')).map(str::to_string).collect()
    }

    /// The custom emoji id inside a reaction code (":name:ID:" or a bare id), if any.
    fn custom_reaction_id(code: &str) -> Option<String> {
        let parts: Vec<&str> = code.trim_matches(':').split(':').collect();
        match parts.as_slice() {
            [_, id] if id.len() >= 20 => Some(id.to_string()),
            [id] if id.len() >= 20 && !id.contains(' ') => Some(id.to_string()),
            _ => None,
        }
    }

    /// Bios use the same markup as messages (emoji, mentions, **bold**, …).
    fn render_bio(&mut self, raw: &str) -> String {
        if !raw.contains("](root://") && !raw.contains(':') { return raw.to_string() }
        let ids: Vec<String> = messages::tokenize(raw).into_iter().filter_map(|t| match t {
            messages::Token::Link { kind: "emoji", target, .. } if !target.starts_with(':') && messages::unicode_emoji(target).is_none() => Some(target.to_string()),
            _ => None,
        }).collect();
        if !ids.is_empty() { self.resolve_emoji(&ids) }
        self.render_content(raw, &[]).0
    }

    fn render_content(&mut self, src: &str, assets: &[messages::AssetRef]) -> (String, Vec<Attachment>) {
        let mut text = String::with_capacity(src.len());
        let mut atts = Vec::new();
        for t in messages::tokenize(src) {
            match t {
                messages::Token::Text(s) => text.push_str(&crate::emoji::replace_shortcodes(s)),
                messages::Token::Link { label, kind: "external", target } => {
                    let idx: usize = target.parse().unwrap_or(usize::MAX);
                    let url = assets.get(idx).and_then(|a| self.asset_cache.get(&a.uri)).map(|(u, _)| u.clone());
                    let kind = messages::kind_for_name(label);
                    if kind == AttachmentKind::Link {
                        // keep the link in the text; the asset is its preview image
                        text.push_str(label);
                        atts.push(Attachment { kind, name: label.to_string(), url, href: Some(label.to_string()), width: 0, height: 0, preview: None });
                    } else {
                        let preview = assets.get(idx).and_then(|a| self.asset_thumbs.get(&a.uri)).cloned();
                        let (width, height) = assets.get(idx).and_then(|a| self.asset_dims.get(&a.uri)).copied().unwrap_or((0, 0));
                        atts.push(Attachment { kind, name: label.to_string(), url, href: None, width, height, preview });
                    }
                }
                messages::Token::Link { label, kind: "emoji", target } => {
                    if let Some(u) = messages::unicode_emoji(target) {
                        text.push_str(&u);
                    } else if let Some((url, code)) = self.emoji_cache.get(target) {
                        text.push_str(&messages::emoji(url, code));
                    } else {
                        text.push_str(if label.is_empty() { ":emoji:" } else { label });
                    }
                }
                messages::Token::Link { label, kind: "user", target } => {
                    if label.is_empty() {
                        // name resolved after profiles are fetched
                        text.push_str(&format!("⟦u:{target}⟧"));
                    } else {
                        text.push_str(&messages::mention(label.trim_start_matches('@')));
                    }
                }
                messages::Token::Link { label, kind: "channel", target } => {
                    let name = if label.is_empty() {
                        parse_guid(target)
                            .and_then(|(h, l)| self.ids.to_local.get(&(h, l)).copied())
                            .and_then(|id| self.servers.iter().flat_map(|s| s.channels.iter()).find(|c| c.id == id))
                            .map(|c| c.name.clone())
                            .unwrap_or_else(|| "channel".into())
                    } else {
                        label.trim_start_matches('#').to_string()
                    };
                    text.push_str(&messages::channel(&name));
                }
                messages::Token::Link { label, kind: "role", .. } => text.push_str(&messages::mention(label.trim_start_matches('@'))),
                messages::Token::Link { label, .. } => text.push_str(label),
            }
        }
        // Media-only posts have no [name](root://external/N) text: show every asset
        // the text didn't reference.
        let referenced: std::collections::HashSet<usize> = messages::tokenize(src)
            .into_iter()
            .filter_map(|t| match t {
                messages::Token::Link { kind: "external", target, .. } => target.parse().ok(),
                _ => None,
            })
            .collect();
        for (i, a) in assets.iter().enumerate() {
            // the list also holds emoji/role/user references; only assets are attachments
            if referenced.contains(&i) || !a.uri.starts_with("root://asset/") {
                continue;
            }
            let url = self.asset_cache.get(&a.uri).map(|(u, _)| u.clone());
            let meta = a.attachment.as_ref();
            let mime = meta.map(|m| m.mime_type.as_str()).unwrap_or("");
            let kind = if mime.starts_with("audio/") { AttachmentKind::Audio }
                else { messages::kind_for_asset_uri(&a.uri).unwrap_or(AttachmentKind::File) };
            let fallback = match kind {
                AttachmentKind::Image => "image",
                AttachmentKind::Video => "video",
                AttachmentKind::Audio => "audio",
                _ => "file",
            };
            let name = meta.map(|m| m.file_name.clone()).filter(|n| !n.is_empty()).unwrap_or_else(|| fallback.to_string());
            let preview = self.asset_thumbs.get(&a.uri).cloned();
            let (width, height) = self.asset_dims.get(&a.uri).copied().unwrap_or((0, 0));
            atts.push(Attachment { kind, name, url, href: None, width, height, preview });
        }
        // plain :shortcode: custom emoji: ones this message references (any server — Root
        // lists them with the message), then the ones from your own servers
        let mut text = text.trim().to_string();
        let mut ref_ids: Vec<&str> = Vec::new();
        for id in assets.iter().filter_map(|a| a.uri.strip_prefix("root://emoji/")).filter(|id| !id.starts_with(':')) {
            if !ref_ids.contains(&id) { ref_ids.push(id) }
        }
        let mut local: HashMap<String, String> = HashMap::new();
        // ids whose name we already know
        for id in &ref_ids {
            if let Some((url, code)) = self.emoji_cache.get(*id) { if !code.is_empty() { local.insert(code.clone(), url.clone()); } }
        }
        // the rest: unknown :codes: in the text, matched to the remaining ids in order
        let mut unknown_codes: Vec<String> = Vec::new();
        for c in shortcode_candidates(&text) {
            if !local.contains_key(&c) && !self.emoji_by_code.contains_key(&c) && crate::emoji::lookup(&c).is_none() && !unknown_codes.contains(&c) { unknown_codes.push(c) }
        }
        let unnamed: Vec<&str> = ref_ids.iter().copied().filter(|id| self.emoji_cache.get(*id).is_some_and(|e| e.1.is_empty())).collect();
        for (code, id) in unknown_codes.iter().zip(unnamed.iter()) {
            if let Some((url, _)) = self.emoji_cache.get(*id).cloned() {
                local.insert(code.clone(), url.clone());
                if let Some(e) = self.emoji_cache.get_mut(*id) { e.1 = code.clone() }
            }
        }
        if text.contains(':') && (!self.emoji_by_code.is_empty() || !local.is_empty()) {
            text = self.replace_custom_shortcodes(&text, &local);
        }
        (text, atts)
    }

    /// `:name:` → inline custom emoji when one of your servers has that emoji.
    fn replace_custom_shortcodes(&self, s: &str, local: &HashMap<String, String>) -> String {
        // leave Bloom's own ⟦…⟧ tokens (emoji, mentions) untouched
        if s.contains('⟦') {
            let mut out = String::with_capacity(s.len());
            let mut rest = s;
            while let Some(a) = rest.find('⟦') {
                out.push_str(&self.replace_custom_shortcodes(&rest[..a], local));
                match rest[a..].find('⟧') {
                    Some(b) => { out.push_str(&rest[a..a + b + '⟧'.len_utf8()]); rest = &rest[a + b + '⟧'.len_utf8()..]; }
                    None => { out.push_str(&rest[a..]); rest = ""; }
                }
            }
            out.push_str(&self.replace_custom_shortcodes(rest, local));
            return out;
        }
        let mut out = String::with_capacity(s.len());
        let mut rest = s;
        while let Some(start) = rest.find(':') {
            out.push_str(&rest[..start]);
            let after = &rest[start + 1..];
            match after.find(':') {
                Some(end) if end > 1 && end <= 48 && after[..end].chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-') && !after[end + 1..].starts_with("//") => {
                    match local.get(&after[..end]).or_else(|| self.emoji_by_code.get(&after[..end])) {
                        Some(url) => {
                            out.push_str(&messages::emoji(url, &after[..end]));
                            rest = &after[end + 1..];
                        }
                        None => {
                            out.push(':');
                            rest = after;
                        }
                    }
                }
                _ => {
                    out.push(':');
                    rest = after;
                }
            }
        }
        out.push_str(rest);
        out
    }

    fn fill_mentions(&self, s: &str) -> String {
        let mut out = String::with_capacity(s.len());
        let mut rest = s;
        while let Some(i) = rest.find("⟦u:") {
            out.push_str(&rest[..i]);
            let after = &rest[i + "⟦u:".len()..];
            let end = after.find('⟧').unwrap_or(after.len());
            let name = parse_guid(&after[..end])
                .and_then(|(h, l)| self.ids.to_local.get(&(h, l)).copied())
                .and_then(|id| self.users.get(&id))
                .map(|u| u.display_name.clone())
                .unwrap_or_else(|| "someone".into());
            out.push_str(&messages::mention(&name));
            rest = after.get(end + '⟧'.len_utf8()..).unwrap_or("");
        }
        out.push_str(rest);
        out
    }

    /// Resolves root://asset URIs to signed https URLs (cached until they expire).
    fn resolve_assets(&mut self, uris: &[String]) {
        let now = crate::now();
        let need: Vec<String> = uris
            .iter()
            .filter(|u| u.starts_with("root://asset/"))
            .filter(|u| self.asset_cache.get(*u).is_none_or(|(_, exp)| *exp < now + 300))
            .cloned()
            .collect();
        for chunk in need.chunks(50) {
            let req = AssetGetReq { uris: chunk.to_vec() };
            let Ok(body) = self.hub.raw("/root.AssetGrpcService/Get", &prost::Message::encode_to_vec(&req)) else { continue };
            let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
            for (uri, url, exp, w, h) in parse_asset_map(&payload) {
                if w > 0 && h > 0 {
                    self.asset_dims.insert(uri.clone(), (w, h));
                }
                // videos come back as "stream\nthumbnail"
                let (main, thumb) = url.split_once('\n').map(|(a, b)| (a.to_string(), Some(b.to_string()))).unwrap_or((url, None));
                if let Some(t) = thumb {
                    self.asset_thumbs.insert(uri.clone(), t);
                }
                self.asset_cache.insert(uri, (main, exp));
            }
        }
    }

    /// Resolves custom emoji ids to image URLs.
    fn resolve_emoji(&mut self, ids: &[String]) {
        let mut need: Vec<String> = ids.iter().filter(|i| !self.emoji_cache.contains_key(*i)).cloned().collect();
        need.sort();
        need.dedup();
        let guids: Vec<EmojiUuid> = need.iter().filter_map(|g| parse_guid(g)).map(|(h, l)| EmojiUuid { high64: h, low64: l }).collect();
        if guids.is_empty() {
            return;
        }
        let req = EmojiResolveReq { ids: guids };
        let Ok(body) = self.hub.raw("/root.CommunityEmojiGrpcService/Resolve", &prost::Message::encode_to_vec(&req)) else { return };
        let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
        // Resolve answers with a map: emoji id (long form) → image info (no names)
        for (key, url, _, _, _) in parse_asset_map(&payload) {
            let Some((h, l)) = parse_guid(&key) else { continue };
            let id = guid_text(h, l);
            let code = self.emoji_cache.get(&id).map(|e| e.1.clone()).unwrap_or_default();
            self.emoji_cache.insert(id, (url, code));
        }
    }

    fn voice_member(&mut self, m: &voice::DevicePacket) -> voice::VoiceMember {
        let user = m.user_id.as_ref().map(|u| self.ids.local(u.high64, u.low64)).unwrap_or(0);
        let t = |v: &Option<StringValue>| v.as_ref().map(|s| s.value.clone()).filter(|s| !s.is_empty());
        voice::VoiceMember {
            user_id: user,
            muted: m.is_muted || m.is_admin_muted,
            deafened: m.is_deafened,
            audio_track: t(&m.audio_track_id),
            video_track: if m.is_video_paused { None } else { t(&m.video_track_id) },
            screen_track: if m.is_screen_paused { None } else { t(&m.screen_track_id) },
            screen_audio_track: t(&m.screen_audio_track_id),
        }
    }

    /// Unary call whose response we don't need; Root error codes become readable text.
    fn call_unit<M: prost::Message>(&self, path: &str, req: &M) -> Result<(), String> {
        self.hub.raw(path, &req.encode_to_vec()).map(|_| ()).map_err(|e| match e {
            grpcweb::Error::Status(s) if s.message.contains("300") => "Root rejected that value (it may be taken or invalid)".into(),
            other => other.to_string(),
        })
    }

    /// Converts one live packet into app events.
    fn packet_to_events(&mut self, p: hub::Packet) -> Vec<Event> {
        use prost::Message as _;
        let env = PacketEnvelope::decode(p.body.as_slice()).unwrap_or_default();
        let container = env.container_id.as_ref().map(|c| self.ids.local(c.high64, c.low64));
        match p.kind {
            170 => {
                let Ok(raw) = messages::RawMessage::decode(p.body.as_slice()) else { return vec![] };
                let Some(ch) = container else { return vec![] };
                let deleted = raw.deleted_at.is_some();
                let mut m = self.convert_one(&raw, ch);
                let known = self.message_mut(m.id).is_some();
                let mut evs = Vec::new();
                if self.dm_ids.contains(&ch) {
                    if let Some(d) = self.dms.iter_mut().find(|d| d.id == ch) {
                        d.last_message = Some(m.system.clone().unwrap_or_else(|| if m.content.is_empty() { "sent an attachment".into() } else { m.content.clone() }));
                        d.last_author = Some(m.author_id);
                        d.last_activity = m.ts;
                        d.unread = self.active_channel != Some(ch) && m.author_id != self.me;
                    }
                    self.dms.sort_by_key(|d| std::cmp::Reverse(d.last_activity));
                    evs.push(Event::DmsChanged);
                } else if self.active_channel != Some(ch) {
                    // mark the channel unread in the sidebar
                    for s in &mut self.servers {
                        for c in &mut s.channels {
                            if c.id == ch && m.author_id != self.me {
                                c.unread = true;
                            }
                        }
                    }
                }
                // Root can deliver a new message more than once; only real edits count as edits.
                let created = raw.id.as_ref().map(|u| messages::guid_time(u.high64)).unwrap_or(0);
                let is_edit = matches!(env.packet_type, 5806 | 209) || raw.edited_at.as_ref().is_some_and(|t| t.seconds > created + 10);
                if deleted {
                    evs.push(Event::Delete { id: m.id });
                } else if known && (!is_edit || self.message_mut(m.id).is_some_and(|cur| cur.content == m.content)) {
                    // same text = not an edit (re-delivery, pin, attachment processing finished);
                    // Root itself only tags "edited" when the text was really edited
                    // our own send (or a re-delivery): swap in the server copy, which has
                    // the resolved attachments; content/edit state are unchanged
                    evs.push(Event::Replace(m));
                } else if known {
                    evs.push(Event::Edit { id: m.id, content: std::mem::take(&mut m.content), ts: m.edited_ts.unwrap_or(m.ts) });
                } else if self.msgs.contains_key(&ch) {
                    evs.push(Event::Message(m));
                }
                evs
            }
            171 => {
                let Ok(d) = DeletedPacket::decode(p.body.as_slice()) else { return vec![] };
                d.id.map(|u| vec![Event::Delete { id: self.ids.local(u.high64, u.low64) }]).unwrap_or_default()
            }
            173 => {
                let Ok(r) = ReactionPacket::decode(p.body.as_slice()) else { return vec![] };
                let (Some(mid), Some(uid)) = (r.message_id, r.user_id) else { return vec![] };
                let added = !matches!(env.packet_type, 205 | 5802);
                let emoji = self.reaction_emoji(&r.shortcode);
                vec![Event::Reaction { id: self.ids.local(mid.high64, mid.low64), emoji, user_id: self.ids.local(uid.high64, uid.low64), added }]
            }
            174 => {
                let Ok(t) = TypingPacket::decode(p.body.as_slice()) else { return vec![] };
                match (container, t.user_id, t.is_typing) {
                    (Some(ch), Some(u), true) => {
                        let user = self.ids.local(u.high64, u.low64);
                        if user == self.me {
                            return vec![];
                        }
                        self.ensure_users(vec![user]);
                        vec![Event::Typing { channel_id: ch, user_id: user }]
                    }
                    _ => vec![],
                }
            }
            // someone changed their profile (name, picture, bio, banner, status): update live
            190 => {
                let Ok(pk) = cosmetics::SetProfilePacket::decode(p.body.as_slice()) else { return vec![] };
                let Some(u) = pk.user_id else { return vec![] };
                let id = self.ids.local(u.high64, u.low64);
                if !self.users.contains_key(&id) { return vec![] }
                let uris: Vec<String> = [Some(pk.profile_picture_asset_uri.clone()).filter(|s| !s.is_empty()), pk.banner_asset_uri.as_ref().map(|v| v.value.clone())]
                    .into_iter().flatten().filter(|s| !s.starts_with("https://") && !s.is_empty()).collect();
                self.resolve_assets(&uris);
                let resolve = |this: &Self, v: &str| if v.starts_with("https://") { Some(v.to_string()) } else { this.asset_cache.get(v).map(|x| x.0.clone()) };
                let avatar = (!pk.profile_picture_asset_uri.is_empty()).then(|| resolve(self, &pk.profile_picture_asset_uri)).flatten();
                let banner = pk.banner_asset_uri.as_ref().filter(|v| !v.value.is_empty()).and_then(|v| resolve(self, &v.value));
                let bio = pk.description.as_ref().map(|d| self.render_bio(&d.value));
                let me = self.me;
                let user = self.users.get_mut(&id).unwrap();
                if !pk.username.is_empty() { user.name = pk.username.clone(); user.display_name = pk.username.clone(); }
                if avatar.is_some() || pk.profile_picture_asset_uri.is_empty() { user.avatar_url = avatar; }
                user.banner_url = banner;
                if let Some(b) = bio { user.bio = b; }
                user.status_text = pk.user_defined_status.map(|d| d.value).unwrap_or_default();
                let out = user.clone();
                if id == me { self.me_user = Some(out.clone()); }
                vec![Event::User(out)]
            }
            // Root 0.9.46 cosmetics: 261 = profile background, 262 = visible cosmetics (name effect)
            261 | 262 => {
                let (user, name, bg) = if p.kind == 262 {
                    let Ok(pk) = cosmetics::SetActiveVisiblePacket::decode(p.body.as_slice()) else { return vec![] };
                    let ids: Vec<(i32, Option<String>)> = pk.active_visible_cosmetics.iter().map(|v| (v.r#type, cosmetics::hex_id(&v.id))).collect();
                    let uid = pk.user_id.as_ref().map(|u| self.ids.local(u.high64, u.low64));
                    if let Some(user) = uid.and_then(|id| self.users.get_mut(&id)) { cosmetics::apply_visible_ids(&mut user.cosmetic, ids.into_iter()); }
                    (pk.user_id, Some(cosmetics::name_effect(&pk.active_visible_cosmetics)), None)
                } else {
                    let Ok(pk) = cosmetics::SetProfileBackgroundPacket::decode(p.body.as_slice()) else { return vec![] };
                    (pk.user_id, None, Some(pk.profile_background.as_ref().and_then(cosmetics::fill)))
                };
                let Some(u) = user else { return vec![] };
                let id = self.ids.local(u.high64, u.low64);
                match self.users.get_mut(&id) {
                    Some(user) => {
                        if let Some(n) = name { user.cosmetic.name = n; }
                        if let Some(b) = bg { user.cosmetic.profile_bg = b; }
                        vec![Event::User(user.clone())]
                    }
                    None => vec![],
                }
            }
            191 => {
                let Ok(st) = StatusPacket::decode(p.body.as_slice()) else { return vec![] };
                let Some(u) = st.user_id else { return vec![] };
                let id = self.ids.local(u.high64, u.low64);
                let status = match st.online_status {
                    16 => Status::Online,
                    4 => Status::Idle,
                    _ => Status::Offline,
                };
                match self.users.get_mut(&id) {
                    Some(user) if user.status != status => {
                        user.status = status;
                        vec![Event::Status { user_id: id, status }]
                    }
                    _ => vec![],
                }
            }
            200 | 202 | 203 => {
                let Ok(d) = voice::DevicePacket::decode(p.body.as_slice()) else { return vec![] };
                let Some(ch) = d.container_id.as_ref().map(|c| self.ids.local(c.high64, c.low64)) else { return vec![] };
                let m = self.voice_member(&d);
                self.ensure_users(vec![m.user_id]);
                vec![Event::Voice { channel_id: ch, member: serde_json::to_value(m).unwrap_or_default() }]
            }
            201 => {
                let Ok(d) = voice::DevicePacket::decode(p.body.as_slice()) else { return vec![] };
                match (d.container_id, d.user_id) {
                    (Some(c), Some(u)) => vec![Event::VoiceLeft { channel_id: self.ids.local(c.high64, c.low64), user_id: self.ids.local(u.high64, u.low64) }],
                    _ => vec![],
                }
            }
            120 | 121 | 122 | 125 => {
                self.load_dms();
                vec![Event::DmsChanged]
            }
            // incoming DM / group call
            123 => {
                let Ok(r) = RingPacket::decode(p.body.as_slice()) else { return vec![] };
                let (Some(dm), Some(caller)) = (r.id, r.caller_user_id) else { return vec![] };
                let caller_id = self.ids.local(caller.high64, caller.low64);
                if caller_id == self.me { return vec![] }
                self.ensure_users(vec![caller_id]);
                vec![Event::Ring { channel_id: self.ids.local(dm.high64, dm.low64), caller_id }]
            }
            124 => {
                let Ok(r) = RingPacket::decode(p.body.as_slice()) else { return vec![] };
                let (Some(dm), Some(u)) = (r.id, r.caller_user_id) else { return vec![] };
                vec![Event::RingDeclined { channel_id: self.ids.local(dm.high64, dm.low64), user_id: self.ids.local(u.high64, u.low64) }]
            }
            _ => vec![],
        }
    }

    /// One wire message → app message (resolving its assets, emoji and authors).
    fn convert_one(&mut self, p: &messages::RawMessage, channel: Id) -> Message {
        let uris: Vec<String> = p.assets.iter().map(|a| a.uri.clone()).collect();
        self.resolve_assets(&uris);
        let emoji_ids: Vec<String> = messages::tokenize(&p.content)
            .into_iter()
            .filter_map(|t| match t {
                messages::Token::Link { kind: "emoji", target, .. } if !target.starts_with(':') && messages::unicode_emoji(target).is_none() => Some(target.to_string()),
                _ => None,
            })
            .chain(Self::emoji_refs(p))
            .collect();
        self.resolve_emoji(&emoji_ids);
        let (uh, ul) = uuid_of!(p.user_id).unwrap_or((0, 0));
        let author = self.ids.local(uh, ul);
        let (mh, ml) = uuid_of!(p.id).unwrap_or((0, 0));
        let (content, attachments) = self.render_content(&p.content, &p.assets);
        let parent = p.parents.first();
        let mut m = Message {
            id: self.ids.local(mh, ml),
            channel_id: channel,
            author_id: author,
            content,
            ts: messages::guid_time(mh),
            edited_ts: p.edited_at.as_ref().map(|t| t.seconds).filter(|e| *e > messages::guid_time(mh) + 10),
            reply_to: parent.and_then(|r| r.id.as_ref()).map(|u| self.ids.local(u.high64, u.low64)),
            reactions: vec![],
            deleted: false,
            edit_history: vec![],
            system: (p.message_type == 2).then(|| p.payload.as_ref().map(messages::system_text).unwrap_or_else(|| "did something".into())),
            attachments,
            reply_author: parent.and_then(|r| r.user_id.as_ref()).map(|u| self.ids.local(u.high64, u.low64)),
            reply_snippet: parent.and_then(|r| r.snippet.as_ref()).map(|s| s.value.clone()),
                more_replies: p.parents.iter().skip(1).filter_map(|r| {
                    let id = r.id.as_ref()?;
                    Some(ReplyRef { id: self.ids.local(id.high64, id.low64), author: r.user_id.as_ref().map(|u| self.ids.local(u.high64, u.low64)), snippet: r.snippet.as_ref().map(|s| s.value.clone()) })
                }).collect(),
        };
        let mut need = vec![author];
        need.extend(m.reply_author);
        self.ensure_users(need);
        if m.content.contains("⟦u:") {
            m.content = self.fill_mentions(&m.content);
        }
        m
    }

    /// Group a message's reactions by emoji (custom ones stay as their id).
    fn reactions_of(&mut self, p: &messages::RawMessage) -> Vec<Reaction> {
        let mut out: Vec<Reaction> = vec![];
        for r in &p.reactions {
            let Some(u) = r.user_id.as_ref() else { continue };
            let uid = self.ids.local(u.high64, u.low64);
            let emoji = self.reaction_emoji(&r.shortcode);
            match out.iter_mut().find(|x| x.emoji == emoji) {
                Some(x) => if !x.user_ids.contains(&uid) { x.user_ids.push(uid) },
                None => out.push(Reaction { emoji, user_ids: vec![uid] }),
            }
        }
        out
    }

    /// Root reaction shortcode (":fire:", "1f525", or a custom emoji id) → what the UI shows.
    fn reaction_emoji(&mut self, code: &str) -> String {
        // custom emoji reactions → "custom:ID:name:URL" so the UI can draw the picture
        if let Some(id) = Self::custom_reaction_id(code) {
            if !self.emoji_cache.contains_key(&id) { self.resolve_emoji(&[id.clone()]) }
            let name = code.trim_matches(':').split(':').next().unwrap_or("").to_string();
            return match self.emoji_cache.get(&id) {
                Some((url, code_name)) => format!("custom:{id}:{}:{url}", if name.is_empty() || name == id { code_name.clone() } else { name }),
                None => id,
            };
        }
        let colon = if code.starts_with(':') { code.to_string() } else { format!(":{code}:") };
        messages::unicode_emoji(code).or_else(|| messages::unicode_emoji(&colon)).unwrap_or_else(|| code.to_string())
    }

    fn container(&self, channel: Id) -> Option<(MessageContainerUuid, Option<CommunityUuid>)> {
        let (h, l) = self.ids.root(channel)?;
        let comm = self.channel_community.get(&channel).and_then(|s| self.ids.root(*s)).map(|(h, l)| CommunityUuid { high64: h, low64: l });
        Some((MessageContainerUuid { high64: h, low64: l }, comm))
    }
}

impl Backend for RootBackend {
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
        self.active_channel = Some(channel);
        if let Some(server) = self.channel_community.get(&channel).copied() {
            if self.attached.insert(server) {
                if let Some((h, l)) = self.ids.root(server) {
                    let req = AttachReq { community_id: Some(CommunityUuid { high64: h, low64: l }) };
                    if let Err(e) = self.hub.raw("/root.CommunityGrpcService/Attach", &prost::Message::encode_to_vec(&req)) {
                        self.attached.remove(&server);
                        self.last_error = Some(format!("Live updates for this server failed: {e}"));
                    }
                }
            }
        }
        match self.fetch_messages(channel) {
            Ok(list) => {
                if let Some(newest) = list.iter().filter(|m| !m.deleted && m.system.is_none()).max_by_key(|m| m.ts) {
                    self.bump_dm(newest);
                }
                self.msgs.insert(channel, list.clone());
                self.last_refresh = crate::now();
                list
            }
            Err(e) => {
                self.last_error = Some(e.to_string());
                self.msgs.get(&channel).cloned().unwrap_or_default()
            }
        }
    }

    fn send(&mut self, channel: Id, content: String, reply_to: Option<Id>, now: i64) -> Message {
        self.send_with(channel, content, reply_to, now, vec![])
    }

    fn send_with(&mut self, channel: Id, content: String, reply_to: Option<Id>, now: i64, attachments: Vec<String>) -> Message {
        // Media posts from Root's own client carry the files only in attachment_token_uris
        // (no text links). Entries may be "token" or "token\tname".
        let attachments: Vec<String> = attachments.iter().map(|a| a.split('\t').next().unwrap_or("").to_string()).filter(|t| !t.is_empty()).collect();
        let had_files = !attachments.is_empty();
        // Mentions picked in the composer: ⟦@u:LOCALID⟧ / ⟦@r:TARGET|Name⟧ → Root links
        let content = self.encode_mentions(&content);
        // Standard emoji → Root's [:name:](root://emoji/:name:) links.
        let content = crate::emoji::encode_for_root(&content);
        let content_has_links = content.contains("](root://");
        // Only uploaded files go in attachment_token_uris; Root derives emoji/mention
        // references from the text itself (listing them there fails with error 300).
        let mut m = Message {
            id: 0,
            channel_id: channel,
            author_id: self.me,
            content: content.clone(),
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
        if let Some((container, community)) = self.container(channel) {
            let mut all: Vec<Id> = reply_to.into_iter().collect();
            all.extend(std::mem::take(&mut self.extra_replies));
            all.dedup();
            m.more_replies = all.iter().filter(|r| Some(**r) != reply_to).map(|r| {
                let parent = self.message_mut(*r).map(|x| (x.author_id, x.content.chars().take(80).collect::<String>()));
                ReplyRef { id: *r, author: parent.as_ref().map(|x| x.0), snippet: parent.map(|x| x.1) }
            }).collect();
            if m.reply_to.is_none() { m.reply_to = all.first().copied() }
            let parents: Vec<MessageUuid> = all.iter().filter_map(|r| self.ids.root(*r)).map(|(h, l)| MessageUuid { high64: h, low64: l }).collect();
            let req = MessageCreateRequest {
                context: Some(context()),
                container_id: Some(container),
                community_id: community,
                content: (!content.trim().is_empty()).then(|| StringValue { value: content.clone() }),
                parent_message_ids: parents,
                needs_parent_message_notification: !all.is_empty(),
                attachment_token_uris: attachments.clone(),
            };
            match self.hub.unary::<_, MessageCreateResponse>("/root.v2.MessageGrpcService/Create", &req) {
                Ok(r) => {
                    if let Some(u) = r.id {
                        m.id = self.ids.local(u.high64, u.low64);
                    }
                }
                Err(e) => self.last_error = Some(format!("Couldn't send: {e}")),
            }
        }
        if m.id == 0 {
            m.id = self.ids.local(random_u64(), random_u64());
        } else if had_files || content_has_links {
            // fetch the server's copy so attachments / emoji render right away
            for _ in 0..3 {
                if let Ok(fresh) = self.fetch_messages(channel) {
                    if let Some(server) = fresh.iter().find(|x| x.id == m.id) {
                        m = server.clone();
                        break;
                    }
                }
                std::thread::sleep(std::time::Duration::from_millis(400));
            }
        }
        self.msgs.entry(channel).or_default().push(m.clone());
        m
    }

    fn edit(&mut self, id: Id, content: String, now: i64) -> Option<Event> {
        let channel = self.msgs.iter().find(|(_, v)| v.iter().any(|m| m.id == id)).map(|(c, _)| *c)?;
        let (container, community) = self.container(channel)?;
        let (h, l) = self.ids.root(id)?;
        let req = MessageEditRequest {
            context: Some(context()),
            container_id: Some(container),
            community_id: community,
            id: Some(MessageUuid { high64: h, low64: l }),
            content: content.clone(),
            ..Default::default()
        };
        match self.hub.unary::<_, MessageEditResponseOrEmpty>("/root.v2.MessageGrpcService/Edit", &req) {
            Ok(_) => Some(Event::Edit { id, content, ts: now }),
            Err(e) => {
                self.last_error = Some(format!("Couldn't edit: {e}"));
                None
            }
        }
    }

    fn delete(&mut self, id: Id) -> Option<Event> {
        let channel = self.msgs.iter().find(|(_, v)| v.iter().any(|m| m.id == id)).map(|(c, _)| *c)?;
        let (container, community) = self.container(channel)?;
        let (h, l) = self.ids.root(id)?;
        let req = MessageDeleteRequest { context: Some(context()), container_id: Some(container), community_id: community, id: Some(MessageUuid { high64: h, low64: l }) };
        match self.hub.unary::<_, MessageEditResponseOrEmpty>("/root.v2.MessageGrpcService/Delete", &req) {
            Ok(_) => Some(Event::Delete { id }),
            Err(e) => {
                self.last_error = Some(format!("Couldn't delete: {e}"));
                None
            }
        }
    }

    fn react(&mut self, id: Id, emoji: &str) {
        let me = self.me;
        let Some(channel) = self.msgs.iter().find(|(_, v)| v.iter().any(|m| m.id == id)).map(|(c, _)| *c) else { return };
        let Some((container, community)) = self.container(channel) else { return };
        let Some((h, l)) = self.ids.root(id) else { return };
        let mine = self.message_mut(id).is_some_and(|m| m.reactions.iter().any(|r| r.emoji == emoji && r.user_ids.contains(&me)));
        // Root identifies unicode emoji by code points ("1f525"), custom ones by their id
        // Root reaction codes: ":heart:" for standard emoji (verified on real messages), the id for custom ones
        let code = if let Some(rest) = emoji.strip_prefix("custom:") {
            let mut it = rest.splitn(3, ':');
            let id = it.next().unwrap_or("");
            let name = it.next().unwrap_or("");
            format!(":{name}:{id}:")
        } else if emoji.is_ascii() && emoji.len() >= 20 && !emoji.contains(':') {
            // custom emoji: Root's code is ":name:ID:" (seen on real reactions)
            let name = self.my_emoji.iter().chain(std::iter::empty()).find(|e| e.0 == emoji).map(|e| e.1.clone())
                .or_else(|| self.emoji_cache.get(emoji).map(|e| e.1.clone()));
            match name { Some(n) => format!(":{n}:{emoji}:"), None => emoji.to_string() }
        } else if emoji.is_ascii() { emoji.to_string() } else { crate::emoji::root_name(emoji).map(|n| format!(":{n}:")).unwrap_or_else(|| emoji.to_string()) };
        let path = if mine { "/root.v2.MessageGrpcService/ReactionDelete" } else { "/root.v2.MessageGrpcService/ReactionCreate" };
        let req = MessageReactionCreateRequest {
            context: Some(context()),
            container_id: Some(container),
            community_id: community,
            message_id: Some(MessageUuid { high64: h, low64: l }),
            shortcode: code,
        };
        if let Err(e) = self.hub.unary::<_, MessageEditResponseOrEmpty>(path, &req) {
            self.last_error = Some(format!("Couldn't react: {e}"));
            return;
        }
        if let Some(m) = self.message_mut(id) {
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

    fn insert(&mut self, m: Message) {
        self.bump_dm(&m);
        // keep each channel in time order (live system events can carry older timestamps)
        let list = self.msgs.entry(m.channel_id).or_default();
        if list.iter().any(|x| x.id == m.id) { return }
        let at = list.partition_point(|x| x.ts <= m.ts);
        list.insert(at, m);
    }
    fn dms(&self) -> Vec<DirectMessage> {
        self.dms.clone()
    }
    fn remote_mutuals(&mut self, user: Id) -> Option<(Vec<Id>, Vec<Id>)> {
        self.connection_between(user)
    }
    fn user_get_prepare(&self, user: Id) -> Option<UserGetJob> {
        Some(UserGetJob { client: self.hub.clone(), user: self.ids.root(user)? })
    }

    fn user_get_apply(&mut self, done: UserGetDone) -> Result<serde_json::Value, String> {
        use serde_json::json;
        let (h, l) = done.user;
        let r = done.resp;
        for (uri, url, exp, w, h2) in done.assets {
            if w > 0 && h2 > 0 { self.asset_dims.insert(uri.clone(), (w, h2)); }
            let main = url.split_once('\n').map(|(a, _)| a.to_string()).unwrap_or(url);
            self.asset_cache.insert(uri, (main, exp));
        }
        let pic = |this: &Self, v: &Option<StringValue>| v.as_ref().filter(|v| !v.value.is_empty()).and_then(|v| {
            if v.value.starts_with("https://") { Some(v.value.clone()) } else { this.asset_cache.get(&v.value).map(|x| x.0.clone()) }
        });
        let avatar = pic(self, &r.profile_picture_asset_uri);
        let banner = pic(self, &r.banner_asset_uri);
        let bio = { let raw = r.description.clone().map(|d| d.value).unwrap_or_default(); self.render_bio(&raw) };
        let act = r.active_cosmetics.clone().unwrap_or_default();
        let worn: Vec<String> = act.cosmetics.iter().map(|c| {
            let t = cosmetics::type_name(c.r#type);
            match c.title.as_ref().map(|s| s.value.trim()).filter(|s| !s.is_empty()) { Some(n) => format!("{t} · {n}"), None => t.to_string() }
        }).collect();
        let name_fx = act.cosmetics.iter().filter(|c| c.r#type == cosmetics::TYPE_NAME_EFFECT).filter_map(|c| c.name_effect.as_ref()).flat_map(|m| m.fills.iter()).find_map(cosmetics::fill);
        let bg = act.profile_background.as_ref().and_then(cosmetics::fill);
        let id = self.ids.local(h, l);
        let me = self.me;
        let u = self.users.entry(id).or_insert_with(|| User {
            id, name: r.username.clone(), display_name: r.username.clone(), hue: hue_of(h, l), status: Status::Offline, platforms: vec![],
            bio: String::new(), pronouns: String::new(), friend_since: None, avatar_url: None, banner_url: None, status_text: String::new(),
            bot: h & 0xFF == 9, cosmetic: Default::default(),
        });
        if !r.username.is_empty() { u.name = r.username.clone(); }
        u.bio = bio;
        if avatar.is_some() { u.avatar_url = avatar; }
        u.banner_url = banner;
        u.status_text = r.user_defined_status.map(|d| d.value).unwrap_or_default();
        let mut c = Cosmetic { name: name_fx.or(u.cosmetic.name.clone()), profile_bg: bg, worn, ..Default::default() };
        cosmetics::apply_ids(&mut c, act.cosmetics.iter().map(|x| (x.r#type, cosmetics::hex_id(&x.id))));
        u.cosmetic = c;
        let out = json!(u);
        if id == me { self.me_user = Some(serde_json::from_value(out.clone()).map_err(|e| e.to_string())?); }
        Ok(out)
    }

    fn take_error(&mut self) -> Option<String> {
        self.last_error.take()
    }
    fn upload(&mut self, path: &str, name: &str, mime: &str) -> Result<String, String> {
        use base64::Engine;
        use md5::Digest;
        let bytes = std::fs::read(path).map_err(|e| format!("Couldn't read file: {e}"))?;
        let b64 = |b: &[u8]| base64::engine::general_purpose::STANDARD.encode(b);
        let url = format!("{}/asset/upload", self.hub.base);
        let modified = {
            let t = crate::now();
            let (y, m, d) = civil(t / 86400);
            let s = t % 86400;
            format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}.000Z", s / 3600, s / 60 % 60, s % 60)
        };
        let resp = ureq::post(&url)
            .set("authorization", &format!("Bearer {}", self.hub.token.clone().unwrap_or_default()))
            .set("content-md5", &b64(&md5::Md5::digest(&bytes)))
            .set("x-amz-meta-root-content-sha256", &b64(&sha2::Sha256::digest(&bytes)))
            .set("x-amz-meta-root-file-modification", &modified)
            .set("x-amz-meta-root-file-name", name)
            .set("content-type", if mime.is_empty() { "application/octet-stream" } else { mime })
            .set("user-agent", USER_AGENT)
            .send_bytes(&bytes);
        match resp {
            Ok(r) => {
                let body = r.into_string().map_err(|e| e.to_string())?;
                // body is a JSON string: "<token uri>"
                serde_json::from_str::<String>(&body).or_else(|_| Ok::<_, String>(body.trim_matches('"').to_string()))
            }
            Err(ureq::Error::Status(code, r)) => Err(format!("Upload failed (HTTP {code}): {}", r.into_string().unwrap_or_default().chars().take(120).collect::<String>())),
            Err(e) => Err(format!("Upload failed: {e}")),
        }
    }

    fn gif_favorites(&mut self) -> Vec<String> {
        let Ok(body) = self.hub.raw("/root.UserFavoriteGifGrpcService/List", &prost::Message::encode_to_vec(&messages::Empty {})) else { return vec![] };
        let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
        // collect every https string in the response (favourite URLs)
        let mut out = Vec::new();
        fn walk(b: &[u8], out: &mut Vec<String>, d: usize) {
            if d > 5 { return }
            for (_, v) in wire::fields(b).unwrap_or_default() {
                if let wire::Value::Bytes(x) = v {
                    match std::str::from_utf8(x) {
                        Ok(s) if s.starts_with("https://") => out.push(s.to_string()),
                        _ => walk(x, out, d + 1),
                    }
                }
            }
        }
        walk(&payload, &mut out, 0);
        out.dedup();
        out
    }

    fn gif_favorite(&mut self, url: &str, add: bool) {
        let req = GifFavReq { context: Some(context()), url: url.to_string() };
        let path = if add { "/root.UserFavoriteGifGrpcService/Create" } else { "/root.UserFavoriteGifGrpcService/Delete" };
        if let Err(e) = self.hub.raw(path, &prost::Message::encode_to_vec(&req)) {
            self.last_error = Some(format!("Couldn't update favourites: {e}"));
        }
    }

    fn custom_emoji(&mut self) -> Vec<(String, String, String)> {
        // only emoji you're allowed to use (not every emoji seen in chats)
        if self.my_emoji.is_empty() {
            let list = self.fetch_custom_emoji();
            if !list.is_empty() { self.my_emoji = list }
        }
        let mut out = self.my_emoji.clone();
        out.sort_by(|a, b| a.1.to_lowercase().cmp(&b.1.to_lowercase()));
        out
    }

    fn edit_profile(&mut self, c: &serde_json::Value) -> Result<User, String> {
        let get = |k: &str| c.get(k).and_then(|v| v.as_str()).map(str::to_string);
        let sv = |v: String| Some(StringValue { value: v });
        if let Some(path) = get("avatar_path") {
            let token = self.upload(&path, "avatar.jpg", "image/jpeg")?;
            self.call_unit("/root.UserGrpcService/SetProfilePicture", &ProfileTokenReq { context: Some(context()), token: sv(token) })?;
        }
        if let Some(path) = get("banner_path") {
            let token = self.upload(&path, "banner.jpg", "image/jpeg")?;
            self.call_unit("/root.UserGrpcService/SetBanner", &ProfileTokenReq { context: Some(context()), token: sv(token) })?;
        }
        if let Some(name) = get("username") {
            self.call_unit("/root.UserGrpcService/SetUsername", &UsernameReq { context: Some(context()), username: name.trim().to_string() })?;
        }
        if let Some(d) = get("description") {
            self.call_unit("/root.UserGrpcService/SetDescription", &ProfileTokenReq { context: Some(context()), token: sv(d) })?;
        }
        if let Some(st) = get("status") {
            self.call_unit("/root.UserGrpcService/SetUserDefinedStatus", &ProfileTokenReq { context: Some(context()), token: sv(st) })?;
        }
        if let Some(p) = get("presence") {
            // Root's UserOnlineStatus: 16 active, 4 inactive (idle), 1 disconnected (appear offline)
            let v = match p.as_str() { "idle" => 4, "invisible" => 1, _ => 16 };
            self.call_unit("/root.UserGrpcService/SetMaxOnlineStatus", &PresenceReq { context: Some(context()), max_status: v })?;
        }
        self.load_self().map_err(|e| e.to_string())?;
        self.users.get(&self.me).cloned().ok_or_else(|| "profile reload failed".into())
    }

    fn social(&mut self, op: &str, a: &serde_json::Value) -> Result<serde_json::Value, String> {
        use prost::Message as _;
        use serde_json::json;
        let users_arg = |this: &Self, key: &str| -> Vec<UserUuid> {
            a.get(key).and_then(|v| v.as_array()).cloned().unwrap_or_default().iter()
                .filter_map(|v| v.as_u64()).filter_map(|id| this.ids.root(id)).map(|(h, l)| UserUuid { high64: h, low64: l }).collect()
        };
        match op {
            // Full profile (bio, banner, status, cosmetics) — Root 0.9.46 moved these to User/Get.
            "user_get" => {
                let uid = a.get("user").and_then(|v| v.as_u64()).ok_or("user")?;
                let job = self.user_get_prepare(uid).ok_or("unknown user")?;
                let done = job.run()?;
                self.user_get_apply(done)
            }
            // Download URLs of cosmetic art bundles (.tar.gz with manifest.json)
            "cosmetic_bundles" => {
                let ids: Vec<cosmetics::CosmeticUuid> = a.get("ids").and_then(|v| v.as_array()).cloned().unwrap_or_default().iter()
                    .filter_map(|v| v.as_str()).filter_map(cosmetics::parse_hex_id).collect();
                if ids.is_empty() { return Ok(json!({})); }
                let r: cosmetics::GetBundlesResp = self.hub.unary("/root.UserCosmeticGrpcService/GetBundlesByIds", &cosmetics::GetBundlesReq { ids }).map_err(|e| e.to_string())?;
                let mut out = serde_json::Map::new();
                for b in r.bundles { if let Some(id) = cosmetics::hex_id(&b.id) { out.insert(id, json!(b.url)); } }
                Ok(serde_json::Value::Object(out))
            }
            // Pinned messages of a channel / DM (Root's MessageGrpcService/Pin*).
            "pins" => {
                let ch = a.get("channel").and_then(|v| v.as_u64()).ok_or("channel")?;
                let (container, community) = self.container(ch).ok_or("unknown chat")?;
                let body = self.hub.raw("/root.v2.MessageGrpcService/PinList", &MessagePinListReq { container_id: Some(container), community_id: community }.encode_to_vec()).map_err(|e| e.to_string())?;
                let (frames, status) = grpcweb::unframe(&body).map_err(|e| e.to_string())?;
                if let Some(s) = status.filter(|s| s.code != 0) { return Err(grpcweb::Error::Status(s).to_string()); }
                let payload = frames.into_iter().next().unwrap_or_default();
                let raws: Vec<messages::RawMessage> = wire::find_all(&payload, |m: &messages::RawMessage| m.id.is_some() && m.user_id.is_some() && m.deleted_at.is_none());
                let authors: Vec<Id> = raws.iter().filter_map(|m| m.user_id.as_ref()).map(|u| self.ids.local(u.high64, u.low64)).collect();
                self.ensure_users(authors);
                let mut out: Vec<Message> = raws.iter().map(|r| self.convert_one(r, ch)).collect();
                out.dedup_by_key(|m| m.id);
                out.sort_by_key(|m| std::cmp::Reverse(m.ts));
                Ok(json!(out))
            }
            "pin" | "unpin" => {
                let id = a.get("id").and_then(|v| v.as_u64()).ok_or("id")?;
                let ch = a.get("channel").and_then(|v| v.as_u64()).ok_or("channel")?;
                let (container, community) = self.container(ch).ok_or("unknown chat")?;
                let (h, l) = self.ids.root(id).ok_or("unknown message")?;
                let req = MessagePinReq { context: Some(context()), container_id: Some(container), community_id: community, message_id: Some(MessageUuid { high64: h, low64: l }) };
                let path = if op == "pin" { "/root.v2.MessageGrpcService/PinCreate" } else { "/root.v2.MessageGrpcService/PinDelete" };
                self.call_unit(path, &req)?;
                Ok(json!(true))
            }
            // mentionable roles of a server, for the @ picker
            "roles" => {
                let sid = a.get("server_id").and_then(|v| v.as_u64()).ok_or("server_id")?;
                let (h, l) = self.ids.root(sid).ok_or("unknown server")?;
                let body = self.hub.raw("/root.CommunityRoleGrpcService/List", &RoleListReq { community_id: Some(CommunityUuid { high64: h, low64: l }) }.encode_to_vec()).map_err(|e| e.to_string())?;
                let payload = grpcweb::unframe(&body).map_err(|e| e.to_string())?.0.into_iter().next().unwrap_or_default();
                let roles: Vec<RoleInfo> = wire::find_all(&payload, |r: &RoleInfo| r.id.is_some() && !r.name.is_empty());
                let mut out: Vec<serde_json::Value> = roles.iter().filter(|r| r.is_mentionable).map(|r| {
                    let id = r.id.as_ref().unwrap();
                    json!({ "id": dashed_guid(id.high64, id.low64), "name": r.name, "color": r.color_hex })
                }).collect();
                out.dedup_by(|a, b| a["id"] == b["id"]);
                Ok(json!(out))
            }
            "ring_decline" => {
                let ch = a.get("channel").and_then(|v| v.as_u64()).ok_or("channel")?;
                let (h, l) = self.ids.root(ch).ok_or("unknown chat")?;
                let req = RingDeclineReq { context: Some(context()), id: Some(EmojiUuid { high64: h, low64: l }) };
                self.hub.raw("/root.DirectMessageGrpcService/RingDecline", &req.encode_to_vec()).map_err(|e| e.to_string())?;
                Ok(json!(true))
            }
            // when an account was made (Root ids carry their creation time)
            "joined" => {
                let uid = a.get("user_id").and_then(|v| v.as_u64()).ok_or("user_id")?;
                let (h, _) = self.ids.root(uid).ok_or("unknown user")?;
                Ok(json!(messages::guid_time(h)))
            }
            // every role of a server, for the Roles page
            "roles_all" => {
                let sid = a.get("server_id").and_then(|v| v.as_u64()).ok_or("server_id")?;
                let (h, l) = self.ids.root(sid).ok_or("unknown server")?;
                let body = self.hub.raw("/root.CommunityRoleGrpcService/List", &RoleListReq { community_id: Some(CommunityUuid { high64: h, low64: l }) }.encode_to_vec()).map_err(|e| e.to_string())?;
                let payload = grpcweb::unframe(&body).map_err(|e| e.to_string())?.0.into_iter().next().unwrap_or_default();
                let mut roles: Vec<RoleFull> = wire::find_all(&payload, |r: &RoleFull| r.id.is_some() && !r.name.is_empty());
                roles.sort_by(|a, b| b.position.total_cmp(&a.position));
                let mut seen = std::collections::HashSet::new();
                let out: Vec<serde_json::Value> = roles.iter().filter(|r| seen.insert(r.id.as_ref().map(|i| (i.high64, i.low64)))).map(|r| {
                    let id = r.id.as_ref().unwrap();
                    json!({ "id": dashed_guid(id.high64, id.low64), "name": r.name, "color": r.color_hex, "position": r.position,
                            "mentionable": r.is_mentionable, "self_assignable": r.is_self_assignable, "members": r.member_count })
                }).collect();
                Ok(json!(out))
            }
            // role ids a member has in a server
            "member_roles" => {
                let sid = a.get("server_id").and_then(|v| v.as_u64()).ok_or("server_id")?;
                let uid = a.get("user_id").and_then(|v| v.as_u64()).ok_or("user_id")?;
                let (h, l) = self.ids.root(sid).ok_or("unknown server")?;
                let (uh, ul) = self.ids.root(uid).ok_or("unknown user")?;
                let req = MemberRoleListReq { community_id: Some(CommunityUuid { high64: h, low64: l }), user_id: Some(UserUuid { high64: uh, low64: ul }) };
                let body = self.hub.raw("/root.CommunityMemberRoleGrpcService/List", &req.encode_to_vec()).map_err(|e| e.to_string())?;
                let payload = grpcweb::unframe(&body).map_err(|e| e.to_string())?.0.into_iter().next().unwrap_or_default();
                let r = MemberRoleListResp::decode(payload.as_slice()).unwrap_or_default();
                Ok(json!(r.community_role_ids.iter().map(|i| dashed_guid(i.high64, i.low64)).collect::<Vec<_>>()))
            }
            "role_create" => {
                let sid = a.get("server_id").and_then(|v| v.as_u64()).ok_or("server_id")?;
                let (h, l) = self.ids.root(sid).ok_or("unknown server")?;
                let name = a.get("name").and_then(|v| v.as_str()).unwrap_or("new role").to_string();
                let color = a.get("color").and_then(|v| v.as_str()).map(|c| StringValue { value: c.to_string() });
                let req = RoleCreateReq { context: Some(context()), community_id: Some(CommunityUuid { high64: h, low64: l }), name, color_hex: color, is_mentionable: a.get("mentionable").and_then(|v| v.as_bool()).unwrap_or(false) };
                self.hub.raw("/root.CommunityRoleGrpcService/Create", &req.encode_to_vec()).map_err(|e| e.to_string())?;
                Ok(json!(true))
            }
            "role_edit" => {
                // Edit replaces everything, so start from the role exactly as Root has it
                // (permissions passed back byte-for-byte) and change only what was asked.
                let sid = a.get("server_id").and_then(|v| v.as_u64()).ok_or("server_id")?;
                let rid = a.get("role_id").and_then(|v| v.as_str()).and_then(parse_guid).ok_or("role_id")?;
                let (h, l) = self.ids.root(sid).ok_or("unknown server")?;
                let body = self.hub.raw("/root.CommunityRoleGrpcService/List", &RoleListReq { community_id: Some(CommunityUuid { high64: h, low64: l }) }.encode_to_vec()).map_err(|e| e.to_string())?;
                let payload = grpcweb::unframe(&body).map_err(|e| e.to_string())?.0.into_iter().next().unwrap_or_default();
                let roles: Vec<RoleFull> = wire::find_all(&payload, |r: &RoleFull| r.id.is_some() && !r.name.is_empty());
                let cur = roles.into_iter().find(|r| r.id.as_ref().is_some_and(|i| (i.high64, i.low64) == rid)).ok_or("role not found")?;
                let req = RoleEditReq {
                    context: Some(context()),
                    community_id: Some(CommunityUuid { high64: h, low64: l }),
                    id: cur.id.clone(),
                    name: a.get("name").and_then(|v| v.as_str()).map(str::to_string).unwrap_or(cur.name.clone()),
                    color_hex: a.get("color").and_then(|v| v.as_str()).map(str::to_string).unwrap_or(cur.color_hex.clone()),
                    community_permission: cur.community_permission.clone(),
                    channel_permission: cur.channel_permission.clone(),
                    is_mentionable: a.get("mentionable").and_then(|v| v.as_bool()).unwrap_or(cur.is_mentionable),
                    is_self_assignable: a.get("self_assignable").and_then(|v| v.as_bool()).unwrap_or(cur.is_self_assignable),
                };
                self.hub.raw("/root.CommunityRoleGrpcService/Edit", &req.encode_to_vec()).map_err(|e| e.to_string())?;
                Ok(json!(true))
            }
            "role_delete" => {
                let sid = a.get("server_id").and_then(|v| v.as_u64()).ok_or("server_id")?;
                let rid = a.get("role_id").and_then(|v| v.as_str()).and_then(parse_guid).ok_or("role_id")?;
                let (h, l) = self.ids.root(sid).ok_or("unknown server")?;
                let req = RoleDeleteReq { context: Some(context()), community_id: Some(CommunityUuid { high64: h, low64: l }), id: Some(CommunityRoleUuid { high64: rid.0, low64: rid.1 }) };
                self.hub.raw("/root.CommunityRoleGrpcService/Delete", &req.encode_to_vec()).map_err(|e| e.to_string())?;
                Ok(json!(true))
            }
            "member_role_add" | "member_role_remove" => {
                let sid = a.get("server_id").and_then(|v| v.as_u64()).ok_or("server_id")?;
                let uid = a.get("user_id").and_then(|v| v.as_u64()).ok_or("user_id")?;
                let rid = a.get("role_id").and_then(|v| v.as_str()).and_then(parse_guid).ok_or("role_id")?;
                let (h, l) = self.ids.root(sid).ok_or("unknown server")?;
                let (uh, ul) = self.ids.root(uid).ok_or("unknown user")?;
                let req = MemberRoleChangeReq { context: Some(context()), community_id: Some(CommunityUuid { high64: h, low64: l }), community_role_id: Some(CommunityRoleUuid { high64: rid.0, low64: rid.1 }), user_ids: vec![UserUuid { high64: uh, low64: ul }] };
                let path = if op == "member_role_add" { "/root.CommunityMemberRoleGrpcService/Add" } else { "/root.CommunityMemberRoleGrpcService/Remove" };
                self.hub.raw(path, &req.encode_to_vec()).map_err(|e| e.to_string())?;
                Ok(json!(true))
            }
            "upload_target" => Ok(json!({ "url": format!("{}/asset/upload", self.hub.base), "token": self.hub.token.clone().unwrap_or_default() })),
            "notifications" => {
                let body = self.hub.raw("/root.NotificationGrpcService/List", &NotifListReq {}.encode_to_vec()).map_err(|e| e.to_string())?;
                let payload = grpcweb::unframe(&body).map_err(|e| e.to_string())?.0.into_iter().next().unwrap_or_default();
                let list: Vec<NotifPacket> = wire::find_all(&payload, |n: &NotifPacket| n.id.is_some() && n.notification_type > 0);
                let mut out = Vec::new();
                let mut people = Vec::new();
                for n in list {
                    use prost::Message as _;
                    let id = n.id.as_ref().unwrap();
                    // n.user_id is the recipient (you); the actor and the channel live in the payload
                    let pl = NotifPayload::decode(n.payload.as_slice()).unwrap_or_default();
                    let mut user = None;
                    let mut channel = None;
                    let mut message = None;
                    let mut text = None;
                    let mut place = None;
                    if let Some(mm) = &pl.mentioned {
                        if let Some(msg) = &mm.message {
                            user = msg.user_id.as_ref().map(|u| self.ids.local(u.high64, u.low64));
                            channel = msg.container_id.as_ref().map(|c| self.ids.local(c.high64, c.low64));
                            message = msg.id.as_ref().map(|m| self.ids.local(m.high64, m.low64));
                            if let (Some(ch), Some(c)) = (channel, msg.community_id.as_ref()) {
                                // remember which community the channel belongs to so it can be opened
                                let cid = self.ids.local(c.high64, c.low64);
                                self.channel_community.entry(ch).or_insert(cid);
                            }
                            text = Some(self.render_content(&msg.content, &[]).0);
                        }
                        let names: Vec<&str> = [mm.container_name.as_str(), mm.sub_container_name.as_str()].into_iter().filter(|x| !x.is_empty()).collect();
                        if !names.is_empty() { place = Some(names.join(" · #")) }
                    }
                    if let Some(inv) = &pl.invite {
                        let a = inv.user_id.as_ref().map(|u| self.ids.local(u.high64, u.low64));
                        let b = inv.friend_user_id.as_ref().map(|u| self.ids.local(u.high64, u.low64));
                        user = [a, b].into_iter().flatten().find(|x| *x != self.me);
                    }
                    if let Some(u) = user { people.push(u) }
                    out.push(json!({
                        "id": guid_text(id.high64, id.low64),
                        "type": n.notification_type,
                        "user_id": user,
                        "channel_id": channel,
                        "message_id": message,
                        "place": place,
                        "viewed": n.is_viewed,
                        "ts": messages::guid_time(id.high64),
                        "text": text,
                    }));
                }
                self.ensure_users(people);
                Ok(json!(out))
            }
            "notifications_read" => {
                self.call_unit("/root.NotificationGrpcService/SetAllViewed", &NotifListReq {})?;
                Ok(json!(true))
            }
            "friend_request" => {
                let name = a.get("username").and_then(|v| v.as_str()).ok_or("missing username")?.trim().to_string();
                self.call_unit("/root.FriendshipInviteGrpcService/Create", &FriendInviteReq { context: Some(context()), username: name })?;
                Ok(json!(true))
            }
            "friend_respond" => {
                let nid = a.get("notification").and_then(|v| v.as_str()).and_then(parse_guid).ok_or("missing notification")?;
                let user = a.get("user").and_then(|v| v.as_u64()).and_then(|u| self.ids.root(u)).ok_or("missing user")?;
                let accept = a.get("accept").and_then(|v| v.as_bool()).unwrap_or(false);
                self.call_unit("/root.FriendshipInviteGrpcService/Respond", &FriendRespondReq {
                    context: Some(context()),
                    notification_id: Some(EmojiUuid { high64: nid.0, low64: nid.1 }),
                    friend_user_id: Some(UserUuid { high64: user.0, low64: user.1 }),
                    accept,
                })?;
                if accept { self.load_friends(); }
                Ok(json!(true))
            }
            "friend_remove" => {
                let user = a.get("user").and_then(|v| v.as_u64()).and_then(|u| self.ids.root(u)).ok_or("missing user")?;
                self.call_unit("/root.FriendshipGrpcService/Delete", &FriendDeleteReq { context: Some(context()), user_id: Some(UserUuid { high64: user.0, low64: user.1 }) })?;
                let local = self.ids.to_local.get(&user).copied().unwrap_or(0);
                if let Some(v) = self.friends.get_mut(&self.me) { v.retain(|f| *f != local) }
                Ok(json!(true))
            }
            "dm_create" => {
                let members = users_arg(self, "users");
                if members.is_empty() { return Err("Pick at least one person".into()) }
                let body = self.hub.raw("/root.DirectMessageGrpcService/Create", &DmCreateReq { context: Some(context()), members }.encode_to_vec()).map_err(|e| e.to_string())?;
                let payload = grpcweb::unframe(&body).map_err(|e| e.to_string())?.0.into_iter().next().unwrap_or_default();
                let r = DmCreateResp::decode(payload.as_slice()).map_err(|e| e.to_string())?;
                self.load_dms();
                Ok(json!(r.id.map(|g| self.ids.local(g.high64, g.low64))))
            }
            "dm_add" => {
                let dm = a.get("dm").and_then(|v| v.as_u64()).and_then(|d| self.ids.root(d)).ok_or("missing dm")?;
                let members = users_arg(self, "users");
                self.call_unit("/root.DirectMessageGrpcService/AddMembers", &DmAddReq { context: Some(context()), id: Some(EmojiUuid { high64: dm.0, low64: dm.1 }), user_ids: members })?;
                self.load_dms();
                Ok(json!(true))
            }
            other => Err(format!("unknown op {other}")),
        }
    }

    fn voice(&mut self, op: &str, a: &serde_json::Value) -> Result<serde_json::Value, String> {
        use prost::Message as _;
        use serde_json::json;
        let call = |this: &Self, path: &str, body: Vec<u8>| -> Result<Vec<u8>, String> {
            let raw = this.hub.raw(path, &body).map_err(|e| e.to_string())?;
            Ok(grpcweb::unframe(&raw).map_err(|e| e.to_string())?.0.into_iter().next().unwrap_or_default())
        };
        if op == "ice" {
            let r = voice::IceResp::decode(call(self, "/root.WebRtcGrpcService/GetIceInfo", voice::IceReq {}.encode_to_vec())?.as_slice()).map_err(|e| e.to_string())?;
            return Ok(json!({ "urls": r.urls, "username": r.username, "credential": r.credentials }));
        }
        let channel = a.get("channel").and_then(|v| v.as_u64()).ok_or("missing channel")?;
        let (container, community) = self.container(channel).ok_or("unknown voice channel")?;
        let sdp = |a: &serde_json::Value| a.get("sdp").and_then(|v| v.as_str()).map(|s| voice::Sdp { r#type: a.get("sdp_type").and_then(|v| v.as_str()).unwrap_or("offer").into(), sdp: s.into() });
        let err = |code: &Option<StringValue>, desc: &Option<StringValue>| -> Option<String> {
            code.as_ref().filter(|c| !c.value.is_empty()).map(|c| format!("{}: {}", c.value, desc.as_ref().map(|d| d.value.as_str()).unwrap_or("")))
        };
        match op {
            "join" => {
                let req = voice::SessionCreateReq { context: Some(context()), community_id: community.clone(), container_id: Some(container.clone()), session_description: sdp(a), supports_v2: true };
                let r = voice::SessionCreateResp::decode(call(self, "/root.WebRtcGrpcService/SessionCreate", req.encode_to_vec())?.as_slice()).map_err(|e| e.to_string())?;
                if let Some(e) = err(&r.error_code, &r.error_description) { return Err(format!("Couldn't join voice: {e}")) }
                let members: Vec<serde_json::Value> = r.members.iter().map(|m| serde_json::to_value(self.voice_member(m)).unwrap()).collect();
                let ids: Vec<Id> = members.iter().filter_map(|m| m["user_id"].as_u64()).collect();
                self.ensure_users(ids);
                Ok(json!({
                    "sdp": r.session_description.as_ref().map(|s| s.sdp.clone()),
                    "sdp_type": r.session_description.as_ref().map(|s| s.r#type.clone()),
                    "session_id": r.session_id.map(|s| s.value),
                    "members": members,
                }))
            }
            "tracks" => {
                let tracks: Vec<voice::TrackReq> = a.get("tracks").and_then(|v| v.as_array()).cloned().unwrap_or_default().iter().map(|t| voice::TrackReq {
                    location: t["location"].as_str().unwrap_or("local").into(),
                    track_id: t["track_id"].as_str().unwrap_or("").into(),
                    mid: t["mid"].as_str().map(|m| StringValue { value: m.into() }),
                    is_audio: t["kind"] == "audio",
                    is_video: t["kind"] == "video",
                    is_screen: t["kind"] == "screen",
                    is_screen_audio: t["kind"] == "screen_audio",
                }).collect();
                let req = voice::TracksCreateReq { context: Some(context()), community_id: community, container_id: Some(container), tracks, session_description: sdp(a) };
                let r = voice::TracksCreateResp::decode(call(self, "/root.WebRtcGrpcService/TracksCreate", req.encode_to_vec())?.as_slice()).map_err(|e| e.to_string())?;
                if let Some(e) = err(&r.error_code, &r.error_description) { return Err(format!("Voice track error: {e}")) }
                Ok(json!({
                    "tracks": r.tracks.iter().map(|t| json!({ "track_id": t.track_id, "mid": t.mid, "location": t.location, "error": t.error_code.as_ref().map(|e| e.value.clone()) })).collect::<Vec<_>>(),
                    "sdp": r.session_description.as_ref().map(|s| s.sdp.clone()),
                    "sdp_type": r.session_description.as_ref().map(|s| s.r#type.clone()),
                    "renegotiate": r.requires_immediate_renegotiation,
                }))
            }
            "renegotiate" => {
                let req = voice::RenegotiateReq { context: Some(context()), community_id: community, container_id: Some(container), session_description: sdp(a) };
                call(self, "/root.WebRtcGrpcService/Renegotiate", req.encode_to_vec())?;
                Ok(json!(true))
            }
            "mute" => {
                let b = |k: &str| a.get(k).and_then(|v| v.as_bool()).map(|value| voice::BoolValue { value });
                let req = voice::MuteReq { context: Some(context()), community_id: community, container_id: Some(container), is_muted: b("muted"), is_deafened: b("deafened") };
                call(self, "/root.WebRtcGrpcService/SetMuteAndDeafen", req.encode_to_vec())?;
                Ok(json!(true))
            }
            "leave" => {
                let req = voice::DetachReq { context: Some(context()), community_id: community, container_id: Some(container) };
                call(self, "/root.WebRtcGrpcService/Detach", req.encode_to_vec())?;
                Ok(json!(true))
            }
            other => Err(format!("unknown voice op {other}")),
        }
    }

    fn refresh(&mut self, step: u32) -> Result<Option<u32>, String> {
        RootBackend::refresh(self, step)
    }
    fn set_extra_replies(&mut self, ids: Vec<Id>) {
        self.extra_replies = ids;
    }
    fn older_messages(&mut self, channel: Id) -> Result<Vec<Message>, String> {
        RootBackend::older_messages(self, channel)
    }
    fn mark_viewed(&mut self, channel: Id) {
        RootBackend::mark_viewed(self, channel)
    }
    fn cached_messages(&mut self, channel: Id) -> Vec<Message> {
        RootBackend::cached_messages(self, channel)
    }
    fn save_cache(&self, dir: &std::path::Path) {
        self.save_snapshot(dir)
    }

    fn set_typing(&mut self, channel: Id) {
        let now = crate::now();
        if self.typing_sent.get(&channel).is_some_and(|t| now - t < 6) {
            return;
        }
        self.typing_sent.insert(channel, now);
        if let Some((container, community)) = self.container(channel) {
            let req = MessageSetTypingIndicatorRequest { container_id: Some(container), community_id: community, is_typing: true };
            let _ = self.hub.raw("/root.v2.MessageGrpcService/SetTypingIndicator", &prost::Message::encode_to_vec(&req));
        }
    }
    fn message_mut(&mut self, id: Id) -> Option<&mut Message> {
        self.msgs.values_mut().flat_map(|v| v.iter_mut()).find(|m| m.id == id)
    }
    fn remove(&mut self, id: Id) {
        for v in self.msgs.values_mut() {
            v.retain(|m| m.id != id);
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

    /// No live event stream yet: refresh the open channel every few seconds and
    /// emit anything new / changed / removed as events.
    fn poll(&mut self, now: i64) -> Vec<Event> {
        let mut out = std::mem::take(&mut self.pending);
        let live = self.hub_conn.as_ref().is_some_and(|h| h.is_connected());
        let packets = self.hub_conn.as_ref().map(|h| h.drain()).unwrap_or_default();
        for p in packets {
            out.extend(self.packet_to_events(p));
        }
        // Polling is only a fallback while the live socket is down.
        if live {
            return out;
        }
        if let Some(ch) = self.active_channel {
            // back off while offline (4 s → 8 → 16 … 60 s) instead of hammering a dead network
            if now - self.last_refresh >= self.poll_every {
                self.last_refresh = now;
                let r = self.fetch_messages(ch);
                self.poll_every = if r.is_ok() { 4 } else { (self.poll_every * 2).min(60) };
                if let Ok(fresh) = r {
                    let old = self.msgs.get(&ch).cloned().unwrap_or_default();
                    let old_ids: HashMap<Id, &Message> = old.iter().map(|m| (m.id, m)).collect();
                    for m in &fresh {
                        match old_ids.get(&m.id) {
                            None => out.push(Event::Message(m.clone())),
                            // only Root's own edit stamp makes it an edit; text that differs just because
                            // emoji/@names were resolved later is a quiet refresh
                            Some(o) if o.content != m.content && m.edited_ts.is_some() => out.push(Event::Edit { id: m.id, content: m.content.clone(), ts: m.edited_ts.unwrap_or(now) }),
                            Some(o) if o.content != m.content => out.push(Event::Replace(m.clone())),
                            _ => {}
                        }
                    }
                    let fresh_ids: std::collections::HashSet<Id> = fresh.iter().map(|m| m.id).collect();
                    let oldest_fresh = fresh.first().map(|m| m.ts).unwrap_or(i64::MAX);
                    for o in &old {
                        // only report deletions inside the window we re-fetched
                        if !fresh_ids.contains(&o.id) && o.ts >= oldest_fresh && o.author_id != self.me {
                            out.push(Event::Delete { id: o.id });
                        }
                    }
                    // New messages are inserted by the core when it applies the events.
                    self.msgs.insert(ch, old);
                }
            }
        }
        out
    }
}

/// Root's edit/delete/reaction responses carry nothing we need; decode as empty.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct MessageEditResponseOrEmpty {}

#[derive(Clone, PartialEq, ::prost::Message)]
struct AssetGetReq {
    #[prost(string, repeated, tag = "1")]
    uris: Vec<String>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct EmojiResolveReq {
    #[prost(message, repeated, tag = "10")]
    ids: Vec<EmojiUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct EmojiInfo {
    #[prost(message, optional, tag = "11")]
    id: Option<EmojiUuid>,
    #[prost(string, tag = "12")]
    shortcode: String,
    #[prost(string, tag = "13")]
    asset_uri: String,
}

/// Asset.Get response: map<uri, Asset>; each Asset has size variants
/// `{10: url, 11: width, ...}` and an expiry Timestamp at field 10.
/// Picks the smallest variant at least 256px wide (or the largest).
fn parse_asset_map(payload: &[u8]) -> Vec<(String, String, i64, u32, u32)> {
    let mut out = Vec::new();
    for (_, v) in wire::fields(payload).unwrap_or_default() {
        let wire::Value::Bytes(entry) = v else { continue };
        let mut key = None;
        let mut val: Option<&[u8]> = None;
        for (t, v) in wire::fields(entry).unwrap_or_default() {
            match (t, v) {
                (1, wire::Value::Bytes(k)) => key = std::str::from_utf8(k).ok().map(str::to_string),
                (2, wire::Value::Bytes(b)) => val = Some(b),
                _ => {}
            }
        }
        let (Some(key), Some(val)) = (key, val) else { continue };
        let mut variants: Vec<(String, u64)> = Vec::new();
        let mut expiry = crate::now() + 3600;
        let mut dims = (0u32, 0u32);
        collect_variants(val, &mut variants, &mut expiry, 0);
        find_dims(val, &mut dims, 0);
        variants.sort_by_key(|(_, w)| *w);
        // a bigger variant for full-screen viewing would be nice, but 1024+ wide keeps chat crisp
        let pick = variants.iter().find(|(_, w)| *w >= 1024).or(variants.last()).map(|(u, _)| u.clone());
        if let Some(url) = pick {
            out.push((key, url, expiry, dims.0, dims.1));
        }
    }
    out
}

/// Largest width/height pair found: image variants {11: w, 12: h}, video {7: {1: w, 2: h}}.
fn find_dims(b: &[u8], best: &mut (u32, u32), depth: usize) {
    if depth > 5 {
        return;
    }
    let Some(fs) = wire::fields(b) else { return };
    let num = |tag: u32| fs.iter().find_map(|(t, v)| match (t, v) { (t, wire::Value::Varint(n)) if *t == tag => Some(*n as u32), _ => None });
    let is_url_variant = fs.iter().any(|(t, v)| *t == 10 && matches!(v, wire::Value::Bytes(_)));
    if is_url_variant {
        if let (Some(w), Some(h)) = (num(11), num(12)) {
            if w as u64 * h as u64 > best.0 as u64 * best.1 as u64 {
                *best = (w, h);
            }
        }
    }
    for (t, v) in &fs {
        if let wire::Value::Bytes(x) = v {
            if *t == 7 {
                if let Some(f) = wire::fields(x) {
                    let w = f.iter().find_map(|(t, v)| match (t, v) { (1, wire::Value::Varint(n)) => Some(*n as u32), _ => None });
                    let h = f.iter().find_map(|(t, v)| match (t, v) { (2, wire::Value::Varint(n)) => Some(*n as u32), _ => None });
                    if let (Some(w), Some(h)) = (w, h) {
                        if w > 0 && h > 0 && best.0 == 0 {
                            *best = (w, h);
                        }
                    }
                }
            }
            find_dims(x, best, depth + 1);
        }
    }
}

fn collect_variants(b: &[u8], out: &mut Vec<(String, u64)>, expiry: &mut i64, depth: usize) {
    if depth > 5 {
        return;
    }
    let Some(fs) = wire::fields(b) else { return };
    // Cloudflare Stream video: {2: HLS .m3u8, 3: DASH .mpd, 4: thumbnail .jpg, ...}
    let text = |tag: u32| fs.iter().find_map(|(t, v)| match (t, v) {
        (t, wire::Value::Bytes(s)) if *t == tag => std::str::from_utf8(s).ok().filter(|s| s.starts_with("https://")),
        _ => None,
    });
    if let Some(hls) = text(2).filter(|u| u.split('?').next().unwrap_or("").ends_with(".m3u8")) {
        let thumb = text(4).unwrap_or("");
        // width u64::MAX so the stream wins the size pick; thumbnail rides along after a newline
        out.push((format!("{hls}\n{thumb}"), u64::MAX));
        return;
    }
    let url = fs.iter().find_map(|(t, v)| match (t, v) {
        (10, wire::Value::Bytes(s)) => std::str::from_utf8(s).ok().filter(|s| s.starts_with("https://")),
        _ => None,
    });
    if let Some(url) = url {
        let w = fs.iter().find_map(|(t, v)| match (t, v) { (11, wire::Value::Varint(n)) => Some(*n), _ => None }).unwrap_or(0);
        out.push((url.to_string(), w));
        return;
    }
    for (t, v) in &fs {
        if let wire::Value::Bytes(x) = v {
            // a Timestamp {1: seconds varint, 2: nanos}
            if *t == 10 {
                if let Some(secs) = wire::fields(x).and_then(|f| f.iter().find_map(|(t, v)| match (t, v) { (1, wire::Value::Varint(n)) if *n > 1_600_000_000 => Some(*n as i64), _ => None })) {
                    *expiry = secs;
                    continue;
                }
            }
            collect_variants(x, out, expiry, depth + 1);
        }
    }
}

/// Root's text GUIDs are base64url of the 16 bytes (hi then lo, big-endian).
pub fn parse_guid(s: &str) -> Option<(u64, u64)> {
    use base64::Engine;
    // long form: 8-4-4-4-12 hex
    if s.len() == 36 && s.matches('-').count() == 4 {
        let hex: String = s.chars().filter(|c| *c != '-').collect();
        let v = u128::from_str_radix(&hex, 16).ok()?;
        return Some(((v >> 64) as u64, v as u64));
    }
    let b = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(s.trim_end_matches('=')).ok()?;
    if b.len() < 16 {
        return None;
    }
    Some((u64::from_be_bytes(b[0..8].try_into().ok()?), u64::from_be_bytes(b[8..16].try_into().ok()?)))
}

/// `:name:` candidates in text, skipping ⟦…⟧ tokens and URL schemes ("https://").
fn shortcode_candidates(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut plain = String::new();
    let mut depth = 0;
    for ch in text.chars() {
        match ch { '⟦' => { depth += 1; plain.push(' ') } '⟧' => { depth -= 1; plain.push(' ') } c if depth == 0 => plain.push(c), _ => {} }
    }
    let mut rest = plain.as_str();
    while let Some(a) = rest.find(':') {
        let after = &rest[a + 1..];
        match after.find(':') {
            Some(e) if e > 1 && e <= 48 && after[..e].chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-') && !after[e + 1..].starts_with("//") => {
                let c = &after[..e];
                if !c.eq_ignore_ascii_case("http") && !c.eq_ignore_ascii_case("https") { out.push(c.to_string()) }
                rest = &after[e + 1..];
            }
            _ => rest = after,
        }
    }
    out
}

/// Root's long id form (users, roles): 8-4-4-4-12 lowercase hex.
pub fn dashed_guid(hi: u64, lo: u64) -> String {
    let h = format!("{hi:016x}{lo:016x}");
    format!("{}-{}-{}-{}-{}", &h[0..8], &h[8..12], &h[12..16], &h[16..20], &h[20..32])
}

pub fn guid_text(hi: u64, lo: u64) -> String {
    use base64::Engine;
    let mut b = [0u8; 16];
    b[..8].copy_from_slice(&hi.to_be_bytes());
    b[8..].copy_from_slice(&lo.to_be_bytes());
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b)
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct EmojiUuid {
    #[prost(fixed64, tag = "1")]
    pub high64: u64,
    #[prost(fixed64, tag = "2")]
    pub low64: u64,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct FriendshipRef {
    #[prost(message, optional, tag = "10")]
    id: Option<EmojiUuid>,
    #[prost(message, optional, tag = "11")]
    friend_user_id: Option<UserUuid>,
}

/// DirectMessageResponse with the member list (field 13, verified live).
#[derive(Clone, PartialEq, ::prost::Message)]
struct DmInfo {
    #[prost(message, optional, tag = "10")]
    id: Option<EmojiUuid>,
    #[prost(message, optional, tag = "11")]
    user_last_viewed_at: Option<Timestamp>,
    #[prost(message, repeated, tag = "13")]
    members: Vec<UserUuid>,
    #[prost(message, optional, tag = "14")]
    last_message: Option<messages::RawMessage>,
    #[prost(message, optional, tag = "16")]
    last_activity_at: Option<Timestamp>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct AttachReq {
    #[prost(message, optional, tag = "10")]
    community_id: Option<CommunityUuid>,
}

/// Common header of message-related packets.
#[derive(Clone, PartialEq, ::prost::Message)]
struct PacketEnvelope {
    #[prost(int32, tag = "1")]
    packet_type: i32,
    #[prost(message, optional, tag = "4")]
    container_id: Option<MessageContainerUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct DeletedPacket {
    #[prost(message, optional, tag = "5")]
    id: Option<MessageUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct ReactionPacket {
    #[prost(string, tag = "5")]
    shortcode: String,
    #[prost(message, optional, tag = "6")]
    message_id: Option<MessageUuid>,
    #[prost(message, optional, tag = "7")]
    user_id: Option<UserUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct TypingPacket {
    #[prost(message, optional, tag = "5")]
    user_id: Option<UserUuid>,
    #[prost(bool, tag = "6")]
    is_typing: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct StatusPacket {
    #[prost(message, optional, tag = "3")]
    user_id: Option<UserUuid>,
    #[prost(int32, tag = "4")]
    online_status: i32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct GifFavReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(string, tag = "10")]
    url: String,
}

fn civil(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

/// Shared shape of SetProfilePicture / SetBanner / SetDescription / SetUserDefinedStatus:
/// `{1: context, 10: StringValue}`.
#[derive(Clone, PartialEq, ::prost::Message)]
struct ProfileTokenReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    token: Option<StringValue>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct UsernameReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(string, tag = "10")]
    username: String,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct PresenceReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(int32, tag = "10")]
    max_status: i32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct ViewTimeReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    container_id: Option<MessageContainerUuid>,
    #[prost(message, optional, tag = "11")]
    community_id: Option<CommunityUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct AppStoreGetReq {
    #[prost(message, optional, tag = "4")]
    id: Option<EmojiUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct AppInfo {
    #[prost(string, tag = "8")]
    name: String,
    #[prost(string, tag = "12")]
    icon_asset_uri: String,
}

impl RootBackend {
    pub fn fetch_custom_emoji(&mut self) -> Vec<(String, String, String)> {
        let Ok(body) = self.hub.raw("/root.CommunityEmojiGrpcService/ListMine", &prost::Message::encode_to_vec(&messages::Empty {})) else { return vec![] };
        let payload = grpcweb::unframe(&body).ok().and_then(|(m, _)| m.into_iter().next()).unwrap_or_default();
        let list: Vec<EmojiInfo> = wire::find_all(&payload, |e: &EmojiInfo| e.id.is_some() && !e.asset_uri.is_empty() && !e.shortcode.is_empty());
        let uris: Vec<String> = list.iter().map(|e| e.asset_uri.clone()).collect();
        self.resolve_assets(&uris);
        let mut out = Vec::new();
        for e in list {
            let id = e.id.as_ref().unwrap();
            let guid = guid_text(id.high64, id.low64);
            if let Some((url, _)) = self.asset_cache.get(&e.asset_uri) {
                self.emoji_cache.insert(guid.clone(), (url.clone(), e.shortcode.clone()));
                out.push((guid, e.shortcode, url.clone()));
            }
        }
        out.sort_by(|a, b| a.1.cmp(&b.1));
        out.dedup_by(|a, b| a.0 == b.0);
        out
    }
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct NotifListReq {}

#[derive(Clone, PartialEq, ::prost::Message)]
struct NotifPacket {
    #[prost(message, optional, tag = "3")]
    id: Option<EmojiUuid>,
    #[prost(message, optional, tag = "4")]
    container_id: Option<EmojiUuid>,
    #[prost(message, optional, tag = "6")]
    user_id: Option<UserUuid>,
    #[prost(int32, tag = "7")]
    notification_type: i32,
    /// kept raw: we only scan it for preview text
    #[prost(bytes = "vec", tag = "8")]
    payload: Vec<u8>,
    #[prost(bool, tag = "9")]
    is_viewed: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct FriendInviteReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(string, tag = "10")]
    username: String,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct FriendRespondReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    notification_id: Option<EmojiUuid>,
    #[prost(message, optional, tag = "11")]
    friend_user_id: Option<UserUuid>,
    #[prost(bool, tag = "12")]
    accept: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct FriendDeleteReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "5")]
    user_id: Option<UserUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct DmCreateReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, repeated, tag = "10")]
    members: Vec<UserUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct DmCreateResp {
    #[prost(message, optional, tag = "5")]
    id: Option<EmojiUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct DmAddReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    id: Option<EmojiUuid>,
    #[prost(message, repeated, tag = "11")]
    user_ids: Vec<UserUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct NotifPayload {
    #[prost(message, optional, tag = "4")]
    invite: Option<NotifInvite>,
    #[prost(message, optional, tag = "6")]
    mentioned: Option<NotifMentioned>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct NotifInvite {
    #[prost(message, optional, tag = "4")]
    user_id: Option<UserUuid>,
    #[prost(message, optional, tag = "5")]
    friend_user_id: Option<UserUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct NotifMentioned {
    #[prost(message, optional, tag = "4")]
    message: Option<NotifMessage>,
    #[prost(string, tag = "5")]
    container_name: String,
    #[prost(string, tag = "6")]
    sub_container_name: String,
}

/// The parts of MessagePacket a notification needs.
#[derive(Clone, PartialEq, ::prost::Message)]
struct NotifMessage {
    #[prost(message, optional, tag = "3")]
    community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "4")]
    container_id: Option<MessageContainerUuid>,
    #[prost(message, optional, tag = "5")]
    user_id: Option<UserUuid>,
    #[prost(message, optional, tag = "6")]
    id: Option<MessageUuid>,
    #[prost(string, tag = "10")]
    content: String,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct RoleListReq {
    #[prost(message, optional, tag = "10")]
    community_id: Option<CommunityUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct RoleInfo {
    #[prost(message, optional, tag = "10")]
    id: Option<CommunityRoleUuid>,
    #[prost(string, tag = "13")]
    name: String,
    #[prost(string, tag = "14")]
    color_hex: String,
    #[prost(bool, tag = "16")]
    is_mentionable: bool,
}

#[cfg(test)]
mod mention_tests {
    #[test]
    fn candidates_skip_tokens_and_urls() {
        let t = "hi :LegoCry: ⟦e:https://x.net/a|Wave⟧ see https://y.com :ok_hand: ⟦m:bob⟧";
        assert_eq!(super::shortcode_candidates(t), vec!["LegoCry".to_string(), "ok_hand".to_string()]);
    }

    #[test]
    fn dashed() {
        assert_eq!(super::dashed_guid(0x00318e1df7f98201, 0x8d29dd78b5f49753), "00318e1d-f7f9-8201-8d29-dd78b5f49753");
        assert_eq!(super::parse_guid("00318e1d-f7f9-8201-8d29-dd78b5f49753"), Some((0x00318e1df7f98201, 0x8d29dd78b5f49753)));
    }
}

/// A role as Root lists it; the permission messages are kept as raw bytes so an edit
/// can send them back unchanged.
#[derive(Clone, PartialEq, ::prost::Message)]
struct RoleFull {
    #[prost(message, optional, tag = "10")]
    id: Option<CommunityRoleUuid>,
    #[prost(bytes = "vec", optional, tag = "11")]
    channel_permission: Option<Vec<u8>>,
    #[prost(bytes = "vec", optional, tag = "12")]
    community_permission: Option<Vec<u8>>,
    #[prost(string, tag = "13")]
    name: String,
    #[prost(string, tag = "14")]
    color_hex: String,
    #[prost(float, tag = "15")]
    position: f32,
    #[prost(bool, tag = "16")]
    is_mentionable: bool,
    #[prost(bool, tag = "17")]
    is_self_assignable: bool,
    #[prost(int32, tag = "18")]
    member_count: i32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct RoleCreateReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    community_id: Option<CommunityUuid>,
    #[prost(string, tag = "11")]
    name: String,
    #[prost(message, optional, tag = "12")]
    color_hex: Option<StringValue>,
    #[prost(bool, tag = "17")]
    is_mentionable: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct RoleEditReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    id: Option<CommunityRoleUuid>,
    #[prost(string, tag = "13")]
    name: String,
    #[prost(string, tag = "14")]
    color_hex: String,
    #[prost(bytes = "vec", optional, tag = "15")]
    community_permission: Option<Vec<u8>>,
    #[prost(bytes = "vec", optional, tag = "16")]
    channel_permission: Option<Vec<u8>>,
    #[prost(bool, tag = "17")]
    is_mentionable: bool,
    #[prost(bool, tag = "18")]
    is_self_assignable: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct RoleDeleteReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    id: Option<CommunityRoleUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct MemberRoleListReq {
    #[prost(message, optional, tag = "10")]
    community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    user_id: Option<UserUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct MemberRoleListResp {
    #[prost(message, optional, tag = "10")]
    user_id: Option<UserUuid>,
    #[prost(message, repeated, tag = "11")]
    community_role_ids: Vec<CommunityRoleUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct MemberRoleChangeReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    community_role_id: Option<CommunityRoleUuid>,
    #[prost(message, repeated, tag = "12")]
    user_ids: Vec<UserUuid>,
}

/// DirectMessageRingPacket / RingDeclinedPacket share the layout: 3 = user, 4 = DM id.
#[derive(Clone, PartialEq, ::prost::Message)]
struct RingPacket {
    #[prost(message, optional, tag = "3")]
    caller_user_id: Option<UserUuid>,
    #[prost(message, optional, tag = "4")]
    id: Option<EmojiUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct RingDeclineReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    id: Option<EmojiUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct VoiceListReq {
    #[prost(message, optional, tag = "10")]
    community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    container_id: Option<MessageContainerUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct VoiceListResp {
    #[prost(bytes = "vec", repeated, tag = "11")]
    members: Vec<Vec<u8>>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct MessagePinListReq {
    #[prost(message, optional, tag = "10")]
    container_id: Option<MessageContainerUuid>,
    #[prost(message, optional, tag = "11")]
    community_id: Option<CommunityUuid>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
struct MessagePinReq {
    #[prost(message, optional, tag = "1")]
    context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    container_id: Option<MessageContainerUuid>,
    #[prost(message, optional, tag = "11")]
    community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "12")]
    message_id: Option<MessageUuid>,
}
