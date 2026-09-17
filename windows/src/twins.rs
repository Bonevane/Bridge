//! Which apps with an Android twin are running on this PC, so the phone can
//! hold back their notifications (WhatsApp on the PC shows its own).
//! Executable name → Android package. Same idea as MacTwins.swift.

use std::collections::BTreeSet;
use windows::Win32::Foundation::CloseHandle;
use windows::Win32::System::ProcessStatus::{EnumProcesses, GetModuleBaseNameW};
use windows::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_INFORMATION, PROCESS_VM_READ};

const TABLE: &[(&str, &str)] = &[
    ("whatsapp.exe", "com.whatsapp"),
    ("telegram.exe", "org.telegram.messenger"),
    ("signal.exe", "org.thoughtcrime.securesms"),
    ("discord.exe", "com.discord"),
    ("slack.exe", "com.Slack"),
    ("messenger.exe", "com.facebook.orca"),
    ("ms-teams.exe", "com.microsoft.teams"),
    ("teams.exe", "com.microsoft.teams"),
    ("zoom.exe", "us.zoom.videomeetings"),
    ("chrome.exe", "com.android.chrome"),
    ("spotify.exe", "com.spotify.music"),
    ("viber.exe", "com.viber.voip"),
    ("skype.exe", "com.skype.raider"),
    ("wechat.exe", "com.tencent.mm"),
];

/// Android packages whose Windows twin is running right now.
pub fn open_packages() -> BTreeSet<String> {
    let mut pids = vec![0u32; 4096];
    let mut needed = 0u32;
    let ok = unsafe { EnumProcesses(pids.as_mut_ptr(), (pids.len() * 4) as u32, &mut needed) };
    if ok.is_err() {
        return BTreeSet::new();
    }
    let count = needed as usize / 4;
    let mut found = BTreeSet::new();
    for &pid in &pids[..count] {
        if pid == 0 {
            continue;
        }
        let Ok(handle) = (unsafe { OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, pid) }) else { continue };
        let mut name = [0u16; 260];
        let len = unsafe { GetModuleBaseNameW(handle, None, &mut name) } as usize;
        unsafe { let _ = CloseHandle(handle); }
        if len == 0 {
            continue;
        }
        let exe = String::from_utf16_lossy(&name[..len]).to_lowercase();
        if let Some((_, pkg)) = TABLE.iter().find(|(e, _)| *e == exe) {
            found.insert(pkg.to_string());
        }
    }
    found
}
