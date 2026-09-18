//! One line per event, timestamped, to `%APPDATA%\Bridge\Bridge.log` and to
//! the console when there is one. This file is what you bring back from a
//! test run: everything the app decided is in it.

use std::fs::OpenOptions;
use std::io::Write;
use std::sync::Mutex;

static FILE: Mutex<Option<std::fs::File>> = Mutex::new(None);

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
}

#[macro_export]
macro_rules! log {
    ($src:expr, $($arg:tt)*) => { $crate::log::line($src, &format!($($arg)*)) };
}
