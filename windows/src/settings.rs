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
                _ => {}
            }
        }
        s
    }

    pub fn save(&self) {
        let _ = std::fs::create_dir_all(crate::store::data_dir());
        let text = format!(
            "{{\n  \"launch_at_login\": {},\n  \"use_bluetooth\": {},\n  \"mirror_notifications\": {},\n  \"notifications_anywhere\": {},\n  \"sync_clipboard\": {},\n  \"background_clipboard\": {},\n  \"keep_ready\": {},\n  \"keep_ready_changed_at\": {},\n  \"max_size\": {},\n  \"bitrate_mbps\": {},\n  \"turn_screen_off\": {},\n  \"mute_phone\": {}\n}}\n",
            self.launch_at_login, self.use_bluetooth, self.mirror_notifications, self.notifications_anywhere,
            self.sync_clipboard, self.background_clipboard, self.keep_ready, self.keep_ready_changed_at,
            self.max_size, self.bitrate_mbps, self.turn_screen_off, self.mute_phone
        );
        let _ = std::fs::write(path(), text);
        apply_launch_at_login(self.launch_at_login);
    }
}

/// HKCU\Software\Microsoft\Windows\CurrentVersion\Run, the per-user autostart list.
fn apply_launch_at_login(on: bool) {
    let Ok(exe) = std::env::current_exe() else { return };
    // --tray: start in the tray without opening the window (see main.rs).
    let value = format!("\"{}\" --tray", exe.display());
    let key = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Run";
    let mut cmd = std::process::Command::new("reg");
    if on {
        cmd.args(["add", key, "/v", "Bridge", "/t", "REG_SZ", "/d", &value, "/f"]);
    } else {
        cmd.args(["delete", key, "/v", "Bridge", "/f"]);
    }
    use std::os::windows::process::CommandExt;
    let _ = cmd.creation_flags(0x0800_0000).stdout(std::process::Stdio::null()).stderr(std::process::Stdio::null()).status();
}
