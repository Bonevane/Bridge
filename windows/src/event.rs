//! What the Bluetooth link, the tunnel and the updater tell the rest of the
//! app, shared by the Windows and Linux Bluetooth code.

use crate::protocol::{self, Kind};
use anyhow::{anyhow, Result};
use std::sync::mpsc::Sender;

/// A choice from the tray icon's menu.
#[derive(Clone, Copy, Debug)]
pub enum TrayAction {
    Open,
    Settings,
    Quit,
}

/// What the link tells the rest of the app.
#[derive(Debug)]
pub enum Event {
    Searching,
    Linked,
    Dropped(String),
    Notification(protocol::PhoneNotification),
    /// A mirrored notification left the phone (read, swiped, answered there).
    NotificationRemoved(u32),
    /// Now Playing changed (see protocol::parse_media).
    Media(std::collections::HashMap<String, String>),
    /// Album art: key and JPEG bytes.
    Art { key: String, jpeg: Vec<u8> },
    /// From a toast (notify.rs): reply typed, or swiped away.
    ToastReply { id: u32, text: String },
    ToastDismissed(u32),
    /// An app icon the phone sent: package and PNG bytes.
    Icon { package: String, png: Vec<u8> },
    Clipboard(String),
    Status(std::collections::HashMap<String, String>),
    /// From the tunnel thread: which Connect attempt, and START's reply or why it failed.
    Connected(u32, Result<String, String>),
    /// Set up over USB (usb.rs): a step started, then the outcome.
    UsbProgress(String),
    UsbDone(Result<crate::store::Credentials, String>),
    /// Phone files (files.rs): a folder listing, transfer progress, and the outcome.
    FilesListed(Result<(String, Vec<crate::files::Entry>), String>),
    /// Transfer progress: what's moving, and how far (0.0 to 1.0).
    FileProgress(String, f32),
    FileDone(Result<String, String>),
    /// The phone is paired (bonded) in Windows' Bluetooth settings, which
    /// breaks the link: Bridge connects unpaired, and encrypts by itself.
    PairedInWindows { name: String, address: u64 },
    /// The outcome of unpairing it for the user.
    UnpairedInWindows(Result<String, String>),
    /// The mirroring watchdog's ping: did the phone answer STATUS?
    SessionPing(bool),
    /// The phone window's "Phone files" button.
    OpenFiles,
    /// This PC's Bluetooth radio: Some(on) when known, None if it has none.
    Radio(Option<bool>),
    /// The updater (updater.rs): what a check found, and every step after.
    UpdateChecked(crate::updater::Checked),
    UpdateState(crate::updater::State),
    /// The new version was started after an update: did it come up?
    UpdateLaunched(Result<(), String>),
}

/// A decrypted message from the phone, passed on as an event.
pub fn deliver(kind: Kind, text: String, events: &Sender<Event>) {
    match kind {
        Kind::Notification => {
            if let Some(n) = protocol::parse_notification(&text) {
                let _ = events.send(Event::Notification(n));
            }
        }
        Kind::Removed => {
            if let Ok(id) = text.trim().parse() {
                let _ = events.send(Event::NotificationRemoved(id));
            }
        }
        Kind::Media => {
            let _ = events.send(Event::Media(protocol::parse_media(&text)));
        }
        Kind::Art => {
            if let Some((key, b64)) = text.split_once('\t') {
                if let Ok(jpeg) = base64_decode(b64) {
                    let _ = events.send(Event::Art { key: key.to_string(), jpeg });
                }
            }
        }
        Kind::Icon => {
            if let Some((package, b64)) = text.split_once('\t') {
                if let Ok(png) = base64_decode(b64) {
                    let _ = events.send(Event::Icon { package: package.to_string(), png });
                }
            }
        }
        Kind::Clipboard => {
            let _ = events.send(Event::Clipboard(text));
        }
        Kind::Status => {
            let _ = events.send(Event::Status(protocol::parse_status(&text)));
        }
        Kind::Ping | Kind::Command | Kind::Auth => {}
    }
}

/// Standard base64, no dependency needed for one use.
fn base64_decode(text: &str) -> Result<Vec<u8>> {
    const T: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = Vec::with_capacity(text.len() * 3 / 4);
    let mut acc = 0u32;
    let mut bits = 0;
    for c in text.bytes() {
        if c == b'=' { break; }
        let v = T.iter().position(|&t| t == c).ok_or_else(|| anyhow!("bad base64"))? as u32;
        acc = (acc << 6) | v;
        bits += 6;
        if bits >= 8 {
            bits -= 8;
            out.push((acc >> bits) as u8);
            acc &= (1 << bits) - 1;
        }
    }
    Ok(out)
}
