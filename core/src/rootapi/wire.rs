//! Schema-tolerant protobuf helpers.
//!
//! Root's list responses wrap items in envelopes whose exact layout we only
//! partly recovered. Instead of depending on the envelope, `find_all` walks the
//! raw wire format and returns every embedded message that decodes cleanly as
//! `T` and passes `accept` (e.g. "has an id and a name"). prost rejects wire-type
//! mismatches, which keeps false positives out.

use prost::Message;

#[derive(Debug)]
pub enum Value<'a> {
    Varint(u64),
    Fixed64(u64),
    Fixed32(u32),
    Bytes(&'a [u8]),
}

fn varint(buf: &[u8], i: &mut usize) -> Option<u64> {
    let mut out = 0u64;
    for shift in (0..64).step_by(7) {
        let b = *buf.get(*i)?;
        *i += 1;
        out |= ((b & 0x7f) as u64) << shift;
        if b & 0x80 == 0 {
            return Some(out);
        }
    }
    None
}

/// Parses one level of fields. Returns None if `buf` isn't a well-formed message.
pub fn fields(buf: &[u8]) -> Option<Vec<(u32, Value<'_>)>> {
    let mut i = 0;
    let mut out = Vec::new();
    while i < buf.len() {
        let key = varint(buf, &mut i)?;
        let tag = (key >> 3) as u32;
        if tag == 0 {
            return None;
        }
        let v = match key & 7 {
            0 => Value::Varint(varint(buf, &mut i)?),
            1 => {
                let b = buf.get(i..i + 8)?;
                i += 8;
                Value::Fixed64(u64::from_le_bytes(b.try_into().ok()?))
            }
            2 => {
                let len = varint(buf, &mut i)? as usize;
                let b = buf.get(i..i.checked_add(len)?)?;
                i += len;
                Value::Bytes(b)
            }
            5 => {
                let b = buf.get(i..i + 4)?;
                i += 4;
                Value::Fixed32(u32::from_le_bytes(b.try_into().ok()?))
            }
            _ => return None,
        };
        out.push((tag, v));
    }
    Some(out)
}

/// Every embedded message (at any depth) that decodes as `T` and is accepted.
/// The top-level buffer itself is not considered.
pub fn find_all<T: Message + Default>(buf: &[u8], accept: impl Fn(&T) -> bool + Copy) -> Vec<T> {
    let mut out = Vec::new();
    walk(buf, accept, &mut out, 0);
    out
}

fn walk<T: Message + Default>(buf: &[u8], accept: impl Fn(&T) -> bool + Copy, out: &mut Vec<T>, depth: usize) {
    if depth > 8 {
        return;
    }
    let Some(fs) = fields(buf) else { return };
    for (_, v) in fs {
        if let Value::Bytes(b) = v {
            if b.is_empty() {
                continue;
            }
            match T::decode(b) {
                Ok(t) if accept(&t) => out.push(t),
                _ => walk(b, accept, out, depth + 1),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Clone, PartialEq, prost::Message)]
    struct Item {
        #[prost(uint64, tag = "1")]
        id: u64,
        #[prost(string, tag = "2")]
        name: String,
    }
    #[derive(Clone, PartialEq, prost::Message)]
    struct Inner {
        #[prost(message, repeated, tag = "7")]
        items: Vec<Item>,
    }
    #[derive(Clone, PartialEq, prost::Message)]
    struct Envelope {
        #[prost(message, optional, tag = "5")]
        inner: Option<Inner>,
        #[prost(string, tag = "9")]
        note: String,
    }

    #[test]
    fn finds_items_inside_unknown_envelope() {
        let env = Envelope {
            inner: Some(Inner { items: vec![Item { id: 1, name: "a".into() }, Item { id: 2, name: "b".into() }] }),
            note: "x".into(),
        };
        let found: Vec<Item> = find_all(&env.encode_to_vec(), |i: &Item| i.id > 0 && !i.name.is_empty());
        assert_eq!(found.iter().map(|i| i.id).collect::<Vec<_>>(), vec![1, 2]);
    }
}
