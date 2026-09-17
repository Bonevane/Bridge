//! Bridge for Windows. Milestone 1: the Bluetooth trio.
//!
//!   - pair by copying the ticket from the phone (Copy under Ticket) and
//!     choosing "Pair from clipboard" in the tray menu;
//!   - Bluetooth LE link with the pairing handshake;
//!   - phone notifications as toasts, clipboard both ways, and "open on this
//!     PC" twin reporting so the phone skips duplicate notifications.
//!
//! No tunnel and no mirroring yet; those are milestone 2.
//!
//! Structure: one worker thread owns the Bluetooth link and a channel of
//! events from it; the main thread runs the Win32 message loop for the tray
//! icon and polls the channel and the clipboard on a timer.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod ble;
mod clipboard;
mod log;
mod notify;
mod protocol;
mod store;
mod twins;

use ble::Event;
use protocol::Kind;
use std::collections::BTreeSet;
use std::sync::mpsc;
use std::time::{Duration, Instant};
use tray_icon::menu::{Menu, MenuEvent, MenuItem, PredefinedMenuItem};
use tray_icon::{Icon, TrayIconBuilder};
use windows::Win32::UI::WindowsAndMessaging::{DispatchMessageW, PeekMessageW, TranslateMessage, MSG, PM_REMOVE};

const VERSION: &str = env!("CARGO_PKG_VERSION");

struct App {
    creds: store::Credentials,
    ble: Option<ble::Ble>,
    events: mpsc::Receiver<Event>,
    events_tx: mpsc::Sender<Event>,
    clipboard: clipboard::Watcher,
    linked: bool,
    last_twins: Option<BTreeSet<String>>,
    last_tick: Instant,
    status_item: MenuItem,
}

impl App {
    fn new(status_item: MenuItem) -> Self {
        let (events_tx, events) = mpsc::channel();
        App {
            creds: store::load().unwrap_or_default(),
            ble: None,
            events,
            events_tx,
            clipboard: clipboard::Watcher::new(),
            linked: false,
            last_twins: None,
            last_tick: Instant::now(),
            status_item,
        }
    }

    fn start_bluetooth(&mut self) {
        if !self.creds.is_paired() {
            self.set_status("Not paired: copy the ticket on the phone, then Pair from clipboard");
            return;
        }
        let mut link = ble::Ble::new(&self.creds.secret, self.events_tx.clone());
        match link.start() {
            Ok(()) => {
                self.ble = Some(link);
                self.set_status("Looking for your phone…");
            }
            Err(e) => {
                crate::log!("bluetooth", "couldn't start: {e:#}");
                self.set_status(&format!("Bluetooth unavailable: {e}"));
            }
        }
    }

    fn pair_from_clipboard(&mut self) {
        let text = arboard::Clipboard::new().ok().and_then(|mut c| c.get_text().ok()).unwrap_or_default();
        match store::Credentials::parse(&text) {
            Some(creds) => {
                if let Err(e) = store::save(&creds) {
                    crate::log!("", "couldn't save credentials: {e:#}");
                    return;
                }
                self.creds = creds;
                crate::log!("", "paired from the clipboard");
                if let Some(b) = self.ble.as_mut() {
                    b.stop();
                }
                self.ble = None;
                self.start_bluetooth();
            }
            None => {
                crate::log!("", "clipboard doesn't hold a Bridge ticket (expected \"endpoint… <secret>\")");
                notify::show("Bridge", "Nothing to pair with", "Tap Copy under Ticket on the phone first, then try again.");
            }
        }
    }

    fn unpair(&mut self) {
        let _ = store::clear();
        self.creds = store::Credentials::default();
        if let Some(b) = self.ble.as_mut() {
            b.stop();
        }
        self.ble = None;
        self.linked = false;
        self.set_status("Not paired");
        crate::log!("", "unpaired");
    }

    fn set_status(&self, text: &str) {
        self.status_item.set_text(text);
    }

    /// Runs every ~500 ms from the message loop.
    fn poll(&mut self) {
        while let Ok(event) = self.events.try_recv() {
            self.handle(event);
        }
        if self.linked {
            if let Some(text) = self.clipboard.poll() {
                if let Some(b) = &self.ble {
                    match b.send(Kind::Clipboard, &text) {
                        Ok(()) => crate::log!("clipboard", "sent to the phone"),
                        Err(e) => crate::log!("clipboard", "couldn't send: {e:#}"),
                    }
                }
            }
        }
        if self.last_tick.elapsed() > Duration::from_secs(5) {
            self.last_tick = Instant::now();
            if let Some(b) = self.ble.as_mut() {
                b.tick();
            }
            if self.linked {
                self.report_twins(false);
            }
        }
    }

    fn handle(&mut self, event: Event) {
        match event {
            Event::Searching => {
                self.linked = false;
                self.set_status("Looking for your phone…");
            }
            Event::Linked => {
                self.linked = true;
                self.set_status("Phone nearby (Bluetooth)");
                self.report_twins(true);
            }
            Event::Dropped(why) => {
                self.linked = false;
                self.set_status(&format!("Phone out of range ({why})"));
                self.last_twins = None;
            }
            Event::Notification { app, title, body } => {
                crate::log!("notify", "{app}: {title}");
                notify::show(&app, &title, &body);
            }
            Event::Clipboard(text) => {
                crate::log!("clipboard", "from the phone ({} chars)", text.chars().count());
                self.clipboard.set(&text);
            }
            Event::Status(fields) => {
                let tunnel = fields.get("tunnel").map(|v| v == "1").unwrap_or(false);
                let daemon = fields.get("daemon").map(|v| v == "1").unwrap_or(false);
                self.set_status(&format!(
                    "Phone nearby · tunnel {} · helper {}",
                    if tunnel { "on" } else { "off" },
                    if daemon { "running" } else { "off" }
                ));
            }
        }
    }

    /// Tells the phone which twin apps are open here (see twins.rs).
    fn report_twins(&mut self, force: bool) {
        let now = twins::open_packages();
        if !force && self.last_twins.as_ref() == Some(&now) {
            return;
        }
        let list: Vec<&str> = now.iter().map(String::as_str).collect();
        if let Some(b) = &self.ble {
            if b.send_command(&format!("macapps {}", list.join(" "))).is_ok() {
                self.last_twins = Some(now);
            }
        }
    }
}

fn tray_icon() -> Icon {
    // The bridge mark, 32×32, generated from assets/bridge-mark.png at build
    // time would be nicer; for the skeleton, a navy square with a white bar.
    let size = 32u32;
    let mut rgba = vec![0u8; (size * size * 4) as usize];
    for y in 0..size {
        for x in 0..size {
            let i = ((y * size + x) * 4) as usize;
            let (r, g, b) = if (10..=14).contains(&y) || ((15..=24).contains(&y) && (x % 12) < 4) {
                (255, 255, 255)
            } else {
                (0x0a, 0x14, 0x32)
            };
            rgba[i..i + 4].copy_from_slice(&[r, g, b, 255]);
        }
    }
    Icon::from_rgba(rgba, size, size).expect("icon")
}

fn main() {
    log::init();
    crate::log!("", "Bridge {VERSION} starting");

    let menu = Menu::new();
    let status = MenuItem::new("Starting…", false, None);
    let pair = MenuItem::new("Pair from clipboard", true, None);
    let rescan = MenuItem::new("Look for the phone again", true, None);
    let unpair = MenuItem::new("Unpair", true, None);
    let open_log = MenuItem::new("Open log folder", true, None);
    let quit = MenuItem::new("Quit Bridge", true, None);
    let _ = menu.append_items(&[
        &status,
        &PredefinedMenuItem::separator(),
        &pair,
        &rescan,
        &unpair,
        &PredefinedMenuItem::separator(),
        &open_log,
        &quit,
    ]);
    let _tray = TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip("Bridge")
        .with_icon(tray_icon())
        .build()
        .expect("tray icon");

    let mut app = App::new(status);
    app.start_bluetooth();

    let menu_events = MenuEvent::receiver();
    let mut msg = MSG::default();
    loop {
        // Pump the Win32 queue so the tray and its menu work, then do our
        // own polling. Sleeping keeps this at ~0% CPU.
        unsafe {
            while PeekMessageW(&mut msg, None, 0, 0, PM_REMOVE).as_bool() {
                let _ = TranslateMessage(&msg);
                DispatchMessageW(&msg);
            }
        }
        while let Ok(e) = menu_events.try_recv() {
            if e.id == pair.id() {
                app.pair_from_clipboard();
            } else if e.id == rescan.id() {
                if let Some(b) = app.ble.as_mut() {
                    let _ = b.start();
                } else {
                    app.start_bluetooth();
                }
            } else if e.id == unpair.id() {
                app.unpair();
            } else if e.id == open_log.id() {
                let _ = std::process::Command::new("explorer").arg(store::data_dir()).spawn();
            } else if e.id == quit.id() {
                crate::log!("", "quit");
                if let Some(b) = app.ble.as_mut() {
                    b.stop();
                }
                return;
            }
        }
        app.poll();
        std::thread::sleep(Duration::from_millis(100));
    }
}
