//! Phone notifications as Windows toasts, with a reply box and swipe-away
//! that reach the phone.
//!
//! Built directly on Windows.UI.Notifications rather than a wrapper crate:
//! the reply box (a toast `<input>`), the Activated event that hands us what
//! was typed, and the Dismissed event (swiped away → dismissed on the phone
//! too) aren't exposed by the wrappers.
//!
//! Unpackaged apps need an AppUserModelID registered in the Start Menu to
//! toast; this borrows PowerShell's, which works everywhere without an
//! installer. The toast's events still come to this process while it runs.

use std::collections::HashMap;
use std::sync::mpsc::Sender;
use std::sync::Mutex;
use windows::core::{Interface, HSTRING};
use windows::Data::Xml::Dom::XmlDocument;
use windows::Foundation::{IPropertyValue, TypedEventHandler};
use windows::UI::Notifications::{
    ToastActivatedEventArgs, ToastDismissalReason, ToastDismissedEventArgs, ToastNotification, ToastNotificationManager,
};

use crate::ble::Event;

const APP_ID: &str = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe";
const GROUP: &str = "bridge";

/// Toasts on screen, by phone id. Kept so their event handlers stay alive,
/// and so a toast we remove on the phone's say-so isn't reported back as
/// "dismissed by the user".
static SHOWN: Mutex<Option<HashMap<u32, ToastNotification>>> = Mutex::new(None);

/// Where the phone's app icons are cached, one PNG per package.
pub fn icon_dir() -> std::path::PathBuf {
    crate::store::data_dir().join("icons")
}

pub fn icon_path(package: &str) -> std::path::PathBuf {
    icon_dir().join(format!("{package}.png"))
}

fn escape(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// A plain toast from Bridge itself (file done, pairing…).
pub fn show(app: &str, title: &str, body: &str) {
    let xml = format!(
        r#"<toast><visual><binding template="ToastGeneric"><text>{}</text><text>{}</text><text>{}</text></binding></visual><audio silent="true"/></toast>"#,
        escape(app), escape(title), escape(body)
    );
    if let Err(e) = deliver(&xml, None, None) {
        crate::log!("notify", "toast failed: {e}");
    }
}

/// One phone notification. With `id`, the same id replaces the toast (a chat's
/// next message), "reply" puts a text box on it when the app supports replies,
/// and swiping it away dismisses it on the phone.
pub fn show_phone(n: &crate::protocol::PhoneNotification, events: Sender<Event>) {
    let icon = (!n.package.is_empty())
        .then(|| icon_path(&n.package))
        .filter(|p| p.exists())
        .map(|p| format!(r#"<image placement="appLogoOverride" hint-crop="circle" src="{}"/>"#, escape(&p.to_string_lossy())))
        .unwrap_or_default();
    let actions = if n.replyable && n.id != 0 {
        r#"<actions><input id="reply" type="text" placeHolderContent="Reply"/><action content="Send" arguments="reply" hint-inputId="reply" activationType="foreground"/></actions>"#
    } else {
        ""
    };
    // App name first, as on the Mac: it's the most useful thing to see.
    let xml = format!(
        r#"<toast><visual><binding template="ToastGeneric"><text>{}</text><text>{}</text><text>{}</text>{icon}</binding></visual>{actions}<audio silent="true"/></toast>"#,
        escape(&n.app), escape(&n.title), escape(&n.body)
    );
    let id = (n.id != 0).then_some(n.id);
    if let Err(e) = deliver(&xml, id, Some(events)) {
        crate::log!("notify", "toast failed: {e}");
    }
}

/// The phone says it's gone: take the toast down (from the screen and from
/// Action Center).
pub fn remove(id: u32) {
    let had = SHOWN.lock().unwrap().get_or_insert_with(HashMap::new).remove(&id).is_some();
    if !had {
        return;
    }
    if let Ok(history) = ToastNotificationManager::History() {
        let _ = history.RemoveGroupedTagWithId(&HSTRING::from(id.to_string()), &HSTRING::from(GROUP), &HSTRING::from(APP_ID));
    }
}

fn deliver(xml: &str, id: Option<u32>, events: Option<Sender<Event>>) -> windows::core::Result<()> {
    let doc = XmlDocument::new()?;
    doc.LoadXml(&HSTRING::from(xml))?;
    let toast = ToastNotification::CreateToastNotification(&doc)?;
    if let (Some(id), Some(events)) = (id, events) {
        toast.SetTag(&HSTRING::from(id.to_string()))?;
        toast.SetGroup(&HSTRING::from(GROUP))?;

        let tx = events.clone();
        toast.Activated(&TypedEventHandler::new(move |_: &Option<ToastNotification>, args: &Option<windows::core::IInspectable>| {
            let Some(args) = args else { return Ok(()) };
            let Ok(args) = args.cast::<ToastActivatedEventArgs>() else { return Ok(()) };
            if args.Arguments()?.to_string() == "reply" {
                let text = args
                    .UserInput()
                    .and_then(|input| input.Lookup(&HSTRING::from("reply")))
                    .and_then(|v| v.cast::<IPropertyValue>())
                    .and_then(|v| v.GetString())
                    .map(|s| s.to_string())
                    .unwrap_or_default();
                if !text.trim().is_empty() {
                    let _ = tx.send(Event::ToastReply { id, text });
                }
            }
            Ok(())
        }))?;

        let tx = events;
        toast.Dismissed(&TypedEventHandler::new(move |_: &Option<ToastNotification>, args: &Option<ToastDismissedEventArgs>| {
            // Only the user swiping it away counts; timing out into Action
            // Center, or us removing it because the phone did, doesn't.
            if let Some(args) = args {
                if args.Reason()? == ToastDismissalReason::UserCanceled {
                    let still_ours = SHOWN.lock().unwrap().get_or_insert_with(HashMap::new).remove(&id).is_some();
                    if still_ours {
                        let _ = tx.send(Event::ToastDismissed(id));
                    }
                }
            }
            Ok(())
        }))?;

        let mut shown = SHOWN.lock().unwrap();
        let map = shown.get_or_insert_with(HashMap::new);
        map.insert(id, toast.clone());
        if map.len() > 100 {
            // Oldest ids first: they're long gone from the screen.
            let mut ids: Vec<u32> = map.keys().copied().collect();
            ids.sort();
            for old in ids.into_iter().take(map.len() - 100) {
                map.remove(&old);
            }
        }
    }
    ToastNotificationManager::CreateToastNotifierWithId(&HSTRING::from(APP_ID))?.Show(&toast)?;
    Ok(())
}
