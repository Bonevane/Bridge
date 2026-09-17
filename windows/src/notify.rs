//! Phone notifications as Windows toasts.
//!
//! Unpackaged apps need an AppUserModelID registered in the Start Menu to
//! toast; the crate used here borrows PowerShell's, which works everywhere
//! without an installer. Good enough for now; an installer can register a
//! real one later so the toast carries Bridge's own name and icon.

use tauri_winrt_notification::{Duration, Toast};

pub fn show(app: &str, title: &str, body: &str) {
    // App name first, as on the Mac: it's the most useful thing to see.
    let result = Toast::new(Toast::POWERSHELL_APP_ID)
        .title(app)
        .text1(title)
        .text2(body)
        .duration(Duration::Short)
        .show();
    if let Err(e) = result {
        crate::log!("notify", "toast failed: {e}");
    }
}
