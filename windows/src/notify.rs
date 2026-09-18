//! Phone notifications as Windows toasts.
//!
//! Unpackaged apps need an AppUserModelID registered in the Start Menu to
//! toast; the crate used here borrows PowerShell's, which works everywhere
//! without an installer. Good enough for now; an installer can register a
//! real one later so the toast carries Bridge's own name and icon.

use tauri_winrt_notification::{Duration, Toast};

pub fn show(app: &str, title: &str, body: &str) {
    show_with_icon(app, title, body, None);
}

/// Where the phone's app icons are cached, one PNG per package.
pub fn icon_dir() -> std::path::PathBuf {
    crate::store::data_dir().join("icons")
}

pub fn icon_path(package: &str) -> std::path::PathBuf {
    icon_dir().join(format!("{package}.png"))
}

pub fn show_with_icon(app: &str, title: &str, body: &str, package: Option<&str>) {
    // App name first, as on the Mac: it's the most useful thing to see.
    let mut toast = Toast::new(Toast::POWERSHELL_APP_ID).title(app).text1(title).text2(body).duration(Duration::Short);
    if let Some(p) = package {
        let path = icon_path(p);
        if path.exists() {
            toast = toast.icon(&path, tauri_winrt_notification::IconCrop::Circular, app);
        }
    }
    let result = toast.show();
    if let Err(e) = result {
        crate::log!("notify", "toast failed: {e}");
    }
}
