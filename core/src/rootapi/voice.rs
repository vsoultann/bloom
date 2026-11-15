//! Voice/video signalling for Root's WebRTC SFU (WebRtcGrpcService).
//! The media itself runs in Android's WebRTC engine; this module only exchanges
//! SDP and track descriptions with Root:
//!   GetIceInfo → SessionCreate(offer) → answer + session id + members
//!   TracksCreate(local: mid/track_id | remote: track_id) → answer / server offer
//!   Renegotiate(answer) when the server sends a new offer
//!   SetMuteAndDeafen, Detach (leave)

use super::proto::{CommunityUuid, MessageContainerUuid, RootContext, StringValue, UserUuid};
use serde::Serialize;

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct Sdp {
    #[prost(string, tag = "4")]
    pub r#type: String,
    #[prost(string, tag = "5")]
    pub sdp: String,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct IceReq {}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct IceResp {
    #[prost(string, repeated, tag = "1")]
    pub urls: Vec<String>,
    #[prost(string, tag = "2")]
    pub username: String,
    #[prost(string, tag = "3")]
    pub credentials: String,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SessionCreateReq {
    #[prost(message, optional, tag = "1")]
    pub context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    pub community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    pub container_id: Option<MessageContainerUuid>,
    #[prost(message, optional, tag = "12")]
    pub session_description: Option<Sdp>,
    #[prost(bool, tag = "13")]
    pub supports_v2: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct DevicePacket {
    #[prost(message, optional, tag = "4")]
    pub container_id: Option<MessageContainerUuid>,
    #[prost(message, optional, tag = "5")]
    pub user_id: Option<UserUuid>,
    #[prost(bool, tag = "7")]
    pub is_muted: bool,
    #[prost(bool, tag = "8")]
    pub is_admin_muted: bool,
    #[prost(bool, tag = "9")]
    pub is_deafened: bool,
    #[prost(message, optional, tag = "20")]
    pub audio_track_id: Option<StringValue>,
    #[prost(message, optional, tag = "21")]
    pub video_track_id: Option<StringValue>,
    #[prost(message, optional, tag = "22")]
    pub screen_track_id: Option<StringValue>,
    #[prost(message, optional, tag = "23")]
    pub screen_audio_track_id: Option<StringValue>,
    #[prost(bool, tag = "25")]
    pub is_video_paused: bool,
    #[prost(bool, tag = "26")]
    pub is_screen_paused: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SessionCreateResp {
    #[prost(message, optional, tag = "4")]
    pub session_description: Option<Sdp>,
    #[prost(message, optional, tag = "5")]
    pub session_id: Option<StringValue>,
    #[prost(message, repeated, tag = "6")]
    pub members: Vec<DevicePacket>,
    #[prost(message, optional, tag = "15")]
    pub error_code: Option<StringValue>,
    #[prost(message, optional, tag = "16")]
    pub error_description: Option<StringValue>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct TrackReq {
    #[prost(string, tag = "4")]
    pub location: String,
    #[prost(string, tag = "5")]
    pub track_id: String,
    #[prost(message, optional, tag = "7")]
    pub mid: Option<StringValue>,
    #[prost(bool, tag = "8")]
    pub is_audio: bool,
    #[prost(bool, tag = "9")]
    pub is_video: bool,
    #[prost(bool, tag = "10")]
    pub is_screen: bool,
    #[prost(bool, tag = "11")]
    pub is_screen_audio: bool,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct TracksCreateReq {
    #[prost(message, optional, tag = "1")]
    pub context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    pub community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    pub container_id: Option<MessageContainerUuid>,
    #[prost(message, repeated, tag = "12")]
    pub tracks: Vec<TrackReq>,
    #[prost(message, optional, tag = "13")]
    pub session_description: Option<Sdp>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct TrackResp {
    #[prost(string, tag = "5")]
    pub track_id: String,
    #[prost(string, tag = "6")]
    pub location: String,
    #[prost(string, tag = "7")]
    pub mid: String,
    #[prost(message, optional, tag = "15")]
    pub error_code: Option<StringValue>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct TracksCreateResp {
    #[prost(message, repeated, tag = "4")]
    pub tracks: Vec<TrackResp>,
    #[prost(message, optional, tag = "5")]
    pub session_description: Option<Sdp>,
    #[prost(bool, tag = "6")]
    pub requires_immediate_renegotiation: bool,
    #[prost(message, optional, tag = "15")]
    pub error_code: Option<StringValue>,
    #[prost(message, optional, tag = "16")]
    pub error_description: Option<StringValue>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RenegotiateReq {
    #[prost(message, optional, tag = "1")]
    pub context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    pub community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    pub container_id: Option<MessageContainerUuid>,
    #[prost(message, optional, tag = "12")]
    pub session_description: Option<Sdp>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct MuteReq {
    #[prost(message, optional, tag = "1")]
    pub context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    pub community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    pub container_id: Option<MessageContainerUuid>,
    #[prost(message, optional, tag = "13")]
    pub is_muted: Option<BoolValue>,
    #[prost(message, optional, tag = "14")]
    pub is_deafened: Option<BoolValue>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct DetachReq {
    #[prost(message, optional, tag = "1")]
    pub context: Option<RootContext>,
    #[prost(message, optional, tag = "10")]
    pub community_id: Option<CommunityUuid>,
    #[prost(message, optional, tag = "11")]
    pub container_id: Option<MessageContainerUuid>,
}

/// A participant as the UI sees it.
#[derive(Serialize, Clone, Debug)]
pub struct VoiceMember {
    pub user_id: u64,
    pub muted: bool,
    pub deafened: bool,
    pub audio_track: Option<String>,
    pub video_track: Option<String>,
    pub screen_track: Option<String>,
    pub screen_audio_track: Option<String>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct BoolValue {
    #[prost(bool, tag = "1")]
    pub value: bool,
}
