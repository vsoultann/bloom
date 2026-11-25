//! GIF search/trending via Klipy, with the user's own API key (Settings → GIFs).
//! The response is parsed defensively: every item with a `file` object of size
//! variants yields a small preview URL and a larger URL to send.

use serde_json::{json, Value};

pub fn search(key: &str, query: &str) -> Result<Value, String> {
    let base = format!("https://api.klipy.com/api/v1/{}/gifs", urlencode(key));
    let url = if query.is_empty() {
        format!("{base}/trending?page=1&per_page=30&customer_id=bloom")
    } else {
        format!("{base}/search?q={}&page=1&per_page=30&customer_id=bloom", urlencode(query))
    };
    let body: Value = match ureq::get(&url).set("user-agent", crate::rootapi::USER_AGENT).call() {
        Ok(r) => serde_json::from_str(&r.into_string().map_err(|e| e.to_string())?).map_err(|e| e.to_string())?,
        Err(ureq::Error::Status(401 | 403, _)) => return Err("Your GIF API key was rejected".into()),
        Err(e) => return Err(format!("GIF search failed: {e}")),
    };
    let mut out = Vec::new();
    collect(&body, &mut out, 0);
    Ok(Value::Array(out))
}

fn collect(v: &Value, out: &mut Vec<Value>, depth: usize) {
    if depth > 8 || out.len() >= 60 {
        return;
    }
    match v {
        Value::Object(o) => {
            if let Some(file) = o.get("file").and_then(Value::as_object) {
                let pick = |sizes: &[&str], fmts: &[&str]| -> Option<(String, u64, u64)> {
                    for s in sizes {
                        for f in fmts {
                            if let Some(x) = file.get(*s).and_then(|z| z.get(*f)) {
                                if let Some(u) = x.get("url").and_then(Value::as_str) {
                                    return Some((u.to_string(), x.get("width").and_then(Value::as_u64).unwrap_or(0), x.get("height").and_then(Value::as_u64).unwrap_or(0)));
                                }
                            }
                        }
                    }
                    None
                };
                let preview = pick(&["sm", "xs", "md"], &["webp", "gif"]);
                let full = pick(&["md", "hd", "sm"], &["gif", "webp"]);
                if let (Some(p), Some(f)) = (preview, full) {
                    out.push(json!({ "preview": p.0, "url": f.0, "width": p.1, "height": p.2,
                        "title": o.get("title").and_then(Value::as_str).unwrap_or("") }));
                    return;
                }
            }
            for x in o.values() {
                collect(x, out, depth + 1);
            }
        }
        Value::Array(a) => a.iter().for_each(|x| collect(x, out, depth + 1)),
        _ => {}
    }
}

fn urlencode(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            b' ' => "+".into(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_nested_items() {
        let v = json!({"data": {"data": [{"title": "cat", "file": {"sm": {"webp": {"url": "https://x/s.webp", "width": 100, "height": 80}}, "md": {"gif": {"url": "https://x/m.gif"}}}}]}});
        let mut out = vec![];
        collect(&v, &mut out, 0);
        assert_eq!(out[0]["preview"], "https://x/s.webp");
        assert_eq!(out[0]["url"], "https://x/m.gif");
    }
}
