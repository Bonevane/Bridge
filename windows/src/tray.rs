//! The tray icon: left-click shows the window; the menu has Open, Settings and Quit.

use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{MouseButton, TrayIcon, TrayIconBuilder, TrayIconEvent};

pub use crate::event::TrayAction;

/// Windows always has a tray; on Linux it depends on the desktop.
pub fn available() -> bool {
    true
}

/// The navy square with the white mark (assets/icon-32.png, from make-icons.sh).
fn icon() -> tray_icon::Icon {
    let img = image::load_from_memory(include_bytes!("../assets/icon-32.png")).expect("tray icon png").into_rgba8();
    let (w, h) = img.dimensions();
    tray_icon::Icon::from_rgba(img.into_raw(), w, h).expect("icon")
}

pub struct Tray {
    show: MenuItem,
    settings: MenuItem,
    quit: MenuItem,
    _icon: TrayIcon,
}

impl Tray {
    pub fn new() -> Tray {
        let menu = Menu::new();
        let show = MenuItem::new("Open Bridge", true, None);
        let settings = MenuItem::new("Settings…", true, None);
        let quit = MenuItem::new("Quit Bridge", true, None);
        let _ = menu.append_items(&[&show, &settings, &PredefinedMenuItem::separator(), &quit]);
        let _icon = TrayIconBuilder::new()
            .with_menu(Box::new(menu))
            .with_tooltip("Bridge")
            .with_icon(icon())
            .build()
            .expect("tray icon");
        Tray { show, settings, quit, _icon }
    }

    pub fn poll(&self) -> Option<TrayAction> {
        while let Ok(e) = MenuEvent::receiver().try_recv() {
            if e.id == self.show.id() {
                return Some(TrayAction::Open);
            } else if e.id == self.settings.id() {
                return Some(TrayAction::Settings);
            } else if e.id == self.quit.id() {
                return Some(TrayAction::Quit);
            }
        }
        while let Ok(e) = TrayIconEvent::receiver().try_recv() {
            if let TrayIconEvent::Click { button: MouseButton::Left, .. } = e {
                return Some(TrayAction::Open);
            }
        }
        None
    }
}
