//! Minimal gRPC-web client (binary `application/grpc-web+proto`) over HTTPS.
//!
//! Frame format (both directions): 1 flag byte + 4-byte big-endian length + payload.
//! Flag 0x00 = message, 0x80 = trailers ("grpc-status: N\r\ngrpc-message: ...").

use prost::Message;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct Status {
    pub code: u32,
    pub message: String,
    /// Root: set when the server wants a Cloudflare Turnstile challenge solved by the user.
    pub challenge_url: Option<String>,
}

impl std::fmt::Display for Status {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "gRPC {} ({}): {}", self.code, code_name(self.code), self.message)
    }
}

pub fn code_name(c: u32) -> &'static str {
    match c {
        0 => "OK",
        1 => "CANCELLED",
        2 => "UNKNOWN",
        3 => "INVALID_ARGUMENT",
        4 => "DEADLINE_EXCEEDED",
        5 => "NOT_FOUND",
        7 => "PERMISSION_DENIED",
        8 => "RESOURCE_EXHAUSTED",
        13 => "INTERNAL",
        14 => "UNAVAILABLE",
        16 => "UNAUTHENTICATED",
        _ => "?",
    }
}

#[derive(Debug)]
pub enum Error {
    Transport(String),
    Status(Status),
    Decode(String),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::Transport(s) => write!(f, "network: {s}"),
            Error::Status(s) => write!(f, "{s}"),
            Error::Decode(s) => write!(f, "decode: {s}"),
        }
    }
}

impl Error {
    pub fn is_unauthenticated(&self) -> bool {
        matches!(self, Error::Status(s) if s.code == 16)
    }
}

pub const CHALLENGE_HEADER: &str = "turnstile-challenge-url";

/// Response headers that matter for error handling.
#[derive(Debug, Default, Clone)]
pub struct Meta {
    pub challenge_url: Option<String>,
    /// Root's own error code (`root-exception-type`), e.g. 300 = RequestValidationFailed.
    pub root_error: Option<u32>,
}

impl Meta {
    fn from_resp(r: &ureq::Response) -> Self {
        Meta {
            challenge_url: r.header(CHALLENGE_HEADER).map(str::to_string),
            root_error: r.header("root-exception-type").and_then(|v| v.parse().ok()),
        }
    }
    fn merge_into(&self, s: &mut Status) {
        if s.challenge_url.is_none() {
            s.challenge_url = self.challenge_url.clone();
        }
        if let Some(code) = self.root_error {
            if s.message.is_empty() || s.message == "root-error" || s.message == "error" {
                s.message = format!("root error {code}");
            }
        }
    }
}

pub fn frame(msg: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(5 + msg.len());
    out.push(0);
    out.extend_from_slice(&(msg.len() as u32).to_be_bytes());
    out.extend_from_slice(msg);
    out
}

/// Splits a response body into message payloads and parses the trailer status.
pub fn unframe(body: &[u8]) -> Result<(Vec<Vec<u8>>, Option<Status>), Error> {
    let mut msgs = Vec::new();
    let mut status = None;
    let mut i = 0;
    while i + 5 <= body.len() {
        let flag = body[i];
        let len = u32::from_be_bytes([body[i + 1], body[i + 2], body[i + 3], body[i + 4]]) as usize;
        let start = i + 5;
        let end = start.checked_add(len).filter(|e| *e <= body.len()).ok_or_else(|| Error::Decode("truncated frame".into()))?;
        let payload = &body[start..end];
        if flag & 0x80 != 0 {
            status = Some(parse_trailers(&String::from_utf8_lossy(payload)));
        } else {
            msgs.push(payload.to_vec());
        }
        i = end;
    }
    Ok((msgs, status))
}

fn parse_trailers(t: &str) -> Status {
    let mut code = 0;
    let mut message = String::new();
    let mut challenge_url = None;
    for line in t.split("\r\n") {
        if let Some((k, v)) = line.split_once(':') {
            match k.trim().to_ascii_lowercase().as_str() {
                "grpc-status" => code = v.trim().parse().unwrap_or(2),
                "grpc-message" => message = percent_decode(v.trim()),
                CHALLENGE_HEADER => challenge_url = Some(v.trim().to_string()),
                _ => {}
            }
        }
    }
    Status { code, message, challenge_url }
}

fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(b[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

pub struct Client {
    agent: ureq::Agent,
    pub base: String,
    pub token: Option<String>,
    pub extra_headers: Vec<(String, String)>,
    /// If set, raw response bodies are written here (for schema verification while testing).
    pub dump_dir: Option<std::path::PathBuf>,
}

impl Client {
    pub fn new(base: &str) -> Self {
        let agent = ureq::AgentBuilder::new()
            .timeout_connect(Duration::from_secs(10))
            .timeout(Duration::from_secs(30))
            .build();
        Client { agent, base: base.trim_end_matches('/').to_string(), token: None, extra_headers: vec![], dump_dir: None }
    }

    /// Unary call: `/package.Service/Method`.
    pub fn unary<Req: Message, Resp: Message + Default>(&self, path: &str, req: &Req) -> Result<Resp, Error> {
        let (body, meta) = self.raw_with_meta(path, &req.encode_to_vec())?;
        let (msgs, status) = unframe(&body)?;
        if let Some(mut s) = status.filter(|s| s.code != 0) {
            meta.merge_into(&mut s);
            return Err(Error::Status(s));
        }
        let first = msgs.into_iter().next().unwrap_or_default();
        Resp::decode(first.as_slice()).map_err(|e| Error::Decode(format!("{path}: {e}")))
    }

    pub fn raw(&self, path: &str, msg: &[u8]) -> Result<Vec<u8>, Error> {
        let (body, meta) = self.raw_with_meta(path, msg)?;
        // Surface body-trailer errors here too, with header metadata attached.
        if let Ok((_, Some(mut s))) = unframe(&body) {
            if s.code != 0 {
                meta.merge_into(&mut s);
                return Err(Error::Status(s));
            }
        }
        Ok(body)
    }

    /// Like [raw] but also returns header metadata and never interprets the trailer.
    pub fn raw_with_meta(&self, path: &str, msg: &[u8]) -> Result<(Vec<u8>, Meta), Error> {
        let r = self.raw_with_meta_inner(path, msg);
        if let Err(e) = &r {
            crate::log::api(path, &e.to_string());
        }
        r
    }

    fn raw_with_meta_inner(&self, path: &str, msg: &[u8]) -> Result<(Vec<u8>, Meta), Error> {
        let body = frame(msg);
        let build = || {
            let mut r = self
                .agent
                .post(&format!("{}{}", self.base, path))
                .set("content-type", "application/grpc-web+proto")
                .set("accept", "application/grpc-web+proto")
                .set("x-grpc-web", "1");
            if let Some(t) = &self.token {
                r = r.set("authorization", &format!("Bearer {t}"));
            }
            for (k, v) in &self.extra_headers {
                r = r.set(k, v);
            }
            r
        };
        // Android kills idle sockets while the app is in the background; the pooled
        // connection then fails with "connection abort/reset". Retry on a fresh one.
        let mut attempt = 0;
        let sent = loop {
            let res = build().send_bytes(&body);
            match &res {
                Err(ureq::Error::Transport(t)) if attempt < 2 && {
                    let m = t.to_string().to_lowercase();
                    m.contains("abort") || m.contains("reset") || m.contains("broken pipe") || m.contains("os error 103") || m.contains("os error 104") || m.contains("unexpected eof") || m.contains("timed out")
                } => {
                    attempt += 1;
                    std::thread::sleep(std::time::Duration::from_millis(250 * attempt));
                }
                _ => break res,
            }
        };
        let resp = match sent {
            Ok(resp) => resp,
            Err(ureq::Error::Status(code, resp)) => {
                // gRPC errors can arrive as HTTP errors with grpc-status headers.
                let s = resp.header("grpc-status").and_then(|v| v.parse().ok()).unwrap_or(match code {
                    401 => 16,
                    403 => 7,
                    404 => 12,
                    429 => 8,
                    503 => 14,
                    _ => 2,
                });
                let m = resp.header("grpc-message").map(percent_decode).unwrap_or_else(|| format!("HTTP {code}"));
                let mut st = Status { code: s, message: m, challenge_url: None };
                Meta::from_resp(&resp).merge_into(&mut st);
                return Err(Error::Status(st));
            }
            Err(e) => return Err(Error::Transport(e.to_string())),
        };
        let meta = Meta::from_resp(&resp);
        // Trailers-only responses put the status in headers.
        if let Some(code) = resp.header("grpc-status").and_then(|v| v.parse::<u32>().ok()) {
            if code != 0 {
                let m = resp.header("grpc-message").map(percent_decode).unwrap_or_default();
                let mut s = Status { code, message: m, challenge_url: None };
                meta.merge_into(&mut s);
                return Err(Error::Status(s));
            }
        }
        let mut buf = Vec::new();
        std::io::Read::read_to_end(&mut resp.into_reader(), &mut buf).map_err(|e| Error::Transport(e.to_string()))?;
        if let Some(dir) = &self.dump_dir {
            let _ = std::fs::create_dir_all(dir);
            let name = path.trim_start_matches('/').replace('/', "__");
            let _ = std::fs::write(dir.join(format!("{name}.bin")), &buf);
        }
        Ok((buf, meta))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_roundtrip() {
        let mut body = frame(b"hello");
        let trailer = b"grpc-status: 16\r\ngrpc-message: token%20expired\r\n";
        body.push(0x80);
        body.extend_from_slice(&(trailer.len() as u32).to_be_bytes());
        body.extend_from_slice(trailer);
        let (msgs, st) = unframe(&body).unwrap();
        assert_eq!(msgs, vec![b"hello".to_vec()]);
        let st = st.unwrap();
        assert_eq!(st.code, 16);
        assert_eq!(st.message, "token expired");
        assert!(st.challenge_url.is_none());
    }
}
