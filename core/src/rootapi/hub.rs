//! Live events from Root's notifications hub (WebSocket, binary protobuf).
//!
//! Server → client frames are `ClientNotification { 2: sequence, 3: PacketContainer }`;
//! the container holds exactly one packet (message 170, deleted 171, reaction 173,
//! typing 174, DM created 120, user status 191, ping 1, ...). Client → server frames are
//! `HubNotification { 2: sequence, 4: PingPacket }`. Auth is the session's Bearer token.
//! A background thread keeps the socket open (with backoff) and queues raw packets.

use super::wire;
use std::collections::VecDeque;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tungstenite::{client::IntoClientRequest, Message};

/// One packet from the container: its field number and raw bytes.
pub struct Packet {
    pub kind: u32,
    pub body: Vec<u8>,
}

pub struct Hub {
    queue: Arc<Mutex<VecDeque<Packet>>>,
    connected: Arc<AtomicBool>,
    stop: Arc<AtomicBool>,
}

impl Hub {
    pub fn start(url: String, token: String) -> Hub {
        let queue = Arc::new(Mutex::new(VecDeque::new()));
        let connected = Arc::new(AtomicBool::new(false));
        let stop = Arc::new(AtomicBool::new(false));
        let (q, c, s) = (queue.clone(), connected.clone(), stop.clone());
        std::thread::Builder::new()
            .name("root-hub".into())
            .spawn(move || run(url, token, q, c, s))
            .ok();
        Hub { queue, connected, stop }
    }

    pub fn is_connected(&self) -> bool {
        self.connected.load(Ordering::Relaxed)
    }

    pub fn drain(&self) -> Vec<Packet> {
        self.queue.lock().map(|mut q| q.drain(..).collect()).unwrap_or_default()
    }
}

impl Drop for Hub {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

fn run(url: String, token: String, queue: Arc<Mutex<VecDeque<Packet>>>, connected: Arc<AtomicBool>, stop: Arc<AtomicBool>) {
    let mut backoff = 1u64;
    while !stop.load(Ordering::Relaxed) {
        let mut req = match url.as_str().into_client_request() {
            Ok(r) => r,
            Err(_) => return,
        };
        if let Ok(v) = format!("Bearer {token}").parse() {
            req.headers_mut().insert("authorization", v);
        }
        match tungstenite::connect(req) {
            Ok((mut ws, _)) => {
                if let tungstenite::stream::MaybeTlsStream::Rustls(t) = ws.get_mut() {
                    t.get_mut().set_read_timeout(Some(Duration::from_secs(1))).ok();
                }
                connected.store(true, Ordering::Relaxed);
                backoff = 1;
                let mut seq: i64 = 0;
                while !stop.load(Ordering::Relaxed) {
                    match ws.read() {
                        Ok(Message::Binary(b)) => {
                            for (t, v) in wire::fields(&b).unwrap_or_default() {
                                match (t, v) {
                                    (2, wire::Value::Varint(n)) => seq = n as i64,
                                    (3, wire::Value::Bytes(container)) => {
                                        for (kind, pv) in wire::fields(container).unwrap_or_default() {
                                            if let wire::Value::Bytes(body) = pv {
                                                if kind == 1 {
                                                    // server ping: answer with our own ping
                                                    let _ = ws.send(Message::Binary(ping_frame(seq).into()));
                                                } else if let Ok(mut q) = queue.lock() {
                                                    if q.len() > 5000 {
                                                        q.pop_front();
                                                    }
                                                    q.push_back(Packet { kind, body: body.to_vec() });
                                                }
                                            }
                                        }
                                    }
                                    _ => {}
                                }
                            }
                        }
                        Ok(Message::Close(_)) => break,
                        Ok(_) => {}
                        Err(tungstenite::Error::Io(e)) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {}
                        Err(_) => break,
                    }
                }
                connected.store(false, Ordering::Relaxed);
            }
            Err(_) => connected.store(false, Ordering::Relaxed),
        }
        // reconnect with backoff (1, 2, 4 ... 30 s)
        for _ in 0..backoff {
            if stop.load(Ordering::Relaxed) {
                return;
            }
            std::thread::sleep(Duration::from_secs(1));
        }
        backoff = (backoff * 2).min(30);
    }
}

/// `HubNotification { 2: seq, 4: PingPacket { 1: PACKET_TYPE_PING(1), 2: seq } }`
fn ping_frame(seq: i64) -> Vec<u8> {
    fn varint(mut v: u64, out: &mut Vec<u8>) {
        loop {
            let b = (v & 0x7f) as u8;
            v >>= 7;
            if v == 0 {
                out.push(b);
                break;
            }
            out.push(b | 0x80);
        }
    }
    let mut ping = Vec::new();
    ping.push(0x08); // field 1 varint
    varint(1, &mut ping);
    ping.push(0x10); // field 2 varint
    varint(seq as u64, &mut ping);
    let mut out = Vec::new();
    out.push(0x10); // field 2 varint
    varint(seq as u64, &mut out);
    out.push(0x22); // field 4 length-delimited
    varint(ping.len() as u64, &mut out);
    out.extend_from_slice(&ping);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ping_frame_is_valid_protobuf() {
        let f = ping_frame(42);
        let fs = wire::fields(&f).unwrap();
        assert!(matches!(fs[0], (2, wire::Value::Varint(42))));
        assert!(matches!(fs[1], (4, wire::Value::Bytes(_))));
    }
}
