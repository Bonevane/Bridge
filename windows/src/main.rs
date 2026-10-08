//! Bridge for Windows.
//!
//! Milestone 1 (done): pair by copying the ticket from the phone; Bluetooth
//! link with the handshake and app-layer encryption; notifications as
//! toasts; clipboard both ways; open-app twins.
//! Milestone 2 (done): the tunnel (bundled dumbpipe), the control protocol
//! over it, and mirroring with video, audio and input.
//! Then: Set up over USB (usb.rs), notification icons fetched from the phone
//! over Bluetooth, and a Material 3 look (ui/theme.slint).
//!
//! Structure: Slint owns the window and the event loop; the Bluetooth link
//! and the tunnel run on their own threads and report back through a channel
//! that a timer drains on the UI thread.

#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod audio;
mod ble;
mod clipboard;
mod files;
mod log;
mod notify;
mod protocol;
mod scrcpy;
mod session;
mod settings;
mod store;
mod tunnel;
mod twins;
mod usb;
mod video;

use ble::Event;
use protocol::Kind;
use session::SessionEvent;
use slint::{ComponentHandle, Timer, TimerMode};
use std::cell::RefCell;
use std::collections::BTreeSet;
use std::rc::Rc;
use std::sync::mpsc;
use std::time::{Duration, Instant};

slint::include_modules!();

const VERSION: &str = env!("CARGO_PKG_VERSION");

/// Everything the UI thread owns.
struct App {
    window: MainWindow,
    settings_window: SettingsWindow,
    files_window: FilesWindow,
    /// The phone folder the files window shows, as the phone resolved it.
    files_path: String,
    files_entries: Vec<files::Entry>,
    /// One transfer at a time: dropping ten files queues them rather than
    /// opening ten streams at once.
    transfer_lock: std::sync::Arc<std::sync::Mutex<()>>,
    settings: settings::Settings,
    creds: store::Credentials,
    ble: Option<ble::Ble>,
    events: mpsc::Receiver<Event>,
    events_tx: mpsc::Sender<Event>,
    clipboard: clipboard::Watcher,
    linked: bool,
    searching: bool,
    phone_tunnel_on: bool,
    phone_paused: bool,
    tunnel: Option<tunnel::Tunnel>,
    session: Option<std::sync::Arc<session::Session>>,
    session_events: Option<mpsc::Receiver<SessionEvent>>,
    mirror_window: Option<MirrorWindow>,
    /// The video size the window's input callbacks scale coordinates against.
    mirror_size: Option<Rc<RefCell<(u16, u16)>>>,
    /// Last text we synced with the phone over the session, to stop ping-pong.
    last_synced: Option<String>,
    mirroring: bool,
    /// The tunnel and helper are up for the files window only (no video).
    files_session: bool,
    /// The connect in progress is for the files window, not for mirroring.
    connect_for_files: bool,
    /// Which Connect this is. A result from an earlier, cancelled attempt is
    /// ignored, and the thread behind it stops retrying as soon as it sees
    /// the number move on (it used to keep hammering START, and once it got
    /// through it would "undo" the helper the *current* attempt had started).
    attempt: std::sync::Arc<std::sync::atomic::AtomicU32>,
    connecting: bool,
    /// Whether this attempt has asked the phone for its tunnel yet.
    woke_tunnel: bool,
    last_twins: Option<BTreeSet<String>>,
    last_tick: Instant,
    rescan_at: Option<Instant>,
    /// Bluetooth couldn't start (radio off, or not ready yet at login): try again then.
    retry_ble_at: Option<Instant>,
    /// This PC's Bluetooth radio (kept alive for its change events) and
    /// whether it's on: None until known, or if there's no adapter at all.
    radio: Option<windows::Devices::Radios::Radio>,
    pc_bluetooth: Option<bool>,
    no_adapter: bool,
    usb_busy: bool,
    /// The last failure (title, detail, from USB setup?). Stays on the hero
    /// card until the next action, so a heartbeat can't wipe it before it's read.
    error: Option<(String, String, bool)>,
    /// Packages whose icon we've already asked the phone for this link.
    icons_requested: BTreeSet<String>,
}

impl App {
    fn new(window: MainWindow, settings_window: SettingsWindow, files_window: FilesWindow) -> Self {
        let (events_tx, events) = mpsc::channel();
        App {
            window,
            settings_window,
            files_window,
            files_path: String::new(),
            files_entries: Vec::new(),
            transfer_lock: Default::default(),
            settings: settings::Settings::load(),
            creds: store::load().unwrap_or_default(),
            ble: None,
            events,
            events_tx,
            clipboard: clipboard::Watcher::new(),
            linked: false,
            searching: false,
            phone_tunnel_on: false,
            phone_paused: false,
            tunnel: None,
            session: None,
            session_events: None,
            mirror_window: None,
            mirror_size: None,
            last_synced: None,
            mirroring: false,
            files_session: false,
            connect_for_files: false,
            attempt: std::sync::Arc::new(std::sync::atomic::AtomicU32::new(0)),
            connecting: false,
            woke_tunnel: false,
            last_twins: None,
            last_tick: Instant::now(),
            rescan_at: None,
            retry_ble_at: None,
            radio: None,
            pc_bluetooth: None,
            no_adapter: false,
            usb_busy: false,
            error: None,
            icons_requested: BTreeSet::new(),
        }
    }

    fn log(&mut self, source: &str, text: &str) {
        crate::log::line(source, text);
        self.show_log();
    }

    /// The panel shows every line the app logs, from every thread, newest
    /// first (the text box can't follow the end by itself, and the latest line
    /// is what you open it for). The file (Open log folder) stays in time order.
    fn show_log(&self) {
        if let Some(text) = crate::log::take_recent() {
            self.window.global::<AppState>().set_log_text(text.into());
        }
    }

    // MARK: - State → UI

    fn refresh(&self) {
        let ui = self.window.global::<AppState>();
        ui.set_paired(self.creds.is_paired());
        ui.set_linked(self.linked);
        ui.set_searching(self.searching);
        ui.set_phone_tunnel_on(self.phone_tunnel_on);
        ui.set_phone_paused(self.phone_paused);
        ui.set_mirroring(self.mirroring);
        ui.set_version(VERSION.into());

        let usb_failed = matches!(&self.error, Some((_, _, true)));
        ui.set_usb_retry(usb_failed && !self.usb_busy && !self.connecting && !self.mirroring);
        let (title, detail, tint) = if let Some((title, detail, _)) = &self.error {
            (title.as_str(), detail.as_str(), 3)
        } else if !self.creds.is_paired() {
            ("Pair your phone first", "On the phone tap Copy under Ticket, get it onto this PC's clipboard, then click Pair.", 0)
        } else if self.usb_busy {
            ("Setting up over USB", "Follow the phone's prompts", 2)
        } else if self.connecting {
            ("Connecting", "Starting the tunnel and the phone's helper…", 2)
        } else if self.mirroring {
            ("Mirroring", "Video, audio and input over the tunnel", 1)
        } else if self.files_session {
            ("Connected for files", "The tunnel and the phone's helper are up for Phone files. Closing that window ends it.", 1)
        } else if self.phone_paused {
            ("Phone paused", "USB debugging off for banking apps", 2)
        } else if self.linked {
            (
                "Phone nearby",
                if self.phone_tunnel_on { "Bluetooth linked, and reachable from anywhere" } else { "Bluetooth linked · tunnel off, so no remote mirroring" },
                1,
            )
        } else if !self.settings.use_bluetooth {
            ("Phone not nearby", "Bluetooth is switched off in Bridge's settings.", 0)
        } else if self.no_adapter {
            ("No Bluetooth on this PC", "Notifications and clipboard need Bluetooth. Mirroring still works if the phone's tunnel is on (Anywhere).", 2)
        } else if self.pc_bluetooth == Some(false) {
            ("Bluetooth is off", "Turn it on in Quick Settings (Win+A) for notifications and clipboard. Mirroring still works if the phone's tunnel is on.", 2)
        } else {
            ("Looking for your phone", "Searching over Bluetooth. Mirroring still works if its tunnel is on.", 0)
        };
        ui.set_reach_title(title.into());
        ui.set_reach_detail(if self.usb_busy { self.settings_window.global::<SettingsState>().get_usb_status() } else { detail.into() });
        ui.set_reach_tint(tint);

        ui.set_screen(Capability {
            name: "Screen".into(),
            detail: if self.mirroring { "Live" } else if self.phone_paused { "Paused for banking" } else if self.phone_tunnel_on { "Ready to mirror" } else if self.linked { "Click Mirror: the tunnel comes up over Bluetooth" } else { "Needs the phone's tunnel, or Bluetooth to wake it" }.into(),
            state: if self.mirroring || self.phone_tunnel_on { 2 } else if self.linked { 1 } else { 0 },
        });
        let notif_on = self.settings.mirror_notifications;
        ui.set_notifications(Capability {
            name: "Notifications".into(),
            detail: if !notif_on { "Off in Settings" } else if self.linked { "Over Bluetooth" } else if self.pc_bluetooth == Some(false) { "Bluetooth is off on this PC" } else { "When the phone is nearby" }.into(),
            state: if notif_on && self.linked { 2 } else { 0 },
        });
        let clip_on = self.settings.sync_clipboard;
        ui.set_clipboard(Capability {
            name: "Clipboard".into(),
            detail: if !clip_on { "Off in Settings" } else if self.linked { "Both ways, over Bluetooth" } else if self.pc_bluetooth == Some(false) { "Bluetooth is off on this PC" } else { "When the phone is nearby" }.into(),
            state: if clip_on && self.linked { 2 } else { 0 },
        });

        // Settings window mirrors the model.
        let st = self.settings_window.global::<SettingsState>();
        st.set_launch_at_login(self.settings.launch_at_login);
        st.set_use_bluetooth(self.settings.use_bluetooth);
        st.set_mirror_notifications(self.settings.mirror_notifications);
        st.set_notifications_anywhere(self.settings.notifications_anywhere);
        st.set_sync_clipboard(self.settings.sync_clipboard);
        st.set_background_clipboard(self.settings.background_clipboard);
        st.set_keep_ready(self.settings.keep_ready);
        st.set_max_size_index(match self.settings.max_size { 720 => 0, 1024 => 1, 1280 => 2, 1600 => 3, _ => 4 });
        st.set_bitrate_mbps(self.settings.bitrate_mbps);
        st.set_turn_screen_off(self.settings.turn_screen_off);
        st.set_mute_phone(self.settings.mute_phone);
        st.set_usb_busy(self.usb_busy);
        let t = &self.creds.ticket;
        st.set_ticket_short(if t.is_empty() { "Not paired".into() } else if t.len() > 28 { format!("{}…{}", &t[..14], &t[t.len() - 8..]).into() } else { t.clone().into() });
    }

    /// The Settings window's switches → the model, saved and applied.
    fn settings_changed(&mut self) {
        let st = self.settings_window.global::<SettingsState>();
        let before = self.settings.clone();
        self.settings.launch_at_login = st.get_launch_at_login();
        self.settings.use_bluetooth = st.get_use_bluetooth();
        self.settings.mirror_notifications = st.get_mirror_notifications();
        self.settings.notifications_anywhere = st.get_notifications_anywhere();
        self.settings.sync_clipboard = st.get_sync_clipboard();
        self.settings.background_clipboard = st.get_background_clipboard();
        self.settings.max_size = [720, 1024, 1280, 1600, 0][st.get_max_size_index().clamp(0, 4) as usize];
        self.settings.bitrate_mbps = st.get_bitrate_mbps();
        self.settings.turn_screen_off = st.get_turn_screen_off();
        self.settings.mute_phone = st.get_mute_phone();
        let keep = st.get_keep_ready();
        if keep != self.settings.keep_ready {
            self.settings.keep_ready = keep;
            self.settings.keep_ready_changed_at = chrono::Utc::now().timestamp_millis();
            if let Some(b) = &self.ble {
                let _ = b.send_command(&format!("keep {} at={}", if keep { "on" } else { "off" }, self.settings.keep_ready_changed_at));
            }
        }
        if self.settings != before {
            self.settings.save();
            if before.use_bluetooth != self.settings.use_bluetooth {
                if self.settings.use_bluetooth {
                    self.start_bluetooth();
                } else if let Some(mut b) = self.ble.take() {
                    b.stop();
                    self.linked = false;
                }
            }
            self.refresh();
        }
    }

    /// "keep ready" from the phone's status: the side that changed it more
    /// recently wins, so neither device silently overwrites the other.
    fn reconcile_keep_ready(&mut self, phone_value: bool, phone_changed_at: i64) {
        if phone_changed_at >= self.settings.keep_ready_changed_at {
            if phone_value != self.settings.keep_ready {
                self.settings.keep_ready = phone_value;
                self.settings.keep_ready_changed_at = phone_changed_at;
                self.settings.save();
                self.log("", &format!("took \"keep ready\" = {phone_value} from the phone (changed there more recently)"));
            }
        } else if phone_value != self.settings.keep_ready {
            if let Some(b) = &self.ble {
                let _ = b.send_command(&format!("keep {} at={}", if self.settings.keep_ready { "on" } else { "off" }, self.settings.keep_ready_changed_at));
            }
        }
    }

    // MARK: - Pairing and Bluetooth

    fn start_bluetooth(&mut self) {
        if !self.creds.is_paired() || !self.settings.use_bluetooth {
            self.refresh();
            return;
        }
        let mut link = ble::Ble::new(&self.creds.secret, self.events_tx.clone());
        match link.start() {
            Ok(()) => {
                self.ble = Some(link);
                self.retry_ble_at = None;
            }
            Err(e) => {
                if self.pc_bluetooth == Some(false) {
                    // Off: the radio watcher starts the link when it's switched on.
                    self.log("bluetooth", "Bluetooth is off on this PC; waiting for it to be turned on");
                } else {
                    // At login the radio can take a while to come up (0x800710DF,
                    // "the device is not ready"); don't give up on it.
                    self.log("bluetooth", &format!("couldn't start: {e:#}; trying again in 10 s"));
                    self.retry_ble_at = Some(Instant::now() + Duration::from_secs(10));
                }
            }
        }
        self.refresh();
    }

    fn pair_from_clipboard(&mut self) {
        let text = arboard::Clipboard::new().ok().and_then(|mut c| c.get_text().ok()).unwrap_or_default();
        match store::Credentials::parse(&text) {
            Some(creds) => {
                self.adopt(creds, "from the clipboard");
            }
            None => {
                self.log("", "the clipboard doesn't hold a Bridge ticket (expected \"endpoint… <secret>\")");
                notify::show("Bridge", "Nothing to pair with", "Tap Copy under Ticket on the phone first, then try again.");
            }
        }
    }

    fn unpair(&mut self) {
        self.disconnect();
        let _ = store::clear();
        ble::Ble::unpair_all();
        self.creds = store::Credentials::default();
        if let Some(b) = self.ble.as_mut() {
            b.stop();
        }
        self.ble = None;
        self.linked = false;
        self.log("", "unpaired");
        self.refresh();
    }

    fn rescan(&mut self) {
        match self.ble.as_mut() {
            Some(b) => {
                if let Err(e) = b.start() {
                    self.log("bluetooth", &format!("rescan failed: {e:#}"));
                }
            }
            None => self.start_bluetooth(),
        }
    }

    fn set_up_over_usb(&mut self) {
        if self.usb_busy || self.mirroring || self.connecting {
            return;
        }
        self.usb_busy = true;
        self.error = None;
        self.settings_window.global::<SettingsState>().set_usb_status("Looking for your phone on USB…".into());
        self.log("usb", "Set up over USB");
        usb::set_up(self.events_tx.clone());
        self.refresh();
    }

    /// New credentials, from USB or a pasted ticket: store them and relink.
    fn adopt(&mut self, creds: store::Credentials, how: &str) -> bool {
        if let Err(e) = store::save(&creds) {
            self.log("", &format!("couldn't save credentials: {e:#}"));
            return false;
        }
        let same = creds.ticket == self.creds.ticket && creds.secret == self.creds.secret;
        self.creds = creds;
        self.error = None;
        self.log("", &format!("paired {how}{}", if same { " (same ticket as before)" } else { "" }));
        if same && self.ble.is_some() {
            return true; // nothing to relink; a link in progress stays
        }
        if let Some(b) = self.ble.as_mut() {
            b.stop();
        }
        self.ble = None;
        self.linked = false;
        self.start_bluetooth();
        true
    }

    // MARK: - Phone files

    fn open_files(&mut self) {
        let _ = self.files_window.show();
        let path = self.files_path.clone();
        self.list_files(&path);
    }

    fn list_files(&mut self, path: &str) {
        let fw = &self.files_window;
        if !self.mirroring && !self.files_session {
            // No session: bring the tunnel and helper up just for files (no
            // video), and list once they're there. See connect_with().
            fw.set_path("Phone storage".into());
            if self.connecting {
                fw.set_status("Connecting to the phone…".into());
            } else if !self.creds.is_paired() {
                fw.set_status("Pair your phone first.".into());
            } else {
                fw.set_status("Connecting to the phone…".into());
                self.files_path = path.to_string();
                self.connect_with(true);
            }
            return;
        }
        fw.set_loading(true);
        fw.set_status("Loading…".into());
        let (secret, tx, path) = (self.creds.secret.clone(), self.events_tx.clone(), path.to_string());
        std::thread::spawn(move || {
            let _ = tx.send(Event::FilesListed(files::list(&secret, &path).map_err(|e| format!("{e:#}"))));
        });
    }

    fn files_open(&mut self, index: usize) {
        let Some(entry) = self.files_entries.get(index).cloned() else { return };
        let full = format!("{}/{}", self.files_path.trim_end_matches('/'), entry.name);
        if entry.dir {
            self.list_files(&full);
        } else {
            self.files_window.set_status(format!("Downloading {}…", entry.name).into());
            let (secret, tx, lock) = (self.creds.secret.clone(), self.events_tx.clone(), self.transfer_lock.clone());
            std::thread::spawn(move || {
                let _guard = lock.lock();
                let name = entry.name.clone();
                let ptx = tx.clone();
                let result = files::pull(&secret, &full, |done, total| {
                    let frac = if total > 0 { done as f32 / total as f32 } else { 1.0 };
                    let _ = ptx.send(Event::FileProgress(format!("Downloading {name} · {} of {}", files::human_size(done), files::human_size(total)), frac));
                });
                let _ = tx.send(Event::FileDone(
                    result.map(|p| format!("Saved {} to Downloads", p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default())).map_err(|e| format!("{e:#}")),
                ));
            });
        }
    }

    fn files_up(&mut self) {
        if let Some((parent, _)) = self.files_path.rsplit_once('/') {
            if !parent.is_empty() {
                let parent = parent.to_string();
                self.list_files(&parent);
            }
        }
    }

    fn show_listing(&mut self, resolved: String, entries: Vec<files::Entry>) {
        const ROOT: &str = "/storage/emulated/0";
        let fw = &self.files_window;
        fw.set_loading(false);
        let shown = match resolved.strip_prefix(ROOT) {
            Some("") => "Phone storage".to_string(),
            Some(rest) => format!("Phone storage{rest}").replace('/', " › "),
            None => resolved.clone(),
        };
        fw.set_path(shown.into());
        fw.set_at_top(resolved == ROOT);
        let rows: Vec<FileEntry> = entries
            .iter()
            .map(|e| {
                let when = chrono::DateTime::from_timestamp_millis(e.modified_ms)
                    .map(|d| d.with_timezone(&chrono::Local).format("%-d %b %Y, %H:%M").to_string())
                    .unwrap_or_default();
                FileEntry {
                    name: e.name.clone().into(),
                    detail: if e.dir { when.into() } else { format!("{} · {when}", files::human_size(e.size)).into() },
                    is_dir: e.dir,
                }
            })
            .collect();
        fw.set_entries(std::rc::Rc::new(slint::VecModel::from(rows)).into());
        fw.set_status(if entries.is_empty() { "Empty folder".into() } else { format!("{} items", entries.len()).into() });
        self.files_path = resolved;
        self.files_entries = entries;
    }

    /// A progress bar on the files window and, while mirroring, over the
    /// bottom of the phone window, like Blip's.
    fn show_transfer(&mut self, text: &str, frac: f32) {
        self.files_window.set_status(text.into());
        self.files_window.set_progress(frac);
        if let Some(w) = &self.mirror_window {
            w.set_transfer_text(text.into());
            w.set_transfer_progress(frac);
            w.set_transfer_visible(true);
        }
    }

    /// The result stays on the bar for a few seconds, then the bar goes.
    fn end_transfer(&mut self, text: String) {
        self.files_window.set_progress(-1.0);
        if let Some(w) = &self.mirror_window {
            w.set_transfer_text(text.into());
            w.set_transfer_progress(1.0);
            let weak = w.as_weak();
            Timer::single_shot(Duration::from_secs(3), move || {
                if let Some(w) = weak.upgrade() {
                    w.set_transfer_visible(false);
                }
            });
        }
    }

    // MARK: - Tunnel and mirroring

    fn toggle_phone_tunnel(&mut self) {
        let on = !self.phone_tunnel_on;
        if let Some(b) = &self.ble {
            let _ = b.send_command(if on { "tunnel on" } else { "tunnel off" });
            self.log("bluetooth", if on { "asked the phone to start its tunnel" } else { "asked the phone to stop its tunnel" });
        }
    }

    fn pause_phone(&mut self) {
        if let Some(b) = &self.ble {
            let _ = b.send_command("pause 15");
            self.log("phone", "asked the phone to pause USB debugging for 15 minutes");
        }
    }

    fn wake_phone_tunnel(&mut self) {
        if self.woke_tunnel {
            return;
        }
        if let Some(b) = &self.ble {
            if b.send_command("tunnel on").is_ok() {
                self.woke_tunnel = true;
                self.log("bluetooth", "waking the phone's tunnel");
            }
        }
    }

    /// Mirror Phone: wake the tunnel if needed, connect, START.
    fn connect(&mut self) {
        if self.files_session && !self.mirroring {
            // The tunnel and helper are already up for the files window:
            // just open the video.
            match self.open_session() {
                Ok(()) => self.mirroring = true,
                Err(e) => {
                    self.log("", &format!("couldn't start mirroring: {e:#}"));
                    self.error = Some(("Couldn't mirror".into(), format!("{e:#}"), false));
                }
            }
            self.refresh();
            return;
        }
        self.connect_with(false);
    }

    /// Tunnel up (waking it over Bluetooth if needed), then START the helper.
    /// `for_files`: stop there, for the files window, instead of opening video.
    fn connect_with(&mut self, for_files: bool) {
        if self.mirroring || self.tunnel.is_some() {
            return;
        }
        use std::sync::atomic::Ordering;
        let attempt = self.attempt.fetch_add(1, Ordering::SeqCst) + 1;
        self.connect_for_files = for_files;
        self.connecting = true;
        self.error = None;
        self.woke_tunnel = false;
        self.window.global::<AppState>().set_busy(true);
        self.refresh();
        let need_wake = self.linked && !self.phone_tunnel_on;
        if !self.linked {
            // We can't see the phone's state without Bluetooth; say what's needed
            // rather than waiting half a minute for a tunnel that may be off.
            // If the link comes up while we're trying, handle() wakes it then.
            self.log("", "no Bluetooth link yet: mirroring needs the phone's tunnel, or Bluetooth to wake it");
        }
        if need_wake {
            self.wake_phone_tunnel();
        }
        let ticket = self.creds.ticket.clone();
        match tunnel::Tunnel::start(&ticket) {
            Ok(t) => self.tunnel = Some(t),
            Err(e) => {
                self.log("tunnel", &format!("{e:#}"));
                self.fail(format!("{e:#}"));
                return;
            }
        }
        // START over the tunnel, on a thread: dumbpipe needs a moment to find the phone.
        let secret = self.creds.secret.clone();
        let events = self.events_tx.clone();
        let current = self.attempt.clone();
        std::thread::spawn(move || {
            if need_wake {
                std::thread::sleep(Duration::from_secs(4)); // let the phone reach a relay
            }
            let mut result = Err("the phone didn't answer over the tunnel".to_string());
            for try_ in 1..=12 {
                std::thread::sleep(Duration::from_secs(2));
                if current.load(Ordering::SeqCst) != attempt {
                    return; // cancelled, or superseded by a newer Connect
                }
                match tunnel::control(&secret, "START", Duration::from_secs(40)) {
                    Ok(r) if r.starts_with("OK") => { result = Ok(r); break; }
                    Ok(r) => { crate::log!("phone", "START: {r}"); result = Err(r); break; }
                    Err(e) => crate::log!("phone", "START {try_}/12: {e:#}"),
                }
            }
            let _ = events.send(Event::Connected(attempt, result));
        });
    }

    /// The video/audio/control streams and the window they show in.
    fn open_session(&mut self) -> anyhow::Result<()> {
        let st = &self.settings;
        let mut options = format!("max_size={} video_bit_rate={}000000", st.max_size, st.bitrate_mbps);
        if st.turn_screen_off {
            options.push_str(" power_off_on_close=false");
        }
        // "playback" capture takes the audio away from the speaker (Android 13+);
        // audio_dup gives it back, i.e. the phone keeps playing too.
        options.push_str(if st.mute_phone { " audio_source=playback" } else { " audio_source=playback audio_dup=true" });

        let (tx, rx) = mpsc::channel();
        let tx_close = tx.clone();
        let window = MirrorWindow::new()?;
        // Frames land on the UI thread straight from the video thread. If the
        // UI hasn't drawn the last one yet, the newer frame replaces it: we
        // want the latest picture, not every picture.
        let pending: std::sync::Arc<std::sync::Mutex<Option<video::Frame>>> = Default::default();
        let sink_pending = pending.clone();
        let sink_window = window.as_weak();
        let frames: session::FrameSink = std::sync::Arc::new(move |f: video::Frame| {
            let was_empty = { let mut p = sink_pending.lock().unwrap(); let e = p.is_none(); *p = Some(f); e };
            if !was_empty {
                return; // a draw is already queued; it will pick up this newer frame
            }
            let pending = sink_pending.clone();
            let _ = sink_window.upgrade_in_event_loop(move |win| {
                if let Some(f) = pending.lock().unwrap().take() {
                    let mut buf = slint::SharedPixelBuffer::<slint::Rgba8Pixel>::new(f.width, f.height);
                    buf.make_mut_bytes().copy_from_slice(&f.rgba);
                    win.set_frame(slint::Image::from_rgba8(buf));
                }
            });
        });
        let session = session::Session::start(&self.creds.secret, &options, tx, frames)?;
        if st.turn_screen_off {
            session.send(&scrcpy::display_power(false));
        }

        // Input → scrcpy control messages. The window reports image-pixel
        // coordinates; the phone wants them relative to the video size it sent.
        let s = Rc::new(RefCell::new(None::<std::sync::Arc<session::Session>>));
        let session = std::sync::Arc::new(session);
        *s.borrow_mut() = Some(session.clone());
        let size = Rc::new(RefCell::new((0u16, 0u16)));
        let (s1, z1) = (s.clone(), size.clone());
        let first_input = Rc::new(std::cell::Cell::new(true));
        window.on_pointer(move |x, y, kind, button| {
            let Some(sess) = s1.borrow().clone() else { return };
            let (w, h) = *z1.borrow();
            if first_input.replace(false) {
                crate::log!("input", "first pointer event at ({x}, {y}) on a {w}×{h} video");
            }
            if w == 0 {
                crate::log!("input", "pointer dropped: the video size isn't known yet");
                return;
            }
            let p = scrcpy::Position { x, y, width: w, height: h };
            let msg = match (kind, button) {
                (0, 1) => scrcpy::back(true),
                (1, 1) => scrcpy::back(false),
                (0, _) => scrcpy::touch(scrcpy::ACTION_DOWN, p, true),
                (1, _) => scrcpy::touch(scrcpy::ACTION_UP, p, false),
                (2, _) => scrcpy::touch(scrcpy::ACTION_MOVE, p, true),
                _ => scrcpy::hover(p),
            };
            sess.send(&msg);
        });
        let (s2, z2) = (s.clone(), size.clone());
        window.on_scroll(move |x, y, dx, dy| {
            let Some(sess) = s2.borrow().clone() else { return };
            let (w, h) = *z2.borrow();
            if w == 0 { return; }
            sess.send(&scrcpy::scroll(scrcpy::Position { x, y, width: w, height: h }, dx / 20.0, dy / 20.0));
        });
        let s3 = s.clone();
        window.on_key(move |text, mods, down| {
            let Some(sess) = s3.borrow().clone() else { return };
            let text = text.to_string();
            let ctrl = mods & 1 != 0;
            let shift = mods & 2 != 0;
            let alt = mods & 4 != 0;
            use scrcpy::android_key as k;
            // Ctrl+letter shortcuts, the Mac's ⌘ ones.
            if ctrl {
                let target = match text.as_str() {
                    "b" => Some(k::BACK), "h" => Some(k::HOME), "r" => Some(k::APP_SWITCH), "p" => Some(k::POWER),
                    _ => None,
                };
                if let Some(code) = target {
                    sess.send(&scrcpy::key(down, code, 0));
                    return;
                }
                if text == "n" && down { sess.send(&scrcpy::simple(scrcpy::EXPAND_NOTIFICATION_PANEL)); return; }
                if text == "o" && down { sess.send(&scrcpy::display_power(false)); return; }
            }
            // Special keys arrive as private-use characters in Slint's key text.
            let special = match text.chars().next() {
                Some('\u{F700}') => Some(k::DPAD_UP), Some('\u{F701}') => Some(k::DPAD_DOWN),
                Some('\u{F702}') => Some(k::DPAD_LEFT), Some('\u{F703}') => Some(k::DPAD_RIGHT),
                Some('\n') | Some('\r') => Some(k::ENTER), Some('\u{8}') => Some(k::DEL), Some('\u{7F}') => Some(k::FORWARD_DEL),
                Some('\u{1B}') => Some(k::ESCAPE), Some('\t') => Some(k::TAB),
                Some('\u{F729}') => Some(k::MOVE_HOME), Some('\u{F72B}') => Some(k::MOVE_END),
                Some('\u{F72C}') => Some(k::PAGE_UP), Some('\u{F72D}') => Some(k::PAGE_DOWN),
                _ => None,
            };
            let meta = (if shift { k::META_SHIFT } else { 0 }) | (if ctrl { k::META_CTRL } else { 0 }) | (if alt { k::META_ALT } else { 0 });
            if let Some(code) = special {
                sess.send(&scrcpy::key(down, code, meta));
                return;
            }
            let mut chars = text.chars();
            if let (Some(c), None) = (chars.next(), chars.next()) {
                if let Some(code) = k::for_char(c) {
                    // Letters and digits as keycodes so shortcuts and the PIN pad work.
                    let m = meta | if c.is_ascii_uppercase() { k::META_SHIFT } else { 0 };
                    sess.send(&scrcpy::key(down, code, m));
                    return;
                }
                if down && !c.is_control() && !ctrl && !alt {
                    sess.send(&scrcpy::text(&text));
                }
            } else if down && !text.is_empty() && !ctrl && !alt {
                sess.send(&scrcpy::text(&text));
            }
        });
        // Closing the window ends the session, via the same path a dead stream takes.
        let closer = tx_close.clone();
        window.window().on_close_requested(move || {
            let _ = closer.send(SessionEvent::Ended("window closed".into()));
            slint::CloseRequestResponse::HideWindow
        });
        accept_drops(window.window(), self.creds.secret.clone(), self.events_tx.clone(), self.transfer_lock.clone());
        let _ = window.show();
        self.session_events = Some(rx);
        self.session = Some(session);
        self.mirror_window = Some(window);
        self.mirror_size = Some(size);
        self.last_synced = None;
        Ok(())
    }

    fn fail(&mut self, message: String) {
        self.connecting = false;
        // We switched the phone's tunnel on for this; it costs battery and
        // data all the while, so put it back if nothing came of it.
        if self.woke_tunnel && self.linked {
            if let Some(b) = &self.ble {
                let _ = b.send_command("tunnel off");
                self.log("bluetooth", "asked the phone to stop its tunnel again");
            }
        }
        self.woke_tunnel = false;
        self.window.global::<AppState>().set_busy(false);
        if self.connect_for_files {
            self.files_window.set_loading(false);
            self.files_window.set_status(format!("Couldn't connect: {message}").into());
        }
        self.error = Some(("Couldn't connect".into(), message, false));
        if let Some(mut t) = self.tunnel.take() {
            t.stop();
        }
        self.refresh();
    }

    fn disconnect(&mut self) {
        if !self.mirroring && !self.connecting && !self.files_session && self.tunnel.is_none() {
            return;
        }
        // A cancelled connect may have STARTed the helper; so has a files session.
        let was_mirroring = self.mirroring || self.connecting || self.files_session;
        self.mirroring = false;
        self.connecting = false;
        self.files_session = false;
        self.woke_tunnel = false;   // "session over" below settles the tunnel
        self.files_window.set_status("Disconnected from the phone. Refresh to connect again.".into());
        self.files_window.set_loading(false);
        if let Some(sess) = self.session.take() {
            sess.stop();
        }
        self.session_events = None;
        if let Some(w) = self.mirror_window.take() {
            let _ = w.hide();
        }
        self.mirror_size = None;
        self.attempt.fetch_add(1, std::sync::atomic::Ordering::SeqCst);   // stops any in-flight START thread
        self.window.global::<AppState>().set_busy(false);
        let tunnel = self.tunnel.take();
        if was_mirroring && self.linked {
            if let Some(b) = &self.ble {
                // The phone stops the helper and, in Nearby mode, its tunnel.
                let _ = b.send_command("session over");
            }
            self.log("phone", "told the phone over Bluetooth");
            if let Some(mut t) = tunnel {
                t.stop();
            }
        } else {
            // No Bluetooth: STOP has to go through the tunnel, so the tunnel
            // must outlive the request. Send, wait for the reply, then stop it.
            let secret = self.creds.secret.clone();
            let was = was_mirroring;
            std::thread::spawn(move || {
                let mut tunnel = tunnel;
                if was {
                    match tunnel::control(&secret, "STOP", Duration::from_secs(10)) {
                        Ok(r) => crate::log!("phone", "{r}"),
                        Err(e) => crate::log!("phone", "couldn't reach the phone to stop: {e:#}"),
                    }
                    // The phone switches its own tunnel off a moment after replying.
                    std::thread::sleep(Duration::from_millis(2500));
                }
                if let Some(t) = tunnel.as_mut() {
                    t.stop();
                }
            });
        }
        self.refresh();
    }

    // MARK: - Events

    fn poll(&mut self) {
        while let Ok(event) = self.events.try_recv() {
            self.handle(event);
        }
        self.show_log();   // lines logged from other threads since the last tick
        self.poll_session();
        if self.settings.sync_clipboard && (self.linked || self.mirroring) {
            if let Some(text) = self.clipboard.poll() {
                if self.last_synced.as_deref() == Some(text.as_str()) {
                    // our own write coming back
                } else if let Some(sess) = &self.session {
                    // In a session the clipboard rides scrcpy's control socket: free.
                    self.last_synced = Some(text.clone());
                    sess.send(&scrcpy::clipboard(&text));
                    self.log("clipboard", "sent to the phone (session)");
                } else if let Some(b) = &self.ble {
                    match b.send(Kind::Clipboard, &text) {
                        Ok(()) => self.log("clipboard", "sent to the phone"),
                        Err(e) => self.log("clipboard", &format!("couldn't send: {e:#}")),
                    }
                }
            }
        }
        if let Some(at) = self.retry_ble_at {
            if Instant::now() >= at && self.ble.is_none() {
                self.retry_ble_at = None;
                self.start_bluetooth();
            }
        }
        if let Some(at) = self.rescan_at {
            if Instant::now() >= at {
                self.rescan_at = None;
                if let Some(b) = self.ble.as_mut() {
                    if !b.is_linked() {
                        if let Err(e) = b.start() {
                            self.log("bluetooth", &format!("rescan failed: {e:#}"));
                        }
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
            if let Some(t) = self.tunnel.as_mut() {
                if !t.is_running() {
                    self.log("tunnel", "dumbpipe exited");
                    self.tunnel = None;
                    if self.mirroring || self.connecting || self.files_session {
                        // Close the session and its window properly (this used
                        // to leave both up, with disconnect() then refusing to
                        // run because it saw no tunnel and no mirroring).
                        self.disconnect();
                        self.fail("the tunnel dropped".into());
                    }
                }
            }
        }
    }

    /// Frames, size changes, clipboard and the end of the stream.
    fn poll_session(&mut self) {
        let Some(rx) = self.session_events.as_ref() else { return };
        let mut ended = None;
        let mut others = Vec::new();
        while let Ok(e) = rx.try_recv() {
            match e {
                SessionEvent::Ended(why) => { ended = Some(why); break; }
                other => others.push(other),
            }
        }
        for e in others {
            match e {
                SessionEvent::Size(w, h) => {
                    if let Some(cell) = &self.mirror_size {
                        *cell.borrow_mut() = (w as u16, h as u16);
                    }
                    if let Some(win) = &self.mirror_window {
                        win.set_video_width(w as f32);
                        win.set_video_height(h as f32);
                    }
                }
                SessionEvent::Clipboard(text) => {
                    if self.settings.sync_clipboard && self.last_synced.as_deref() != Some(text.as_str()) {
                        self.last_synced = Some(text.clone());
                        self.clipboard.set(&text);
                        self.log("clipboard", "from the phone (session)");
                    }
                }
                SessionEvent::Log(line) => self.log("video", &line),
                SessionEvent::Device(name) => {
                    if let Some(win) = &self.mirror_window {
                        win.set_phone_name(name.into());
                    }
                }
                _ => {}
            }
        }
        if let Some(why) = ended {
            self.log("", &format!("mirroring ended: {why}"));
            self.disconnect();
            self.refresh();
        }
    }

    fn handle(&mut self, event: Event) {
        match event {
            Event::Searching => {
                self.linked = false;
                self.searching = true;
                self.log("bluetooth", "looking for the phone");
            }
            Event::Linked => {
                self.linked = true;
                self.searching = false;
                self.icons_requested.clear();
                self.log("bluetooth", "linked to the phone");
                self.report_twins(true);
                if let Some(b) = &self.ble {
                    let _ = b.send_command("status");
                }
            }
            Event::Dropped(why) => {
                self.linked = false;
                self.last_twins = None;
                self.log("bluetooth", &format!("dropped ({why})"));
                self.rescan_at = Some(Instant::now() + Duration::from_secs(3));
            }
            Event::Notification { app, title, body, package } => {
                if self.settings.mirror_notifications {
                    self.log("notify", &format!("{app}: {title}"));
                    // First notification from an app: ask the phone for its
                    // icon (96 px PNG over Bluetooth, cached for good). This
                    // toast goes out without it; the next one has it.
                    if !package.is_empty() && !notify::icon_path(&package).exists() && self.icons_requested.insert(package.clone()) {
                        if let Some(b) = &self.ble {
                            let _ = b.send_command(&format!("icon {package}"));
                        }
                    }
                    notify::show_with_icon(&app, &title, &body, if package.is_empty() { None } else { Some(&package) });
                }
            }
            Event::Icon { package, png } => {
                let _ = std::fs::create_dir_all(notify::icon_dir());
                match std::fs::write(notify::icon_path(&package), &png) {
                    Ok(()) => self.log("notify", &format!("cached the icon for {package}")),
                    Err(e) => self.log("notify", &format!("couldn't cache the icon for {package}: {e}")),
                }
            }
            Event::FilesListed(result) => {
                match result {
                    Ok((resolved, entries)) => self.show_listing(resolved, entries),
                    Err(e) => {
                        self.files_window.set_loading(false);
                        self.files_window.set_status(format!("Couldn't list: {e}").into());
                        self.log("files", &e);
                    }
                }
                return;
            }
            Event::Radio(state) => {
                self.no_adapter = state.is_none();
                let was = self.pc_bluetooth;
                self.pc_bluetooth = state;
                match state {
                    Some(false) if was != Some(false) => {
                        self.log("bluetooth", "Bluetooth was turned off on this PC");
                        self.linked = false;
                        self.retry_ble_at = None;
                    }
                    Some(true) if was == Some(false) => {
                        self.log("bluetooth", "Bluetooth is back on; reconnecting");
                        if let Some(mut b) = self.ble.take() {
                            b.stop();
                        }
                        self.start_bluetooth();
                    }
                    None => self.log("bluetooth", "this PC has no Bluetooth adapter"),
                    _ => {}
                }
            }
            Event::FileProgress(text, frac) => {
                self.show_transfer(&text, frac);
                return;
            }
            Event::FileDone(result) => {
                self.end_transfer(match &result { Ok(m) => m.clone(), Err(e) => format!("Failed: {e}") });
                match result {
                    Ok(msg) => {
                        self.log("files", &msg);
                        self.files_window.set_status(msg.clone().into());
                        // The files window or the phone window's bar shows it
                        // already; a toast only when neither is open.
                        if !self.files_window.window().is_visible() && self.mirror_window.is_none() {
                            notify::show("Bridge", "File transfer", &msg);
                        }
                        // A drop went into the phone's Download folder; if that's
                        // what's on screen, show it.
                        if self.files_path.ends_with("/Download") {
                            let p = self.files_path.clone();
                            self.list_files(&p);
                        }
                    }
                    Err(e) => {
                        self.log("files", &format!("transfer failed: {e}"));
                        self.files_window.set_status(format!("Failed: {e}").into());
                        notify::show("Bridge", "File transfer failed", &e);
                    }
                }
                return;
            }
            Event::UsbProgress(text) => {
                self.settings_window.global::<SettingsState>().set_usb_status(text.into());
            }
            Event::UsbDone(result) => {
                self.usb_busy = false;
                match result {
                    Ok(creds) => {
                        self.adopt(creds, "over USB");
                        let note = if self.settings.keep_ready {
                            "Set up, and the helper is running. Unplug and click Mirror phone."
                        } else {
                            "Set up. Unplug and click Mirror phone. After each session the phone locks down again; turn on \"Keep the phone ready\" to skip that."
                        };
                        self.settings_window.global::<SettingsState>().set_usb_status(note.into());
                        notify::show("Bridge", "Phone set up", note);
                    }
                    Err(e) => {
                        self.log("usb", &e);
                        self.settings_window.global::<SettingsState>().set_usb_status(e.clone().into());
                        self.error = Some(("USB setup didn't finish".into(), e, true));
                    }
                }
            }
            Event::Clipboard(text) => {
                if self.settings.sync_clipboard {
                    self.log("clipboard", &format!("from the phone ({} chars)", text.chars().count()));
                    self.clipboard.set(&text);
                }
            }
            Event::Status(fields) => {
                self.phone_tunnel_on = fields.get("tunnel").map(|v| v == "1").unwrap_or(false);
                self.phone_paused = fields.get("paused").map(|v| v == "1").unwrap_or(false);
                if self.connecting && !self.phone_tunnel_on {
                    // Bluetooth came up after Mirror was clicked: now we can see
                    // the tunnel is off, and switch it on.
                    self.wake_phone_tunnel();
                }
                if let Some(keep) = fields.get("keep") {
                    let at = fields.get("keepAt").and_then(|v| v.parse::<i64>().ok()).unwrap_or(0);
                    self.reconcile_keep_ready(keep == "1", at);
                }
            }
            Event::Connected(attempt, result) => {
                if attempt != self.attempt.load(std::sync::atomic::Ordering::SeqCst) {
                    // Cancelled while connecting. If it got through anyway and
                    // nothing newer wants the helper, undo it.
                    if result.is_ok() && !self.connecting && !self.mirroring {
                        self.log("phone", "a cancelled connect had started the helper; stopping it");
                        if let Some(b) = &self.ble { let _ = b.send_command("session over"); }
                    }
                    return;
                }
                match result {
                    Ok(reply) => {
                        self.log("phone", &reply);
                        if self.connect_for_files {
                            self.files_session = true;
                            self.connecting = false;
                            self.window.global::<AppState>().set_busy(false);
                            let p = self.files_path.clone();
                            self.list_files(&p);
                            self.refresh();
                            return;
                        }
                        if let Err(e) = self.open_session() {
                            self.log("", &format!("couldn't start mirroring: {e:#}"));
                            // Still "connecting" here, so disconnect() tells the phone
                            // the session is over and the helper it started can stop.
                            self.disconnect();
                            self.fail(format!("{e:#}"));
                            return;
                        }
                        self.mirroring = true;
                        self.connecting = false;
                        self.window.global::<AppState>().set_busy(false);
                    }
                    Err(e) => {
                        self.log("", &format!("couldn't connect: {e}"));
                        self.fail(e);
                        return;
                    }
                }
            }
        }
        self.refresh();
    }

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

/// Sends a dropped file to the phone's Download folder, on its own thread,
/// one transfer at a time. Used by the mirror window and the files window.
fn start_push(secret: String, tx: mpsc::Sender<Event>, lock: std::sync::Arc<std::sync::Mutex<()>>, path: std::path::PathBuf) {
    if path.is_dir() {
        let _ = tx.send(Event::FileDone(Err(format!("{} is a folder; drop files, not folders", path.display()))));
        return;
    }
    std::thread::spawn(move || {
        let _guard = lock.lock();
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        let ptx = tx.clone();
        let result = files::push(&secret, &path, |done, total| {
            let frac = if total > 0 { done as f32 / total as f32 } else { 1.0 };
            let _ = ptx.send(Event::FileProgress(format!("Sending {name} · {} of {}", files::human_size(done), files::human_size(total)), frac));
        });
        let _ = tx.send(Event::FileDone(result.map(|r| format!("{name}: {r}")).map_err(|e| format!("{name}: {e:#}"))));
    });
}

/// Files dragged from Explorer onto a window go to the phone. Slint has no
/// drop event of its own; winit does.
fn accept_drops(window: &slint::Window, secret: String, tx: mpsc::Sender<Event>, lock: std::sync::Arc<std::sync::Mutex<()>>) {
    use slint::winit_030::{winit::event::WindowEvent, EventResult, WinitWindowAccessor};
    window.on_winit_window_event(move |_, event| {
        if let WindowEvent::DroppedFile(path) = event {
            crate::log!("files", "dropped {}", path.display());
            start_push(secret.clone(), tx.clone(), lock.clone(), path.clone());
        }
        EventResult::Propagate
    });
}

/// The navy square with the white mark (assets/icon-32.png, from make-icons.sh).
fn tray_icon() -> tray_icon::Icon {
    let img = image::load_from_memory(include_bytes!("../assets/icon-32.png")).expect("tray icon png").into_rgba8();
    let (w, h) = img.dimensions();
    tray_icon::Icon::from_rgba(img.into_raw(), w, h).expect("icon")
}

/// One Bridge at a time. A second copy would link to the phone over
/// Bluetooth from the same PC (the phone saw two subscriptions from one
/// device and one failed its handshake) and fight the first over the tunnel
/// port. A second launch asks the running one to show its window, then quits.
/// Returns the "show yourself" event the running instance waits on.
fn single_instance() -> Option<windows::Win32::Foundation::HANDLE> {
    use windows::core::w;
    use windows::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows::Win32::System::Threading::{CreateEventW, CreateMutexW, OpenEventW, SetEvent, EVENT_MODIFY_STATE};
    unsafe {
        // Never closed: it lives as long as this process, which is the point.
        let _mutex = CreateMutexW(None, true, w!("Local\\Bridge-Bonevane-instance"));
        if GetLastError() == ERROR_ALREADY_EXISTS {
            if !std::env::args().any(|a| a == "--tray") {
                if let Ok(ev) = OpenEventW(EVENT_MODIFY_STATE, false, w!("Local\\Bridge-Bonevane-show")) {
                    let _ = SetEvent(ev);
                }
            }
            std::process::exit(0);
        }
        CreateEventW(None, false, false, w!("Local\\Bridge-Bonevane-show")).ok()
    }
}

fn main() {
    let show_event = single_instance();
    log::init();
    crate::log!("", "Bridge {VERSION} starting");

    let window = MainWindow::new().expect("window");
    let settings_window = SettingsWindow::new().expect("settings window");
    let files_window = FilesWindow::new().expect("files window");
    let app = Rc::new(RefCell::new(App::new(window.clone_strong(), settings_window.clone_strong(), files_window.clone_strong())));

    // Tray: left-click shows the window; the menu has Open, Settings and Quit.
    let menu = tray_icon::menu::Menu::new();
    let show = tray_icon::menu::MenuItem::new("Open Bridge", true, None);
    let settings_item = tray_icon::menu::MenuItem::new("Settings…", true, None);
    let quit = tray_icon::menu::MenuItem::new("Quit Bridge", true, None);
    let _ = menu.append_items(&[&show, &settings_item, &tray_icon::menu::PredefinedMenuItem::separator(), &quit]);
    let _tray = tray_icon::TrayIconBuilder::new()
        .with_menu(Box::new(menu))
        .with_tooltip("Bridge")
        .with_icon(tray_icon())
        .build()
        .expect("tray icon");

    // Main window callbacks.
    {
        let ui = window.global::<AppState>();
        let a = app.clone();
        ui.on_primary(move || {
            let mut app = a.borrow_mut();
            let busy = app.window.global::<AppState>().get_busy();
            if app.mirroring || busy {
                app.disconnect();
            } else if matches!(app.error, Some((_, _, true))) {
                app.set_up_over_usb();
            } else if !app.creds.is_paired() {
                app.pair_from_clipboard();
            } else {
                app.connect();
            }
        });
        let a = app.clone();
        ui.on_refresh(move || {
            let mut app = a.borrow_mut();
            if app.linked {
                if let Some(b) = &app.ble { let _ = b.send_command("status"); }
            } else {
                app.rescan();
            }
        });
        let a = app.clone();
        ui.on_pause_phone(move || a.borrow_mut().pause_phone());
        let a = app.clone();
        ui.on_toggle_phone_tunnel(move || a.borrow_mut().toggle_phone_tunnel());
        let a = app.clone();
        ui.on_set_up_over_usb(move || a.borrow_mut().set_up_over_usb());
        let a = app.clone();
        ui.on_open_files(move || a.borrow_mut().open_files());
        let sw = settings_window.as_weak();
        ui.on_open_settings(move || { if let Some(w) = sw.upgrade() { let _ = w.show(); } });
        ui.on_open_log_folder(move || {
            let _ = std::process::Command::new("explorer").arg(store::data_dir()).spawn();
        });
        let a = app.clone();
        ui.on_quit(move || {
            a.borrow_mut().disconnect();
            let _ = slint::quit_event_loop();
        });
    }

    // Settings window callbacks.
    {
        let st = settings_window.global::<SettingsState>();
        let a = app.clone();
        st.on_changed(move || a.borrow_mut().settings_changed());
        let a = app.clone();
        st.on_copy_ticket(move || {
            let app = a.borrow();
            if let Ok(mut c) = arboard::Clipboard::new() {
                let _ = c.set_text(format!("{} {}", app.creds.ticket, app.creds.secret));
            }
        });
        let sw = settings_window.as_weak();
        st.on_paste_ticket(move || {
            if let Some(w) = sw.upgrade() {
                let text = arboard::Clipboard::new().ok().and_then(|mut c| c.get_text().ok()).unwrap_or_default();
                let st = w.global::<SettingsState>();
                st.set_pasted(text.trim().into());
                st.set_pasted_status(if store::Credentials::parse(text.trim()).is_some() { "Looks right".into() } else { "Should be two words: endpoint… and the secret".into() });
            }
        });
        let a = app.clone();
        let sw = settings_window.as_weak();
        st.on_apply_pasted(move || {
            let Some(w) = sw.upgrade() else { return };
            let text = w.global::<SettingsState>().get_pasted().to_string();
            match store::Credentials::parse(&text) {
                Some(creds) => {
                    if a.borrow_mut().adopt(creds, "from the pasted ticket") {
                        w.global::<SettingsState>().set_pasted("".into());
                        w.global::<SettingsState>().set_pasted_status("Paired".into());
                    }
                }
                None => w.global::<SettingsState>().set_pasted_status("Should be two words: endpoint… and the secret".into()),
            }
        });
        let a = app.clone();
        st.on_set_up_over_usb(move || a.borrow_mut().set_up_over_usb());
        let a = app.clone();
        st.on_unpair(move || a.borrow_mut().unpair());
        let sw = settings_window.as_weak();
        settings_window.window().on_close_requested(move || {
            if let Some(w) = sw.upgrade() { let _ = w.hide(); }
            slint::CloseRequestResponse::HideWindow
        });
    }

    // Files window callbacks. Closing it just hides it.
    {
        let a = app.clone();
        files_window.on_open(move |i| a.borrow_mut().files_open(i as usize));
        let a = app.clone();
        files_window.on_up(move || a.borrow_mut().files_up());
        let a = app.clone();
        files_window.on_refresh(move || {
            let mut app = a.borrow_mut();
            let p = app.files_path.clone();
            app.list_files(&p);
        });
        files_window.on_open_downloads(|| {
            let _ = std::process::Command::new("explorer").arg(files::downloads_dir()).spawn();
        });
        // Drops need the secret at drop time (it changes on re-pairing), so
        // read it from the app then rather than capturing it now.
        let a = app.clone();
        {
            use slint::winit_030::{winit::event::WindowEvent, EventResult, WinitWindowAccessor};
            files_window.window().on_winit_window_event(move |_, event| {
                if let WindowEvent::DroppedFile(path) = event {
                    if let Ok(app) = a.try_borrow() {
                        if app.mirroring || app.files_session {
                            start_push(app.creds.secret.clone(), app.events_tx.clone(), app.transfer_lock.clone(), path.clone());
                        } else {
                            app.files_window.set_status("Not connected yet: wait for the folder to load, then drop again.".into());
                        }
                    }
                }
                EventResult::Propagate
            });
        }
        // Closing it ends a files-only session (tunnel and helper were up just
        // for it); a mirroring session carries on.
        let a = app.clone();
        files_window.window().on_close_requested(move || {
            if let Ok(mut app) = a.try_borrow_mut() {
                if app.files_session && !app.mirroring {
                    app.log("files", "files window closed; ending the files session");
                    app.disconnect();
                } else if app.connecting && app.connect_for_files {
                    app.disconnect();
                }
            }
            slint::CloseRequestResponse::HideWindow
        });
    }

    // Closing the window hides it; the tray brings it back.
    {
        let w = window.as_weak();
        window.window().on_close_requested(move || {
            if let Some(w) = w.upgrade() {
                let _ = w.hide();
            }
            slint::CloseRequestResponse::HideWindow
        });
    }

    {
        let mut a = app.borrow_mut();
        a.radio = ble::watch_radio(a.events_tx.clone());
        a.start_bluetooth();
        a.refresh();
    }

    // Drain events, the clipboard and the tray on the UI thread.
    let timer = Timer::default();
    {
        let a = app.clone();
        let w = window.as_weak();
        let sw = settings_window.as_weak();
        let tray_events = tray_icon::menu::MenuEvent::receiver();
        let tray_clicks = tray_icon::TrayIconEvent::receiver();
        timer.start(TimerMode::Repeated, Duration::from_millis(250), move || {
            a.borrow_mut().poll();
            // Minimise = to the tray, like close. Slint has no minimise
            // callback, so watch the state: un-minimise (so a later show()
            // brings a normal window back) and hide.
            if let Some(w) = w.upgrade() {
                if w.window().is_minimized() {
                    w.window().set_minimized(false);
                    let _ = w.hide();
                }
            }
            if let Some(s) = sw.upgrade() {
                if s.window().is_minimized() {
                    s.window().set_minimized(false);
                    let _ = s.hide();
                }
            }
            while let Ok(e) = tray_events.try_recv() {
                if e.id == show.id() {
                    if let Some(w) = w.upgrade() {
                        let _ = w.show();
                    }
                } else if e.id == settings_item.id() {
                    if let Some(s) = sw.upgrade() {
                        let _ = s.show();
                    }
                } else if e.id == quit.id() {
                    a.borrow_mut().disconnect();
                    let _ = slint::quit_event_loop();
                }
            }
            // Someone launched Bridge again: show this one instead.
            if let Some(ev) = show_event {
                use windows::Win32::Foundation::WAIT_OBJECT_0;
                use windows::Win32::System::Threading::WaitForSingleObject;
                if unsafe { WaitForSingleObject(ev, 0) } == WAIT_OBJECT_0 {
                    if let Some(w) = w.upgrade() {
                        let _ = w.show();
                    }
                }
            }
            while let Ok(e) = tray_clicks.try_recv() {
                if let tray_icon::TrayIconEvent::Click { button: tray_icon::MouseButton::Left, .. } = e {
                    if let Some(w) = w.upgrade() {
                        let _ = w.show();
                    }
                }
            }
        });
    }

    // Started at login (--tray): stay in the tray until clicked. The loop
    // must keep running with every window hidden, hence until_quit.
    if !std::env::args().any(|a| a == "--tray") {
        window.show().expect("window");
    }
    slint::run_event_loop_until_quit().expect("event loop");
    crate::log!("", "quit");
}
