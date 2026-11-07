//! Fitting into the desktop: an entry in the app menu with Bridge's icon, and
//! launch at login, both plain `.desktop` files in the user's home.

use std::path::{Path, PathBuf};

fn home() -> PathBuf {
    std::env::var_os("HOME").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."))
}

fn entry(exe: &Path, args: &str) -> String {
    format!(
        "[Desktop Entry]\nType=Application\nName=Bridge\nComment=Your Android phone on this PC\n\
         Exec=\"{}\"{args}\nIcon=bridge\nCategories=Network;Utility;\nStartupWMClass=bridge\nTerminal=false\n",
        exe.display()
    )
}

/// Bridge ships as a folder you unzip anywhere, so it adds itself to the app
/// menu. It's also the way back in on desktops with no tray. Runs at every
/// start, which keeps the path right if the folder moves.
pub fn install_launcher(exe: &Path) {
    let share = std::env::var_os("XDG_DATA_HOME").map(PathBuf::from).unwrap_or_else(|| home().join(".local/share"));
    let icons = share.join("icons/hicolor/256x256/apps");
    let apps = share.join("applications");
    let wrote = std::fs::create_dir_all(&icons)
        .and_then(|_| std::fs::create_dir_all(&apps))
        .and_then(|_| std::fs::write(icons.join("bridge.png"), include_bytes!("../../assets/icon-256.png")))
        .and_then(|_| std::fs::write(apps.join("bridge.desktop"), entry(exe, "")));
    if let Err(e) = wrote {
        crate::log!("", "couldn't add Bridge to the app menu: {e}");
    }
}

pub fn set_launch_at_login(exe: &Path, on: bool) {
    let config = std::env::var_os("XDG_CONFIG_HOME").map(PathBuf::from).unwrap_or_else(|| home().join(".config"));
    let dir = config.join("autostart");
    let file = dir.join("bridge.desktop");
    if on {
        // --tray: start in the tray without opening the window (see main.rs).
        let wrote = std::fs::create_dir_all(&dir).and_then(|_| std::fs::write(&file, entry(exe, " --tray")));
        if let Err(e) = wrote {
            crate::log!("", "couldn't set launch at login: {e}");
        }
    } else {
        let _ = std::fs::remove_file(file);
    }
}
