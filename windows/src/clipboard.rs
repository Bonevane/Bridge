//! Clipboard both ways. Windows has a proper change listener, but polling the
//! clipboard *sequence number* every half second is simpler, costs nothing,
//! and needs no window to receive messages on.

use arboard::Clipboard;
use windows::Win32::System::DataExchange::GetClipboardSequenceNumber;

pub struct Watcher {
    last_seq: u32,
    /// The last text we put on the clipboard ourselves (or sent), so a
    /// change that is just our own write isn't echoed back to the phone.
    pub last_synced: Option<String>,
}

impl Watcher {
    pub fn new() -> Self {
        Watcher { last_seq: unsafe { GetClipboardSequenceNumber() }, last_synced: None }
    }

    /// Returns new text if the clipboard changed to something we didn't write.
    pub fn poll(&mut self) -> Option<String> {
        let seq = unsafe { GetClipboardSequenceNumber() };
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
        self.last_seq = unsafe { GetClipboardSequenceNumber() };
    }
}
