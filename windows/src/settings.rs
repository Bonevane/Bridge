//! Bridge's settings, the same set as the Mac's Settings window, kept as
//! JSON in %APPDATA%\Bridge\settings.json. "Launch at login" is a Run key.

use std::path::PathBuf;

#[derive(Clone, Debug, PartialEq)]
pub struct Settings {
    pub launch_at_login: bool,
    pub use_bluetooth: bool,
    pub mirror_notifications: bool,
    pub notifications_anywhere: bool,
    pub sync_clipboard: bool,
    pub background_clipboard: bool,
    pub keep_ready: bool,
    /// Unix millis of the last change, for last-write-wins with the phone.
    pub keep_ready_changed_at: i64,
    pub max_size: i32,
    pub bitrate_mbps: i32,
    pub turn_screen_off: bool,
    pub mute_phone: bool,
    /// Clearing a notification on one side clears it on the other.
    pub sync_dismiss: bool,
}

impl Default for Settings {
    fn default() -> Self {
        // Free things on, paid things off; keep ready on (see the Mac).
        Settings {
            launch_at_login: false,
            use_bluetooth: true,
            mirror_notifications: true,
            notifications_anywhere: false,
            sync_clipboard: true,
            background_clipboard: false,
            keep_ready: true,
            keep_ready_changed_at: 0,
            max_size: 1280,
            bitrate_mbps: 4,
            turn_screen_off: false,
            mute_phone: false,
            sync_dismiss: true,
        }
    }
}

fn path() -> PathBuf {
    crate::store::data_dir().join("settings.json")
}

impl Settings {
    pub fn load() -> Settings {
        let Ok(text) = std::fs::read_to_string(path()) else { return Settings::default() };
        let mut s = Settings::default();
        // A tiny hand parser: `"key": value,` lines. Enough for flat bools/ints,
        // and no dependency for it.
        for line in text.lines() {
            let line = line.trim().trim_end_matches(',');
            let Some((k, v)) = line.split_once(':') else { continue };
            let k = k.trim().trim_matches('"');
            let v = v.trim();
            let b = v == "true";
            let n = v.parse::<i64>().unwrap_or(0);
            match k {
                "launch_at_login" => s.launch_at_login = b,
                "use_bluetooth" => s.use_bluetooth = b,
                "mirror_notifications" => s.mirror_notifications = b,
                "notifications_anywhere" => s.notifications_anywhere = b,
                "sync_clipboard" => s.sync_clipboard = b,
                "background_clipboard" => s.background_clipboard = b,
                "keep_ready" => s.keep_ready = b,
                "keep_ready_changed_at" => s.keep_ready_changed_at = n,
                "max_size" => s.max_size = n as i32,
                "bitrate_mbps" => s.bitrate_mbps = n as i32,
                "turn_screen_off" => s.turn_screen_off = b,
                "mute_phone" => s.mute_phone = b,
                "sync_dismiss" => s.sync_dismiss = b,
                _ => {}
            }
        }
        s
    }

    pub fn save(&self) {
        let _ = std::fs::create_dir_all(crate::store::data_dir());
        let text = format!(
            "{{\n  \"launch_at_login\": {},\n  \"use_bluetooth\": {},\n  \"mirror_notifications\": {},\n  \"notifications_anywhere\": {},\n  \"sync_clipboard\": {},\n  \"background_clipboard\": {},\n  \"keep_ready\": {},\n  \"keep_ready_changed_at\": {},\n  \"max_size\": {},\n  \"bitrate_mbps\": {},\n  \"turn_screen_off\": {},\n  \"mute_phone\": {},\n  \"sync_dismiss\": {}\n}}\n",
            self.launch_at_login, self.use_bluetooth, self.mirror_notifications, self.notifications_anywhere,
            self.sync_clipboard, self.background_clipboard, self.keep_ready, self.keep_ready_changed_at,
            self.max_size, self.bitrate_mbps, self.turn_screen_off, self.mute_phone, self.sync_dismiss
        );
        let _ = std::fs::write(path(), text);
        apply_launch_at_login(self.launch_at_login);
    }
}

/// HKCU\Software\Microsoft\Windows\CurrentVersion\Run, the per-user autostart list.
///
/// Written with the registry API, and only when the choice changes. It used
/// to run a hidden `reg.exe add …\Run` on every settings save: exactly the
/// "spawns a hidden process to install itself at startup" pattern antivirus
/// heuristics look for, and part of why Bridge.exe was flagged.
fn apply_launch_at_login(on: bool) {
    use std::sync::Mutex;
    use windows::core::{HSTRING, PCWSTR};
    use windows::Win32::System::Registry::{RegDeleteKeyValueW, RegSetKeyValueW, HKEY_CURRENT_USER, REG_SZ};
    static APPLIED: Mutex<Option<bool>> = Mutex::new(None);
    let mut applied = APPLIED.lock().unwrap();
    if *applied == Some(on) {
        return;
    }
    let key = HSTRING::from(r"Software\Microsoft\Windows\CurrentVersion\Run");
    let name = HSTRING::from("Bridge");
    unsafe {
        if on {
            let Ok(exe) = std::env::current_exe() else { return };
            // --tray: start in the tray without opening the window (see main.rs).
            let value: Vec<u16> = format!("\"{}\" --tray", exe.display()).encode_utf16().chain(Some(0)).collect();
            let _ = RegSetKeyValueW(
                HKEY_CURRENT_USER,
                PCWSTR(key.as_ptr()),
                PCWSTR(name.as_ptr()),
                REG_SZ.0,
                Some(value.as_ptr() as *const _),
                (value.len() * 2) as u32,
            );
        } else {
            let _ = RegDeleteKeyValueW(HKEY_CURRENT_USER, PCWSTR(key.as_ptr()), PCWSTR(name.as_ptr()));
        }
    }
    *applied = Some(on);
}
