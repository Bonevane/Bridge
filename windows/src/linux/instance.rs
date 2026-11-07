//! One Bridge at a time, as on Windows, with an abstract Unix socket playing
//! the part of the named mutex: only one process can bind the name, the kernel
//! frees it when that process ends however it ends, and a second launch can
//! connect to it to ask the first to show its window.

use std::io::Write;
use std::os::linux::net::SocketAddrExt;
use std::os::unix::net::{SocketAddr, UnixListener, UnixStream};
use std::sync::Mutex;

static LISTENER: Mutex<Option<UnixListener>> = Mutex::new(None);

/// Abstract names are per machine, not per user: the uid keeps two people
/// logged in at once from sharing one.
pub fn address(what: &str) -> std::io::Result<SocketAddr> {
    SocketAddr::from_abstract_name(format!("Bridge-Bonevane-{what}-{}", unsafe { libc::getuid() }))
}

fn bind() -> Option<UnixListener> {
    let listener = UnixListener::bind_addr(&address("instance").ok()?).ok()?;
    listener.set_nonblocking(true).ok()?;
    Some(listener)
}

pub type ShowEvent = ();

/// Exits if Bridge is already running, after asking it to show itself.
pub fn single_instance() -> Option<ShowEvent> {
    match bind() {
        Some(listener) => *LISTENER.lock().unwrap() = Some(listener),
        None => {
            if !std::env::args().any(|a| a == "--tray") {
                if let Ok(mut running) = address("instance").and_then(|a| UnixStream::connect_addr(&a)) {
                    let _ = running.write_all(b"show");
                }
            }
            std::process::exit(0);
        }
    }
    Some(())
}

/// Did someone launch Bridge again since the last look?
pub fn show_requested(_event: &ShowEvent) -> bool {
    LISTENER.lock().unwrap().as_ref().is_some_and(|l| l.accept().is_ok())
}

/// Lets go of "the one running Bridge", so the new version can start.
pub fn release_instance() {
    *LISTENER.lock().unwrap() = None;
}

/// Takes it back after an update that didn't go ahead.
pub fn reacquire_instance() {
    let mut listener = LISTENER.lock().unwrap();
    if listener.is_none() {
        *listener = bind();
    }
}
