//! Text transforms used by send-side plugins. Hand-written (no regex crate)
//! to keep the native library small and fast.

use serde_json::{Map, Value};

const KAOMOJI: &[(&str, &str)] = &[
    ("/shrug", "¯\\_(ツ)_/¯"),
    ("/tableflip", "(╯°□°)╯︵ ┻━┻"),
    ("/unflip", "┬─┬ノ( º _ ºノ)"),
    ("/lenny", "( ͡° ͜ʖ ͡°)"),
    ("/disapprove", "ಠ_ಠ"),
    ("/happy", "(◕‿◕)"),
    ("/hug", "(づ｡◕‿‿◕｡)づ"),
    ("/sparkle", "(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧"),
    ("/cry", "(╥﹏╥)"),
    ("/bear", "ʕ•ᴥ•ʔ"),
];

pub fn kaomoji(s: &str) -> String {
    map_words(s, |w| KAOMOJI.iter().find(|(k, _)| *k == w).map(|(_, v)| v.to_string()))
}

/// Replaces `{{name}}` with the saved tag text (unknown tags are left alone).
pub fn expand_tags(s: &str, tags: &Map<String, Value>) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(start) = rest.find("{{") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        match after.find("}}") {
            Some(end) => {
                let name = after[..end].trim();
                match tags.get(name).and_then(Value::as_str) {
                    Some(t) => out.push_str(t),
                    None => out.push_str(&rest[start..start + 2 + end + 2]),
                }
                rest = &after[end + 2..];
            }
            None => {
                out.push_str(&rest[start..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

/// Strips the common leading indentation inside ``` fenced blocks.
pub fn unindent_code_blocks(s: &str) -> String {
    let parts: Vec<&str> = s.split("```").collect();
    if parts.len() < 3 {
        return s.to_string();
    }
    let mut out = Vec::with_capacity(parts.len());
    for (i, part) in parts.iter().enumerate() {
        // Odd indices are inside a fence (if the fence is closed).
        if i % 2 == 1 && i + 1 < parts.len() {
            out.push(unindent(part));
        } else {
            out.push(part.to_string());
        }
    }
    out.join("```")
}

fn unindent(block: &str) -> String {
    let indent = block
        .lines()
        .skip(1) // first line is the language tag
        .filter(|l| !l.trim().is_empty())
        .map(|l| l.len() - l.trim_start().len())
        .min()
        .unwrap_or(0);
    if indent == 0 {
        return block.to_string();
    }
    let mut lines = block.split('\n');
    let mut out = lines.next().unwrap_or("").to_string();
    for l in lines {
        out.push('\n');
        out.push_str(if l.len() >= indent && l[..indent].trim().is_empty() { &l[indent..] } else { l.trim_start() });
    }
    out
}

/// `[14:30]`, `[2:30pm]` → `<t:UNIX:t>` for today in the sender's timezone.
pub fn bracket_times(s: &str, now_unix: i64, tz_offset_min: i32) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(start) = rest.find('[') {
        out.push_str(&rest[..start]);
        let after = &rest[start + 1..];
        if let Some(end) = after.find(']') {
            if let Some((h, m)) = parse_clock(&after[..end]) {
                let tz = tz_offset_min as i64 * 60;
                let local_now = now_unix + tz;
                let day_start = local_now - local_now.rem_euclid(86_400);
                let ts = day_start + h * 3600 + m * 60 - tz;
                out.push_str(&format!("<t:{ts}:t>"));
                rest = &after[end + 1..];
                continue;
            }
        }
        out.push('[');
        rest = after;
    }
    out.push_str(rest);
    out
}

fn parse_clock(t: &str) -> Option<(i64, i64)> {
    let t = t.trim().to_ascii_lowercase();
    let (body, pm, am) = if let Some(b) = t.strip_suffix("pm") {
        (b.trim().to_string(), true, false)
    } else if let Some(b) = t.strip_suffix("am") {
        (b.trim().to_string(), false, true)
    } else {
        (t.clone(), false, false)
    };
    let (h, m) = body.split_once(':')?;
    if h.is_empty() || h.len() > 2 || m.len() != 2 {
        return None;
    }
    let mut h: i64 = h.parse().ok()?;
    let m: i64 = m.parse().ok()?;
    if m > 59 {
        return None;
    }
    if pm || am {
        if !(1..=12).contains(&h) {
            return None;
        }
        h %= 12;
        if pm {
            h += 12;
        }
    } else if h > 23 {
        return None;
    }
    Some((h, m))
}

const TRACKERS: &[&str] = &[
    "fbclid", "gclid", "dclid", "gbraid", "wbraid", "msclkid", "mc_cid", "mc_eid", "igshid", "igsh", "si",
    "ref_src", "ref_url", "yclid", "_hsenc", "_hsmi", "mkt_tok", "s_cid", "spm", "share_id", "feature",
    "trk", "trackingId", "pp", "t",
];

/// Drops tracking query parameters from http(s) links.
pub fn clear_urls(s: &str) -> String {
    map_words(s, |w| {
        if !(w.starts_with("https://") || w.starts_with("http://")) {
            return None;
        }
        let (url, frag) = match w.split_once('#') {
            Some((u, f)) => (u, Some(f)),
            None => (w, None),
        };
        let (base, query) = url.split_once('?')?;
        // `t` and `feature` are tracking on YouTube/X but meaningful elsewhere (timestamps).
        let host = base.split('/').nth(2).unwrap_or("");
        let social = ["youtube.com", "youtu.be", "x.com", "twitter.com", "spotify.com", "instagram.com"]
            .iter()
            .any(|d| host.ends_with(d));
        let kept: Vec<&str> = query
            .split('&')
            .filter(|kv| {
                let k = kv.split('=').next().unwrap_or("");
                if k.is_empty() || k.starts_with("utm_") {
                    return false;
                }
                let generic = TRACKERS.contains(&k) && !matches!(k, "t" | "feature" | "pp" | "si");
                let social_only = social && matches!(k, "feature" | "pp" | "si") || (host.ends_with("x.com") || host.ends_with("twitter.com")) && k == "t";
                !(generic || social_only)
            })
            .collect();
        let mut out = base.to_string();
        if !kept.is_empty() {
            out.push('?');
            out.push_str(&kept.join("&"));
        }
        if let Some(f) = frag {
            out.push('#');
            out.push_str(f);
        }
        (out != w).then_some(out)
    })
}

/// Applies `f` to each whitespace-separated word, preserving all whitespace.
fn map_words(s: &str, f: impl Fn(&str) -> Option<String>) -> String {
    let mut out = String::with_capacity(s.len());
    let mut word_start: Option<usize> = None;
    for (i, c) in s.char_indices() {
        if c.is_whitespace() {
            if let Some(ws) = word_start.take() {
                let w = &s[ws..i];
                out.push_str(&f(w).unwrap_or_else(|| w.to_string()));
            }
            out.push(c);
        } else if word_start.is_none() {
            word_start = Some(i);
        }
    }
    if let Some(ws) = word_start {
        let w = &s[ws..];
        out.push_str(&f(w).unwrap_or_else(|| w.to_string()));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clears_tracking() {
        assert_eq!(
            clear_urls("look https://a.com/x?utm_source=z&id=5&fbclid=q#top ok"),
            "look https://a.com/x?id=5#top ok"
        );
        assert_eq!(clear_urls("https://youtu.be/abc?si=xyz&t=42"), "https://youtu.be/abc?t=42");
        assert_eq!(clear_urls("https://example.com/p?t=3"), "https://example.com/p?t=3");
    }

    #[test]
    fn kaomoji_and_tags() {
        assert_eq!(kaomoji("ok /shrug"), "ok ¯\\_(ツ)_/¯");
        let tags: Map<String, Value> = serde_json::from_str(r#"{"gm":"good morning"}"#).unwrap();
        assert_eq!(expand_tags("{{gm}} all {{nope}}", &tags), "good morning all {{nope}}");
    }

    #[test]
    fn times() {
        // 2026-01-01T00:00:00Z = 1767225600, UTC+2
        let now = 1767225600 + 3600;
        assert_eq!(bracket_times("at [14:30]!", now, 120), format!("at <t:{}:t>!", 1767225600 + 12 * 3600 + 1800));
        assert_eq!(bracket_times("[2:30pm]", now, 0), format!("<t:{}:t>", 1767225600 + 14 * 3600 + 1800));
        assert_eq!(bracket_times("[link] [99:00]", now, 0), "[link] [99:00]");
    }

    #[test]
    fn unindents() {
        let s = "```rs\n    fn a() {\n        1\n    }\n```";
        assert_eq!(unindent_code_blocks(s), "```rs\nfn a() {\n    1\n}\n```");
    }
}
