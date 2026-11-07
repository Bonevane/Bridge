//! Phone notifications through the desktop's notification service, over the
//! freedesktop D-Bus interface directly: the same id-replaces-notification
//! behaviour as Windows toasts, and swiping one away reaches the phone.
//!
//! Replying needs KDE's "inline-reply" extension, so the Reply box only
//! appears on desktops that offer it (asked with GetCapabilities). GNOME
//! doesn't; there you reply on the phone.

use std::collections::HashMap;
use std::sync::mpsc::Sender;
use std::sync::{Mutex, OnceLock};
use zbus::blocking::{Connection, Proxy};
use zbus::zvariant::Value;

use crate::ble::Event;

const SERVICE: &str = "org.freedesktop.Notifications";
const PATH: &str = "/org/freedesktop/Notifications";
/// "Closed" reason from the spec: the user dismissed it.
const DISMISSED_BY_USER: u32 = 2;

/// Phone id → the desktop's id for the notification on screen.
static SHOWN: Mutex<Option<HashMap<u32, u32>>> = Mutex::new(None);
/// Where replies and dismissals go; set by the first phone notification.
static EVENTS: Mutex<Option<Sender<Event>>> = Mutex::new(None);

struct Bus {
    proxy: Proxy<'static>,
    inline_reply: bool,
}

fn bus() -> Option<&'static Bus> {
    static BUS: OnceLock<Option<Bus>> = OnceLock::new();
    BUS.get_or_init(|| {
        let connect = || -> zbus::Result<Bus> {
            let connection = Connection::session()?;
            let proxy = Proxy::new(&connection, SERVICE, PATH, SERVICE)?;
            let capabilities: Vec<String> = proxy.call("GetCapabilities", &()).unwrap_or_default();
            listen(&proxy)?;
            Ok(Bus { proxy, inline_reply: capabilities.iter().any(|c| c == "inline-reply") })
        };
        connect().map_err(|e| crate::log!("notify", "no notification service: {e}")).ok()
    })
    .as_ref()
}

/// Two threads, one per signal, that live as long as Bridge.
fn listen(proxy: &Proxy<'static>) -> zbus::Result<()> {
    let closed = proxy.receive_signal("NotificationClosed")?;
    std::thread::spawn(move || {
        for message in closed {
            let Ok((server_id, reason)) = message.body().deserialize::<(u32, u32)>() else { continue };
            let Some(id) = forget(server_id) else { continue };
            if reason == DISMISSED_BY_USER {
                send(Event::ToastDismissed(id));
            }
        }
    });
    let replied = proxy.receive_signal("NotificationReplied")?;
    std::thread::spawn(move || {
        for message in replied {
            let Ok((server_id, text)) = message.body().deserialize::<(u32, String)>() else { continue };
            if let Some(id) = phone_id(server_id) {
                if !text.trim().is_empty() {
                    send(Event::ToastReply { id, text });
                }
            }
        }
    });
    Ok(())
}

fn send(event: Event) {
    if let Some(tx) = EVENTS.lock().unwrap().as_ref() {
        let _ = tx.send(event);
    }
}

fn phone_id(server_id: u32) -> Option<u32> {
    SHOWN.lock().unwrap().as_ref()?.iter().find(|(_, s)| **s == server_id).map(|(id, _)| *id)
}

/// Takes it off the list, so only the first to notice reports it.
fn forget(server_id: u32) -> Option<u32> {
    let id = phone_id(server_id)?;
    SHOWN.lock().unwrap().as_mut()?.remove(&id);
    Some(id)
}

/// Where the phone's app icons are cached, one PNG per package.
pub fn icon_dir() -> std::path::PathBuf {
    crate::store::data_dir().join("icons")
}

pub fn icon_path(package: &str) -> std::path::PathBuf {
    icon_dir().join(format!("{package}.png"))
}

/// The body may be read as markup, so text from the phone can't be left as is.
fn escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;")
}

fn deliver(app: &str, summary: &str, body: &str, icon: &str, replaces: u32, reply: bool) -> Option<u32> {
    let bus = bus()?;
    let mut hints: HashMap<&str, Value> = HashMap::new();
    hints.insert("desktop-entry", Value::from("bridge"));
    let actions: &[&str] = if reply && bus.inline_reply {
        hints.insert("x-kde-reply-placeholder-text", Value::from("Reply"));
        &["inline-reply", "Reply"]
    } else {
        &[]
    };
    let timeout = -1i32; // the desktop's own default
    let sent = bus.proxy.call("Notify", &(app, replaces, icon, summary, escape(body), actions, hints, timeout));
    sent.map_err(|e| crate::log!("notify", "notification failed: {e}")).ok()
}

/// A plain notification from Bridge itself (file done, pairing…).
pub fn show(app: &str, title: &str, body: &str) {
    deliver(app, title, body, "bridge", 0, false);
}

/// One phone notification. With `id`, the same id replaces it (a chat's next
/// message), and dismissing it dismisses it on the phone.
pub fn show_phone(n: &crate::protocol::PhoneNotification, events: Sender<Event>) {
    *EVENTS.lock().unwrap() = Some(events);
    let icon = (!n.package.is_empty())
        .then(|| icon_path(&n.package))
        .filter(|p| p.exists())
        .map(|p| p.to_string_lossy().into_owned())
        .unwrap_or_else(|| "bridge".into());
    let summary = if n.title.is_empty() { &n.app } else { &n.title };
    let replaces = SHOWN.lock().unwrap().as_ref().and_then(|m| m.get(&n.id).copied()).unwrap_or(0);
    let Some(server_id) = deliver(&n.app, summary, &n.body, &icon, replaces, n.replyable && n.id != 0) else { return };
    if n.id == 0 {
        return;
    }
    let mut shown = SHOWN.lock().unwrap();
    let map = shown.get_or_insert_with(HashMap::new);
    map.insert(n.id, server_id);
    if map.len() > 100 {
        // Oldest ids first: they're long gone from the screen.
        let mut ids: Vec<u32> = map.keys().copied().collect();
        ids.sort();
        for old in ids.into_iter().take(map.len() - 100) {
            map.remove(&old);
        }
    }
}

/// The phone says it's gone: take it down here too.
pub fn remove(id: u32) {
    let server_id = SHOWN.lock().unwrap().as_mut().and_then(|m| m.remove(&id));
    if let (Some(server_id), Some(bus)) = (server_id, bus()) {
        let _: zbus::Result<()> = bus.proxy.call("CloseNotification", &(server_id,));
    }
}
