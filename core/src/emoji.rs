//! Unicode emoji shortcodes (`:sob:` → 😭), from GitHub's gemoji (MIT, see data/GEMOJI_LICENSE).
//! Also feeds the emoji panel (grouped by category).

use serde::Serialize;
use std::collections::HashMap;
use std::sync::OnceLock;

const TABLE: &str = include_str!("../data/emoji.tsv");

#[derive(Serialize, Clone)]
pub struct EmojiEntry {
    pub emoji: &'static str,
    pub names: Vec<&'static str>,
    pub category: &'static str,
    #[serde(skip)]
    pub desc: &'static str,
}

fn entries() -> &'static Vec<EmojiEntry> {
    static E: OnceLock<Vec<EmojiEntry>> = OnceLock::new();
    E.get_or_init(|| {
        TABLE
            .lines()
            .filter_map(|l| {
                let mut p = l.split('\t');
                let emoji = p.next()?;
                let names = p.next()?.split(',').filter(|n| !n.is_empty()).collect();
                let category = p.next().unwrap_or("");
                let desc = p.next().unwrap_or("");
                Some(EmojiEntry { emoji, names, category, desc })
            })
            .collect()
    })
}

fn by_name() -> &'static HashMap<&'static str, &'static str> {
    static M: OnceLock<HashMap<&'static str, &'static str>> = OnceLock::new();
    M.get_or_init(|| entries().iter().flat_map(|e| e.names.iter().map(move |n| (*n, e.emoji))).collect())
}

pub fn lookup(name: &str) -> Option<&'static str> {
    by_name().get(name).copied()
}

/// Replaces `:name:` with the emoji where `name` is known; leaves everything else alone.
pub fn replace_shortcodes(s: &str) -> String {
    if !s.contains(':') {
        return s.to_string();
    }
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(start) = rest.find(':') {
        out.push_str(&rest[..start]);
        let after = &rest[start + 1..];
        match after.find(':') {
            Some(end) if end > 0 && end <= 40 && after[..end].chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '+' || c == '-') => {
                match lookup(&after[..end]) {
                    Some(e) => {
                        out.push_str(e);
                        rest = &after[end + 1..];
                    }
                    None => {
                        // not an emoji: keep the colon and continue from the next char
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

/// JoyPixels/Discord-style names Root uses that gemoji spells differently.
const ALIASES: &[(&str, &str)] = &[
    ("slight_smile", "slightly_smiling_face"), ("slight_frown", "slightly_frowning_face"), ("frowning2", "frowning_face"),
    ("upside_down", "upside_down_face"), ("hugging", "hugs"), ("thinking", "thinking"), ("rolling_eyes", "roll_eyes"),
    ("nerd", "nerd_face"), ("robot", "robot"), ("money_mouth", "money_mouth_face"), ("zipper_mouth", "zipper_mouth_face"),
    ("head_bandage", "face_with_head_bandage"), ("thermometer_face", "face_with_thermometer"), ("rofl", "rofl"),
    ("heart_eyes", "heart_eyes"), ("smiling_face_with_3_hearts", "smiling_face_with_three_hearts"), ("relaxed", "relaxed"),
    ("hand_splayed", "raised_hand_with_fingers_splayed"), ("metal", "metal"), ("vulcan", "vulcan_salute"),
    ("speaking_head", "speaking_head"), ("skull_crossbones", "skull_and_crossbones"), ("heart_exclamation", "heavy_heart_exclamation"),
    ("cowboy", "cowboy_hat_face"), ("clown", "clown_face"), ("lying_face", "lying_face"), ("drooling_face", "drooling_face"),
    ("face_with_raised_eyebrow", "raised_eyebrow"), ("star_struck", "star_struck"), ("exploding_head", "exploding_head"),
    ("face_vomiting", "vomiting_face"), ("shushing_face", "shushing_face"), ("face_with_monocle", "monocle_face"),
    ("hot_face", "hot_face"), ("cold_face", "cold_face"), ("woozy_face", "woozy_face"), ("partying_face", "partying_face"),
    ("white_heart", "white_heart"), ("brown_heart", "brown_heart"), ("black_heart", "black_heart"),
    ("thumbup", "+1"), ("thumbsdown", "-1"), ("thumbdown", "-1"), ("ok_hand", "ok_hand"), ("pinching_hand", "pinching_hand"),
    ("eye_in_speech_bubble", "eye_speech_bubble"), ("flag_white", "white_flag"), ("flag_black", "black_flag"),
];

/// Root (JoyPixels) names mostly equal the Unicode names: "woman_in_lotus_position".
fn by_desc(name: &str) -> Option<&'static str> {
    static M: OnceLock<HashMap<String, &'static str>> = OnceLock::new();
    let m = M.get_or_init(|| {
        entries().iter().filter(|e| !e.desc.is_empty()).map(|e| {
            let k: String = e.desc.to_lowercase().chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '_' }).collect();
            (k.split('_').filter(|x| !x.is_empty()).collect::<Vec<_>>().join("_"), e.emoji)
        }).collect()
    });
    let n = name.to_lowercase();
    m.get(&n).copied()
        .or_else(|| m.get(&format!("{n}_face")).copied())
        .or_else(|| n.strip_suffix("_face").and_then(|b| m.get(b).copied()))
        // "person_x" ↔ "x", "woman_x" ↔ "x_woman"
        .or_else(|| n.strip_prefix("person_").and_then(|b| m.get(b).copied()))
}

/// Root's ":name:" (optionally "_toneN") → the emoji character(s).
pub fn from_root_name(name: &str) -> Option<String> {
    let (base, tone) = match name.rsplit_once("_tone") {
        Some((b, t)) if t.len() == 1 && ("1"..="5").contains(&t) => (b, Some(t.parse::<u32>().ok()?)),
        _ => (name, None),
    };
    let e = lookup(base)
        .or_else(|| ALIASES.iter().find(|(a, _)| *a == base).and_then(|(_, g)| lookup(g)))
        .or_else(|| by_desc(base))?;
    let mut out = e.to_string();
    if let Some(t) = tone {
        // skin tone modifier goes after the first code point (before any ZWJ sequence)
        let modifier = char::from_u32(0x1F3FA + t)?;
        let mut cs = out.chars();
        let first = cs.next()?;
        let rest: String = cs.filter(|c| *c != '\u{FE0F}').collect();
        out = format!("{first}{modifier}{rest}");
    }
    Some(out)
}

/// The emoji character → a name Root understands (":name:"), if we know one.
pub fn root_name(e: &str) -> Option<&'static str> {
    let t = e.trim_end_matches('\u{FE0F}');
    entries().iter().find(|x| x.emoji == e || x.emoji.trim_end_matches('\u{FE0F}') == t).and_then(|x| x.names.first().copied())
}

fn by_emoji() -> &'static HashMap<&'static str, ()> {
    static M: OnceLock<HashMap<&'static str, ()>> = OnceLock::new();
    M.get_or_init(|| entries().iter().map(|e| (e.emoji, ())).collect())
}

/// Code-point id Root uses for unicode emoji: lowercase hex joined by '-', without FE0F.
pub fn hex_id(e: &str) -> String {
    e.chars().filter(|c| *c != '\u{FE0F}').map(|c| format!("{:x}", c as u32)).collect::<Vec<_>>().join("-")
}

/// Wraps each unicode emoji as `[emoji](root://emoji/<hex>)` (longest match first),
/// leaving existing markdown links untouched.
pub fn encode_for_root(s: &str) -> String {
    let chars: Vec<(usize, char)> = s.char_indices().collect();
    let mut out = String::with_capacity(s.len() + 16);
    let mut i = 0;
    let mut in_link = 0usize; // inside [...](...) we don't touch anything
    while i < chars.len() {
        let (pos, c) = chars[i];
        if c == '[' || c == '(' { in_link += 1; }
        if (c == ']' || c == ')') && in_link > 0 { in_link -= 1; }
        if in_link == 0 && !c.is_ascii() {
            // try the longest known emoji sequence starting here (up to 10 code points)
            let mut matched = None;
            for len in (1..=10.min(chars.len() - i)).rev() {
                let end = if i + len < chars.len() { chars[i + len].0 } else { s.len() };
                let cand = &s[pos..end];
                if by_emoji().contains_key(cand) || by_emoji().contains_key(cand.trim_end_matches('\u{FE0F}')) {
                    matched = Some((cand, len));
                    break;
                }
            }
            if let Some((e, len)) = matched {
                // Root's editor writes [:name:](root://emoji/:name:)
                match root_name(e) {
                    Some(n) => out.push_str(&format!("[:{n}:](root://emoji/:{n}:)")),
                    None => out.push_str(e),
                }
                i += len;
                continue;
            }
        }
        out.push(c);
        i += 1;
    }
    out
}

/// All emoji grouped by category, in gemoji order (for the panel).
pub fn catalog() -> &'static Vec<EmojiEntry> {
    entries()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn shortcodes() {
        assert_eq!(replace_shortcodes(":sob::sob: ok :ninja:"), "😭😭 ok 🥷");
        assert_eq!(replace_shortcodes("time 10:30 :notanemoji: :+1:"), "time 10:30 :notanemoji: 👍");
        assert!(catalog().len() > 1500);
        assert_eq!(encode_for_root("hi 😭!"), "hi [:sob:](root://emoji/:sob:)!");
        assert_eq!(from_root_name("slight_smile").as_deref(), Some("🙂"));
        assert_eq!(from_root_name("thumbsup_tone2").as_deref(), Some("👍🏼"));
        assert!(from_root_name("woman_in_lotus_position_tone2").is_some());
        assert!(from_root_name("frowning2").is_some());
        assert_eq!(encode_for_root("[x](root://emoji/abc) ok"), "[x](root://emoji/abc) ok");
        assert_eq!(hex_id("❤️"), "2764");
    }
}
