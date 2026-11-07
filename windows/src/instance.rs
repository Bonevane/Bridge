//! One Bridge at a time, with a named mutex and a "show yourself" event.

pub type ShowEvent = windows::Win32::Foundation::HANDLE;

/// One Bridge at a time. A second copy would link to the phone over
/// Bluetooth from the same PC (the phone saw two subscriptions from one
/// device and one failed its handshake) and fight the first over the tunnel
/// port. A second launch asks the running one to show its window, then quits.
/// Returns the "show yourself" event the running instance waits on.
pub fn single_instance() -> Option<ShowEvent> {
    use windows::core::w;
    use windows::Win32::Foundation::{GetLastError, ERROR_ALREADY_EXISTS};
    use windows::Win32::System::Threading::{CreateEventW, CreateMutexW, OpenEventW, SetEvent, EVENT_MODIFY_STATE};
    unsafe {
        // Held as long as this process, which is the point; released only to
        // hand over to a new version during an update (see release_instance).
        let mutex = CreateMutexW(None, true, w!("Local\\Bridge-Bonevane-instance"));
        if GetLastError() == ERROR_ALREADY_EXISTS {
            if !std::env::args().any(|a| a == "--tray") {
                if let Ok(ev) = OpenEventW(EVENT_MODIFY_STATE, false, w!("Local\\Bridge-Bonevane-show")) {
                    let _ = SetEvent(ev);
                }
            }
            std::process::exit(0);
        }
        if let Ok(m) = mutex { *INSTANCE.lock().unwrap() = m.0 as isize; }
        CreateEventW(None, false, false, w!("Local\\Bridge-Bonevane-show")).ok()
    }
}

/// The single-instance mutex's handle, kept so an update can hand it over.
static INSTANCE: std::sync::Mutex<isize> = std::sync::Mutex::new(0);

/// Lets go of "the one running Bridge", so the new version can start.
pub fn release_instance() {
    use windows::Win32::Foundation::{CloseHandle, HANDLE};
    use windows::Win32::System::Threading::ReleaseMutex;
    let mut h = INSTANCE.lock().unwrap();
    if *h != 0 {
        unsafe {
            let handle = HANDLE(*h as *mut _);
            let _ = ReleaseMutex(handle);
            let _ = CloseHandle(handle);
        }
        *h = 0;
    }
}

/// Takes it back after an update that didn't go ahead.
pub fn reacquire_instance() {
    use windows::core::w;
    use windows::Win32::System::Threading::CreateMutexW;
    let mut h = INSTANCE.lock().unwrap();
    if *h == 0 {
        if let Ok(m) = unsafe { CreateMutexW(None, true, w!("Local\\Bridge-Bonevane-instance")) } {
            *h = m.0 as isize;
        }
    }
}

/// Did someone launch Bridge again since the last look?
pub fn show_requested(event: &ShowEvent) -> bool {
    use windows::Win32::Foundation::WAIT_OBJECT_0;
    use windows::Win32::System::Threading::WaitForSingleObject;
    unsafe { WaitForSingleObject(*event, 0) == WAIT_OBJECT_0 }
}
