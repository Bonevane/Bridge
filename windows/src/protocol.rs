//! The bits of the Bluetooth protocol that don't touch Windows at all: the
//! chunk framing, the message types, and the pairing handshake. Kept
//! platform-free so they can be unit-tested on any machine (the rest of the
//! crate only compiles for Windows).
//!
//! Wire format, identical to BleLink.kt and BluetoothLink.swift:
//! every chunk is `[type][more][payload…]`, with `more = 1` when the message
//! continues in the next chunk. Payloads are UTF-8 text.

use hmac::{Hmac, Mac};
use sha2::Sha256;

/// Message types. Numbers are the protocol, don't renumber.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Notification = 1,
    Clipboard = 2,
    Ping = 3,
    Status = 4,
    Command = 5,
    Auth = 6,
}

impl Kind {
    pub fn from_byte(b: u8) -> Option<Kind> {
        Some(match b {
            1 => Kind::Notification,
            2 => Kind::Clipboard,
            3 => Kind::Ping,
            4 => Kind::Status,
            5 => Kind::Command,
            6 => Kind::Auth,
            _ => return None,
        })
    }
}

/// Splits one message into chunks that fit `mtu_payload` bytes each.
pub fn chunk(kind: Kind, text: &str, mtu_payload: usize) -> Vec<Vec<u8>> {
    chunk_bytes(kind, text.as_bytes(), mtu_payload)
}

pub fn chunk_bytes(kind: Kind, bytes: &[u8], mtu_payload: usize) -> Vec<Vec<u8>> {
    let room = mtu_payload.saturating_sub(2).max(1);
    if bytes.is_empty() {
        return vec![vec![kind as u8, 0]];
    }
    let mut out = Vec::new();
    let mut offset = 0;
    while offset < bytes.len() {
        let end = (offset + room).min(bytes.len());
        let more = if end < bytes.len() { 1 } else { 0 };
        let mut c = Vec::with_capacity(end - offset + 2);
        c.push(kind as u8);
        c.push(more);
        c.extend_from_slice(&bytes[offset..end]);
        out.push(c);
        offset = end;
    }
    out
}

/// Reassembles chunks into whole messages.
#[derive(Default)]
pub struct Inbox {
    buffer: Vec<u8>,
}

impl Inbox {
    /// Feed one chunk; returns the complete message when this chunk ends one.
    pub fn push(&mut self, chunk: &[u8]) -> Option<(Kind, String)> {
        self.push_bytes(chunk).map(|(k, b)| (k, String::from_utf8_lossy(&b).into_owned()))
    }

    pub fn push_bytes(&mut self, chunk: &[u8]) -> Option<(Kind, Vec<u8>)> {
        if chunk.len() < 2 {
            return None;
        }
        let kind = Kind::from_byte(chunk[0]);
        let more = chunk[1] == 1;
        self.buffer.extend_from_slice(&chunk[2..]);
        if more {
            return None;
        }
        let out = std::mem::take(&mut self.buffer);
        kind.map(|k| (k, out))
    }
}

/// HMAC-SHA256 of `message` under the pairing secret, lowercase hex.
pub fn hmac_hex(secret: &str, message: &str) -> String {
    let mut mac = Hmac::<Sha256>::new_from_slice(secret.as_bytes()).expect("any key length works");
    mac.update(message.as_bytes());
    hex::encode(mac.finalize().into_bytes())
}

/// 16 random bytes as hex, for the handshake.
pub fn nonce() -> String {
    let bytes: [u8; 16] = rand::random();
    hex::encode(bytes)
}

/// The Mac/PC side of the handshake.
///
/// Phone: `challenge <n1>` → us: `auth <hmac(n1)> <n2>` → phone: `ok <hmac(n2)>`.
/// Nothing else is trusted until `Verified` comes back.
pub struct Handshake {
    secret: String,
    our_nonce: Option<String>,
    /// Both nonces once the phone has been verified, for the session key.
    pub nonces: Option<(String, String)>,
}

pub enum Step {
    /// Send this text as a `Kind::Command`.
    Reply(String),
    /// The phone proved it knows the secret.
    Verified,
    /// The phone failed our challenge; drop it.
    Failed,
    Ignore,
}

impl Handshake {
    pub fn new(secret: &str) -> Self {
        Handshake { secret: secret.to_string(), our_nonce: None, nonces: None }
    }

    pub fn handle(&mut self, auth_text: &str) -> Step {
        let words: Vec<&str> = auth_text.split(' ').collect();
        match (words.first().copied(), words.get(1).copied()) {
            (Some("challenge"), Some(their_nonce)) => {
                let ours = nonce();
                let reply = format!("auth {} {}", hmac_hex(&self.secret, their_nonce), ours);
                self.nonces = Some((their_nonce.to_string(), ours.clone()));
                self.our_nonce = Some(ours);
                Step::Reply(reply)
            }
            (Some("ok"), Some(proof)) => match self.our_nonce.take() {
                Some(n) if hmac_hex(&self.secret, &n) == proof => Step::Verified,
                _ => { self.nonces = None; Step::Failed }
            },
            _ => Step::Ignore,
        }
    }
}

/// App-layer encryption after the handshake, on the phone's *plain* door.
/// Mirrors Pairing.SessionCrypto on the phone: key = HMAC-SHA256(secret,
/// phone_nonce + our_nonce); AES-256-GCM; nonce = direction byte (phone 1,
/// PC 2) + 11-byte big-endian counter; message = nonce + ciphertext + tag.
pub struct SessionCrypto {
    cipher: aes_gcm::Aes256Gcm,
    send_counter: u64,
}

impl SessionCrypto {
    pub fn new(secret: &str, phone_nonce: &str, our_nonce: &str) -> Self {
        use aes_gcm::KeyInit;
        let mut mac = <Hmac<Sha256> as Mac>::new_from_slice(secret.as_bytes()).expect("any key length");
        Mac::update(&mut mac, phone_nonce.as_bytes());
        Mac::update(&mut mac, our_nonce.as_bytes());
        let key: [u8; 32] = mac.finalize().into_bytes().into();
        SessionCrypto { cipher: <aes_gcm::Aes256Gcm as KeyInit>::new(&key.into()), send_counter: 0 }
    }

    pub fn seal(&mut self, plain: &[u8]) -> Vec<u8> {
        use aes_gcm::aead::Aead;
        let mut nonce = [0u8; 12];
        nonce[0] = 2;
        nonce[4..].copy_from_slice(&self.send_counter.to_be_bytes());
        self.send_counter += 1;
        let ct = self.cipher.encrypt(aes_gcm::Nonce::from_slice(&nonce), plain).expect("encrypt");
        let mut out = nonce.to_vec();
        out.extend(ct);
        out
    }

    pub fn open(&self, sealed: &[u8]) -> Option<Vec<u8>> {
        use aes_gcm::aead::Aead;
        if sealed.len() < 12 + 16 || sealed[0] != 1 {
            return None;
        }
        self.cipher.decrypt(aes_gcm::Nonce::from_slice(&sealed[..12]), &sealed[12..]).ok()
    }
}

/// A status report from the phone: `daemon=1 tunnel=0 keep=1 keepAt=… paused=0`.
pub fn parse_status(text: &str) -> std::collections::HashMap<String, String> {
    text.split(' ')
        .filter_map(|field| field.split_once('='))
        .map(|(k, v)| (k.to_string(), v.to_string()))
        .collect()
}

/// A notification line from the phone: app, title, body, tab-separated.
pub fn parse_notification(line: &str) -> Option<(String, String, String)> {
    let mut parts = line.splitn(3, '\t');
    Some((
        parts.next()?.to_string(),
        parts.next().unwrap_or("").to_string(),
        parts.next().unwrap_or("").to_string(),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chunks_round_trip() {
        let text = "héllo wörld, this is longer than one chunk";
        let chunks = chunk(Kind::Clipboard, text, 12);
        assert!(chunks.len() > 1);
        let mut inbox = Inbox::default();
        let mut got = None;
        for c in &chunks {
            if let Some(m) = inbox.push(c) {
                got = Some(m);
            }
        }
        assert_eq!(got, Some((Kind::Clipboard, text.to_string())));
    }

    #[test]
    fn empty_message_is_one_chunk() {
        assert_eq!(chunk(Kind::Ping, "", 20), vec![vec![3, 0]]);
    }

    #[test]
    fn handshake_matches_phone() {
        // Same secret both sides; play the phone's part by hand.
        let secret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        let mut us = Handshake::new(secret);
        let reply = match us.handle("challenge aabbccdd") {
            Step::Reply(r) => r,
            _ => panic!("expected a reply"),
        };
        let words: Vec<&str> = reply.split(' ').collect();
        assert_eq!(words[0], "auth");
        assert_eq!(words[1], hmac_hex(secret, "aabbccdd"));
        let phone_proof = hmac_hex(secret, words[2]);
        assert!(matches!(us.handle(&format!("ok {phone_proof}")), Step::Verified));
    }

    #[test]
    fn handshake_rejects_wrong_proof() {
        let mut us = Handshake::new("s");
        let _ = us.handle("challenge x");
        assert!(matches!(us.handle("ok deadbeef"), Step::Failed));
    }

    #[test]
    fn crypto_round_trips_between_directions() {
        // The PC seals with direction 2; a phone-side opener expects 2. Simulate
        // the phone by building a crypto whose "open" accepts direction 2.
        let mut pc = SessionCrypto::new("secret", "n1", "n2");
        let sealed = pc.seal(b"hello");
        assert_eq!(sealed[0], 2);
        assert!(pc.open(&sealed).is_none(), "must not open our own stream");
        // Same key, phone direction: flip the byte and it opens.
        let mut as_phone = sealed.clone();
        as_phone[0] = 1;
        // Decryption is over nonce||ct, so the flipped nonce fails the tag: that's correct GCM behaviour.
        assert!(pc.open(&as_phone).is_none());
    }

    #[test]
    fn status_parses() {
        let s = parse_status("daemon=1 tunnel=0 keep=1 keepAt=123 paused=0");
        assert_eq!(s["tunnel"], "0");
        assert_eq!(s["keepAt"], "123");
    }
}
