//! Clipboard both ways. Windows has a proper change listener, but polling the
//! clipboard *sequence number* every half second is simpler, costs nothing,
//! and needs no window to receive messages on. Linux has no such number, so
//! there the text itself is compared.

use arboard::Clipboard;

pub struct Watcher {
    #[cfg(windows)]
    last_seq: u32,
    /// Kept for good on Linux: there, text stays pasteable only as long as
    /// the program that copied it holds the clipboard open.
    #[cfg(target_os = "linux")]
    clipboard: Option<Clipboard>,
    /// The last text we put on the clipboard ourselves (or sent), so a
    /// change that is just our own write isn't echoed back to the phone.
    pub last_synced: Option<String>,
}

#[cfg(windows)]
fn sequence() -> u32 {
    unsafe { windows::Win32::System::DataExchange::GetClipboardSequenceNumber() }
}

#[cfg(windows)]
impl Watcher {
    pub fn new() -> Self {
        Watcher { last_seq: sequence(), last_synced: None }
    }

    /// Returns new text if the clipboard changed to something we didn't write.
    pub fn poll(&mut self) -> Option<String> {
        let seq = sequence();
        if seq == self.last_seq {
            return None;
        }
        self.last_seq = seq;
        let text = Clipboard::new().ok()?.get_text().ok()?;
        if text.is_empty() || self.last_synced.as_deref() == Some(text.as_str()) {
            return None;
        }
        self.last_synced = Some(text.clone());
        Some(text)
    }

    /// Text from the phone: put it on the clipboard without echoing it back.
    pub fn set(&mut self, text: &str) {
        self.last_synced = Some(text.to_string());
        if let Ok(mut c) = Clipboard::new() {
            let _ = c.set_text(text);
        }
        self.last_seq = sequence();
    }
}

#[cfg(target_os = "linux")]
impl Watcher {
    pub fn new() -> Self {
        let mut clipboard = Clipboard::new().map_err(|e| crate::log!("clipboard", "no clipboard: {e}")).ok();
        // What's there already was copied before Bridge started: not news.
        let last_synced = clipboard.as_mut().and_then(|c| c.get_text().ok());
        Watcher { clipboard, last_synced }
    }

    /// Returns new text if the clipboard changed to something we didn't write.
    pub fn poll(&mut self) -> Option<String> {
        let text = self.clipboard.as_mut()?.get_text().ok()?;
        if text.is_empty() || self.last_synced.as_deref() == Some(text.as_str()) {
            return None;
        }
        self.last_synced = Some(text.clone());
        Some(text)
    }

    /// Text from the phone: put it on the clipboard without echoing it back.
    pub fn set(&mut self, text: &str) {
        self.last_synced = Some(text.to_string());
        if let Some(c) = self.clipboard.as_mut() {
            let _ = c.set_text(text);
        }
    }
}
