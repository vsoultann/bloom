//! Root's cosmetics (added in Root 0.9.46, Nov 2026) and the per-user `User/Get` call,
//! which now carries bios, banners and cosmetics (`UserResponse` lost fields 16/17).
//!
//! Schema read from the decompiled bundle (`tools/root_schema.json`):
//! - `UserCosmeticType`: AvatarFrame 1, CardBorder 2, NameFont 3, NameEffect 4
//! - `UserCosmeticFill` is a oneof { color = 1, gradient = 2 }; colours are 0-255 RGB(A)
//! - packets: UserCosmeticSetActive 716, SetProfileBackground 717, SetActiveVisible 718

use super::proto::{StringValue, UserUuid};
use crate::model::Fill;

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct RootColor {
    #[prost(uint32, tag = "1")]
    pub red: u32,
    #[prost(uint32, tag = "2")]
    pub green: u32,
    #[prost(uint32, tag = "3")]
    pub blue: u32,
    #[prost(uint32, tag = "4")]
    pub alpha: u32,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct GradientStop {
    #[prost(message, optional, tag = "1")]
    pub color: Option<RootColor>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct Gradient {
    #[prost(message, repeated, tag = "1")]
    pub stops: Vec<GradientStop>,
    /// degrees
    #[prost(double, tag = "2")]
    pub angle: f64,
}

/// oneof value { color = 1; gradient = 2 } — two optionals are wire-identical.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct CosmeticFill {
    #[prost(message, optional, tag = "1")]
    pub color: Option<RootColor>,
    #[prost(message, optional, tag = "2")]
    pub gradient: Option<Gradient>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct NameEffectMeta {
    #[prost(message, repeated, tag = "1")]
    pub fills: Vec<CosmeticFill>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct CosmeticUuid {
    #[prost(fixed64, tag = "1")]
    pub high64: u64,
    #[prost(fixed64, tag = "2")]
    pub low64: u64,
}

/// What other people see on you: shown next to your name everywhere.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ActiveVisibleCosmetic {
    #[prost(message, optional, tag = "1")]
    pub id: Option<CosmeticUuid>,
    #[prost(int32, tag = "2")]
    pub r#type: i32,
    #[prost(message, optional, tag = "13")]
    pub name_effect: Option<NameEffectMeta>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct UserCosmetic {
    #[prost(message, optional, tag = "1")]
    pub id: Option<CosmeticUuid>,
    #[prost(int32, tag = "2")]
    pub r#type: i32,
    #[prost(message, optional, tag = "4")]
    pub title: Option<StringValue>,
    #[prost(message, optional, tag = "5")]
    pub description: Option<StringValue>,
    #[prost(message, optional, tag = "13")]
    pub name_effect: Option<NameEffectMeta>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct ActiveCosmetics {
    #[prost(message, repeated, tag = "1")]
    pub cosmetics: Vec<UserCosmetic>,
    #[prost(message, optional, tag = "2")]
    pub profile_background: Option<CosmeticFill>,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct UserGetRequest {
    #[prost(message, optional, tag = "10")]
    pub user_id: Option<UserUuid>,
    #[prost(string, tag = "11")]
    pub language_code: String,
}

#[derive(Clone, PartialEq, ::prost::Message)]
pub struct UserGetResponse {
    #[prost(message, optional, tag = "10")]
    pub user_id: Option<UserUuid>,
    #[prost(message, optional, tag = "11")]
    pub profile_picture_asset_uri: Option<StringValue>,
    #[prost(string, tag = "12")]
    pub username: String,
    #[prost(int32, tag = "13")]
    pub online_status: i32,
    #[prost(bool, tag = "14")]
    pub is_deleted: bool,
    #[prost(message, optional, tag = "16")]
    pub description: Option<StringValue>,
    #[prost(message, optional, tag = "17")]
    pub banner_asset_uri: Option<StringValue>,
    #[prost(message, optional, tag = "18")]
    pub user_defined_status: Option<StringValue>,
    #[prost(message, optional, tag = "19")]
    pub active_cosmetics: Option<ActiveCosmetics>,
}

/// Live packet 718: someone changed the cosmetics everyone sees.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SetActiveVisiblePacket {
    #[prost(int32, tag = "1")]
    pub packet_type: i32,
    #[prost(message, optional, tag = "2")]
    pub user_id: Option<UserUuid>,
    #[prost(message, repeated, tag = "3")]
    pub active_visible_cosmetics: Vec<ActiveVisibleCosmetic>,
}

/// Live packet 717: someone changed their profile background.
#[derive(Clone, PartialEq, ::prost::Message)]
pub struct SetProfileBackgroundPacket {
    #[prost(int32, tag = "1")]
    pub packet_type: i32,
    #[prost(message, optional, tag = "2")]
    pub user_id: Option<UserUuid>,
    #[prost(message, optional, tag = "3")]
    pub profile_background: Option<CosmeticFill>,
}

pub const TYPE_AVATAR_FRAME: i32 = 1;
pub const TYPE_CARD_BORDER: i32 = 2;
pub const TYPE_NAME_FONT: i32 = 3;
pub const TYPE_NAME_EFFECT: i32 = 4;

pub const PACKET_SET_ACTIVE: u32 = 716;
pub const PACKET_SET_PROFILE_BACKGROUND: u32 = 717;
pub const PACKET_SET_ACTIVE_VISIBLE: u32 = 718;

fn argb(c: &RootColor) -> u32 {
    // proto3 leaves an unset alpha at 0; nobody picks an invisible colour on purpose
    let a = if c.alpha == 0 { 255 } else { c.alpha.min(255) };
    (a << 24) | (c.red.min(255) << 16) | (c.green.min(255) << 8) | c.blue.min(255)
}

/// Flattens a fill into colours + angle for the UI (solid = one colour).
pub fn fill(f: &CosmeticFill) -> Option<Fill> {
    if let Some(g) = &f.gradient {
        let colors: Vec<u32> = g.stops.iter().filter_map(|s| s.color.as_ref()).map(argb).collect();
        if !colors.is_empty() {
            return Some(Fill { colors, angle: g.angle as f32 });
        }
    }
    f.color.as_ref().map(|c| Fill { colors: vec![argb(c)], angle: 0.0 })
}

/// The name effect among someone's visible cosmetics, if they wear one.
pub fn name_effect(list: &[ActiveVisibleCosmetic]) -> Option<Fill> {
    list.iter()
        .filter(|c| c.r#type == TYPE_NAME_EFFECT)
        .filter_map(|c| c.name_effect.as_ref())
        .flat_map(|m| m.fills.iter())
        .find_map(fill)
}

pub fn type_name(t: i32) -> &'static str {
    match t {
        TYPE_AVATAR_FRAME => "Avatar frame",
        TYPE_CARD_BORDER => "Card border",
        TYPE_NAME_FONT => "Name font",
        TYPE_NAME_EFFECT => "Name effect",
        _ => "Cosmetic",
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use prost::Message;

    #[test]
    fn decodes_gradient_name_effect() {
        let c = |r, g, b| RootColor { red: r, green: g, blue: b, alpha: 0 };
        let v = ActiveVisibleCosmetic {
            id: Some(CosmeticUuid { high64: 1, low64: 2 }),
            r#type: TYPE_NAME_EFFECT,
            name_effect: Some(NameEffectMeta {
                fills: vec![CosmeticFill { color: None, gradient: Some(Gradient { stops: vec![GradientStop { color: Some(c(255, 0, 0)) }, GradientStop { color: Some(c(0, 0, 255)) }], angle: 90.0 }) }],
            }),
        };
        let bytes = v.encode_to_vec();
        let back = ActiveVisibleCosmetic::decode(bytes.as_slice()).unwrap();
        let f = name_effect(&[back]).unwrap();
        assert_eq!(f.colors, vec![0xFFFF0000, 0xFF0000FF]);
        assert_eq!(f.angle, 90.0);
    }

    #[test]
    fn solid_fill_and_other_types_ignored() {
        let frame = ActiveVisibleCosmetic { id: None, r#type: TYPE_AVATAR_FRAME, name_effect: None };
        assert!(name_effect(&[frame]).is_none());
        let f = fill(&CosmeticFill { color: Some(RootColor { red: 1, green: 2, blue: 3, alpha: 128 }), gradient: None }).unwrap();
        assert_eq!(f.colors, vec![0x80010203]);
    }
}
