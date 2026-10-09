//! One line per event, timestamped, to `%APPDATA%\Bridge\Bridge.log` and to
//! the console when there is one. This file is what you bring back from a
//! test run: everything the app decided is in it.

use std::fs::OpenOptions;
use std::io::Write;
use std::sync::Mutex;

static FILE: Mutex<Option<std::fs::File>> = Mutex::new(None);

/// The last few hundred lines from every part of the app (Bluetooth, tunnel,
/// USB, input…), for the window's log panel. It used to show only the lines
/// the window itself wrote, so a link problem was visible only in the file.
static RECENT: Mutex<std::collections::VecDeque<String>> = Mutex::new(std::collections::VecDeque::new());
static CHANGED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

/// Newest first, if anything was logged since the last call.
pub fn take_recent() -> Option<String> {
    if !CHANGED.swap(false, std::sync::atomic::Ordering::SeqCst) {
        return None;
    }
    let r = RECENT.lock().unwrap();
    Some(r.iter().rev().map(String::as_str).collect::<Vec<_>>().join("\n"))
}

pub fn init() {
    let _ = std::fs::create_dir_all(crate::store::data_dir());
    let path = crate::store::data_dir().join("Bridge.log");
    if let Ok(f) = OpenOptions::new().create(true).append(true).open(&path) {
        *FILE.lock().unwrap() = Some(f);
    }
}

pub fn line(source: &str, text: &str) {
    let stamp = chrono::Local::now().format("%H:%M:%S");
    let entry = if source.is_empty() { format!("{stamp} {text}") } else { format!("{stamp} [{source}] {text}") };
    println!("{entry}");
    if let Some(f) = FILE.lock().unwrap().as_mut() {
        let _ = writeln!(f, "{entry}");
    }
    let mut r = RECENT.lock().unwrap();
    r.push_back(entry);
    while r.len() > 400 {
        r.pop_front();
    }
    CHANGED.store(true, std::sync::atomic::Ordering::SeqCst);
}

#[macro_export]
macro_rules! log {
    ($src:expr, $($arg:tt)*) => { $crate::log::line($src, &format!($($arg)*)) };
}
