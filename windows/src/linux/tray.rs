//! The tray icon as a StatusNotifierItem (KDE, Cinnamon, XFCE, and GNOME with
//! the AppIndicator extension). Stock GNOME shows no tray at all, so
//! [available] tells the app not to hide into something that isn't there.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{channel, Receiver, Sender};

use ksni::blocking::{Handle, TrayMethods};
use ksni::menu::{MenuItem, StandardItem};

pub use crate::event::TrayAction;

static AVAILABLE: AtomicBool = AtomicBool::new(false);

/// Whether a tray is showing Bridge's icon right now.
pub fn available() -> bool {
    AVAILABLE.load(Ordering::SeqCst)
}

struct BridgeTray {
    tx: Sender<TrayAction>,
}

fn item(label: &str, action: TrayAction) -> MenuItem<BridgeTray> {
    StandardItem {
        label: label.into(),
        activate: Box::new(move |t: &mut BridgeTray| { let _ = t.tx.send(action); }),
        ..Default::default()
    }
    .into()
}

impl ksni::Tray for BridgeTray {
    fn id(&self) -> String { "bridge".into() }
    fn title(&self) -> String { "Bridge".into() }
    fn activate(&mut self, _x: i32, _y: i32) { let _ = self.tx.send(TrayAction::Open); }
    fn icon_pixmap(&self) -> Vec<ksni::Icon> {
        let img = image::load_from_memory(include_bytes!("../../assets/icon-32.png")).expect("tray icon png").into_rgba8();
        let (width, height) = img.dimensions();
        // ARGB, where the PNG gives RGBA.
        let data = img.into_raw().chunks_exact(4).flat_map(|p| [p[3], p[0], p[1], p[2]]).collect();
        vec![ksni::Icon { width: width as i32, height: height as i32, data }]
    }
    fn menu(&self) -> Vec<MenuItem<Self>> {
        vec![
            item("Open Bridge", TrayAction::Open),
            item("Settings…", TrayAction::Settings),
            MenuItem::Separator,
            item("Quit Bridge", TrayAction::Quit),
        ]
    }
    fn watcher_online(&self) { AVAILABLE.store(true, Ordering::SeqCst); }
    fn watcher_offline(&self, _reason: ksni::OfflineReason) -> bool {
        AVAILABLE.store(false, Ordering::SeqCst);
        true // keep going: it may come back (a shell restart)
    }
}

pub struct Tray {
    actions: Receiver<TrayAction>,
    _handle: Option<Handle<BridgeTray>>,
}

impl Tray {
    pub fn new() -> Tray {
        let (tx, actions) = channel();
        // A plain spawn fails when the desktop has no tray, which is the only
        // way to find out at the start: watcher_online is for coming *back*.
        let handle = match (BridgeTray { tx: tx.clone() }).spawn() {
            Ok(handle) => {
                AVAILABLE.store(true, Ordering::SeqCst);
                Some(handle)
            }
            Err(e) => {
                crate::log!("", "no tray on this desktop ({e}); closing the window leaves Bridge running");
                // Keep the icon ready in case one appears (the extension gets enabled).
                BridgeTray { tx }.assume_sni_available(true).spawn().ok()
            }
        };
        Tray { actions, _handle: handle }
    }

    pub fn poll(&self) -> Option<TrayAction> {
        self.actions.try_recv().ok()
    }
}
