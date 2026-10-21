//! Keeps Bridge for Windows up to date from the project's GitHub releases.
//!
//! - Checks once a day (unless switched off) and whenever asked. Nothing is
//!   downloaded until someone clicks Update.
//! - Downloads resume: the bytes already on disk are kept (`.part`), a dropped
//!   connection retries by itself, a pause or a restart carries on.
//! - The zip must match the release's SHA256SUMS and contain Bridge.exe.
//! - Installing stops everything cleanly first (main.rs), then swaps the
//!   files: Windows lets a running .exe or .dll be *renamed*, so each file is
//!   moved aside to `.old` and the new one put in its place. The new Bridge is
//!   started with `--after-update`, and this one waits for it to say it's up.
//!   If it doesn't, the old files go back and this copy carries on.

use std::fs::{self, File};
use std::io::{Read, Seek, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::mpsc::Sender;
use std::sync::Arc;
use std::time::{Duration, Instant};

use anyhow::{anyhow, Context, Result};
use sha2::{Digest, Sha256};

use crate::ble::Event;

const API: &str = "https://api.github.com/repos/Bonevane/Bridge/releases/latest";
/// The event the new copy sets once it's running (see `signal_updated`).
const UPDATED_EVENT: &str = "Local\\Bridge-Bonevane-updated";

#[derive(Clone, Debug, PartialEq)]
pub struct Release {
    pub version: String,
    pub notes: String,
    pub zip_url: String,
    pub zip_name: String,
    pub size: u64,
    pub sums_url: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub enum State {
    Idle,
    Checking,
    UpToDate,
    Available(Release),
    Downloading { release: Release, done: u64, total: u64, per_sec: u64 },
    Paused { release: Release, done: u64, total: u64, reason: Option<String> },
    Verifying(Release),
    Ready(Release),
    Installing(Release),
    Failed(String, Option<Release>),
}

impl State {
    pub fn release(&self) -> Option<&Release> {
        match self {
            State::Available(r) | State::Verifying(r) | State::Ready(r) | State::Installing(r) => Some(r),
            State::Downloading { release, .. } | State::Paused { release, .. } => Some(release),
            State::Failed(_, r) => r.as_ref(),
            _ => None,
        }
    }
}

/// What a check found.
#[derive(Debug)]
pub enum Checked {
    Newer(Release),
    Current,
    /// GitHub asked us to wait until this time (unix millis).
    RateLimited(i64),
    Failed(String),
}

pub fn current_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

/// "0.3.10" is newer than "0.3.9"; unparsable parts count as 0.
pub fn is_newer(a: &str, b: &str) -> bool {
    let parse = |v: &str| v.split(['.', '-']).map(|p| p.parse::<i64>().unwrap_or(0)).collect::<Vec<_>>();
    let (x, y) = (parse(a), parse(b));
    for i in 0..x.len().max(y.len()) {
        let (p, q) = (x.get(i).copied().unwrap_or(0), y.get(i).copied().unwrap_or(0));
        if p != q {
            return p > q;
        }
    }
    false
}

fn agent(redirects: u32) -> ureq::Agent {
    let mut builder = ureq::AgentBuilder::new();
    if let Ok(tls) = native_tls::TlsConnector::new() {
        builder = builder.tls_connector(Arc::new(tls));
    }
    builder
        .redirects(redirects)
        .timeout_connect(Duration::from_secs(15))
        .timeout_read(Duration::from_secs(30))
        .user_agent(&format!("Bridge-Windows/{}", current_version()))
        .build()
}

pub fn dir() -> PathBuf {
    let d = crate::store::data_dir().join("updates");
    let _ = fs::create_dir_all(&d);
    d
}
fn part_file(r: &Release) -> PathBuf { dir().join(format!("Bridge-{}-windows.zip.part", r.version)) }
pub fn zip_file(r: &Release) -> PathBuf { dir().join(format!("Bridge-{}-windows.zip", r.version)) }

// MARK: - Checking

/// Runs on its own thread; the answer arrives as `Event::UpdateChecked`.
pub fn check(tx: Sender<Event>) {
    std::thread::spawn(move || {
        let _ = tx.send(Event::UpdateChecked(check_now()));
    });
}

fn check_now() -> Checked {
    let resp = agent(5).get(API).set("Accept", "application/vnd.github+json").call();
    let resp = match resp {
        Ok(r) => r,
        Err(ureq::Error::Status(403, r)) | Err(ureq::Error::Status(429, r)) => {
            let reset = r.header("X-RateLimit-Reset").and_then(|v| v.parse::<i64>().ok()).map(|s| s * 1000)
                .unwrap_or_else(|| chrono::Utc::now().timestamp_millis() + 3_600_000);
            return Checked::RateLimited(reset);
        }
        Err(ureq::Error::Status(404, _)) => return Checked::Current,
        Err(e) => return Checked::Failed(format!("{e}")),
    };
    let json: serde_json::Value = match resp.into_json() {
        Ok(j) => j,
        Err(e) => return Checked::Failed(format!("unreadable answer: {e}")),
    };
    let version = json["tag_name"].as_str().unwrap_or("").trim_start_matches('v').to_string();
    let assets = json["assets"].as_array().cloned().unwrap_or_default();
    let zip = assets.iter().find(|a| a["name"].as_str().unwrap_or("").ends_with("-windows.zip"));
    let sums = assets.iter().find(|a| a["name"].as_str().unwrap_or("").starts_with("SHA256SUMS"));
    let Some(zip) = zip else { return Checked::Current };
    if !is_newer(&version, current_version()) {
        return Checked::Current;
    }
    Checked::Newer(Release {
        version,
        notes: json["body"].as_str().unwrap_or("").trim().to_string(),
        zip_url: zip["browser_download_url"].as_str().unwrap_or("").to_string(),
        zip_name: zip["name"].as_str().unwrap_or("").to_string(),
        size: zip["size"].as_u64().unwrap_or(0),
        sums_url: sums.and_then(|s| s["browser_download_url"].as_str()).map(String::from),
    })
}

/// A release found earlier: what to show for it, given what's already on disk.
pub fn state_for(r: Release) -> State {
    if zip_file(&r).exists() {
        return State::Paused { done: r.size, total: r.size, reason: None, release: r };
    }
    let have = fs::metadata(part_file(&r)).map(|m| m.len()).unwrap_or(0);
    if have > 0 && have < r.size {
        State::Paused { done: have, total: r.size, reason: None, release: r }
    } else {
        State::Available(r)
    }
}

// MARK: - Downloading and verifying

/// 0 = run, 1 = pause, 2 = cancel; read between reads by the download loop.
pub type Control = Arc<AtomicU8>;

/// Download (resuming), verify, and report `Ready`, all on its own thread.
/// Every step reports through `Event::UpdateState`.
pub fn download(r: Release, control: Control, tx: Sender<Event>) {
    control.store(0, Ordering::SeqCst);
    std::thread::spawn(move || {
        let send = |s: State| { let _ = tx.send(Event::UpdateState(s)); };
        match fetch(&r, &control, &tx) {
            Ok(true) => {}
            Ok(false) => return, // paused or cancelled; already reported
            Err(e) => {
                let have = fs::metadata(part_file(&r)).map(|m| m.len()).unwrap_or(0);
                send(State::Paused {
                    done: have, total: r.size, release: r.clone(),
                    reason: Some(format!("{e}. Click Resume to carry on from where it stopped.")),
                });
                return;
            }
        }
        send(State::Verifying(r.clone()));
        match verify(&r) {
            Ok(()) => send(State::Ready(r)),
            Err((msg, delete)) => {
                if delete { let _ = fs::remove_file(zip_file(&r)); }
                send(State::Failed(msg, Some(r)));
            }
        }
    });
}

/// Ok(true) finished, Ok(false) paused/cancelled, Err after the retries ran out.
fn fetch(r: &Release, control: &Control, tx: &Sender<Event>) -> Result<bool> {
    let done = zip_file(r);
    if done.exists() && fs::metadata(&done).map(|m| m.len()).unwrap_or(0) == r.size {
        return Ok(true);
    }
    let part = part_file(r);
    let have = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
    if let Some(free) = free_space(&dir()) {
        let need = r.size.saturating_sub(have) * 2 + 50 * 1024 * 1024;
        if free < need {
            return Err(anyhow!("Not enough free space: the update needs about {} MB", need >> 20));
        }
    }
    let waits = [2, 5, 10, 20, 30];
    let mut attempt = 0;
    loop {
        match fetch_once(r, &part, control, tx) {
            Ok(()) => {}
            Err(e) => {
                if control.load(Ordering::SeqCst) == 0 {
                    crate::log!("update", "download interrupted: {e:#}");
                    if attempt >= waits.len() {
                        return Err(anyhow!("The connection kept dropping"));
                    }
                    let have = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
                    let _ = tx.send(Event::UpdateState(State::Paused {
                        release: r.clone(), done: have, total: r.size, reason: Some("Connection lost. Retrying…".into()),
                    }));
                    std::thread::sleep(Duration::from_secs(waits[attempt]));
                    attempt += 1;
                    continue;
                }
            }
        }
        match control.load(Ordering::SeqCst) {
            1 => {
                let have = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
                let _ = tx.send(Event::UpdateState(State::Paused { release: r.clone(), done: have, total: r.size, reason: None }));
                return Ok(false);
            }
            2 => {
                let _ = fs::remove_file(&part);
                let _ = tx.send(Event::UpdateState(State::Available(r.clone())));
                return Ok(false);
            }
            _ => {}
        }
        let len = fs::metadata(&part).map(|m| m.len()).unwrap_or(0);
        if len != r.size {
            attempt += 1;
            if attempt > waits.len() { return Err(anyhow!("Got {len} of {} bytes", r.size)); }
            continue;
        }
        let _ = fs::remove_file(&done);
        fs::rename(&part, &done).context("couldn't finish writing the file")?;
        return Ok(true);
    }
}

/// One pass: ask for the missing bytes (Range), follow GitHub's redirect by
/// hand so the Range header survives, append. A 200 instead of a 206 means
/// the server ignored Range, so the file starts over rather than corrupting.
fn fetch_once(r: &Release, part: &Path, control: &Control, tx: &Sender<Event>) -> Result<()> {
    let agent = agent(0);
    let mut url = r.zip_url.clone();
    let mut hops = 0;
    let resp = loop {
        let have = fs::metadata(part).map(|m| m.len()).unwrap_or(0);
        let mut req = agent.get(&url);
        if have > 0 { req = req.set("Range", &format!("bytes={have}-")); }
        let resp = match req.call() {
            Ok(r) => r,
            Err(ureq::Error::Status(416, _)) => {
                if have == r.size { return Ok(()); }
                let _ = fs::remove_file(part);
                return Err(anyhow!("the file on the server changed; starting again"));
            }
            Err(e) => return Err(anyhow!("{e}")),
        };
        if (300..400).contains(&resp.status()) && hops < 6 {
            url = resp.header("Location").ok_or_else(|| anyhow!("redirect without a target"))?.to_string();
            hops += 1;
            continue;
        }
        break resp;
    };
    let append = resp.status() == 206;
    let mut out = fs::OpenOptions::new().create(true).write(true).open(part)?;
    if append { out.seek(std::io::SeekFrom::End(0))?; } else { out.set_len(0)?; }
    let mut done = out.stream_position()?;
    let mut reader = resp.into_reader();
    let mut buf = vec![0u8; 64 * 1024];
    let mut window = (Instant::now(), done);
    let mut rate = 0u64;
    loop {
        if control.load(Ordering::SeqCst) != 0 { return Ok(()); }
        let n = reader.read(&mut buf)?;
        if n == 0 { break; }
        out.write_all(&buf[..n])?;
        done += n as u64;
        let elapsed = window.0.elapsed();
        if elapsed >= Duration::from_millis(500) {
            rate = ((done - window.1) as f64 / elapsed.as_secs_f64()) as u64;
            window = (Instant::now(), done);
            let _ = tx.send(Event::UpdateState(State::Downloading { release: r.clone(), done, total: r.size, per_sec: rate }));
        }
    }
    let _ = rate;
    Ok(())
}

/// Err((message, delete the download)).
fn verify(r: &Release) -> std::result::Result<(), (String, bool)> {
    let path = zip_file(r);
    if let Some(url) = &r.sums_url {
        let text = agent(5).get(url).call().ok().and_then(|resp| resp.into_string().ok())
            .ok_or_else(|| ("Couldn't fetch the release's checksum to check the download. Try again.".to_string(), false))?;
        let expected = text.lines().find(|l| l.trim().ends_with(&r.zip_name))
            .and_then(|l| l.split_whitespace().next()).map(|h| h.to_lowercase())
            .ok_or_else(|| ("The release doesn't list a checksum for this download.".to_string(), false))?;
        let mut hasher = Sha256::new();
        let mut f = File::open(&path).map_err(|e| (format!("Couldn't read the download: {e}"), true))?;
        let mut buf = vec![0u8; 1 << 20];
        loop {
            let n = f.read(&mut buf).map_err(|e| (format!("Couldn't read the download: {e}"), true))?;
            if n == 0 { break; }
            hasher.update(&buf[..n]);
        }
        if hex::encode(hasher.finalize()) != expected {
            return Err(("The download was damaged on the way (its checksum doesn't match). Try again.".into(), true));
        }
    } else {
        crate::log!("update", "this release has no SHA256SUMS; checking the zip's contents only");
    }
    let file = File::open(&path).map_err(|e| (format!("Couldn't open the download: {e}"), true))?;
    let mut zip = zip::ZipArchive::new(file).map_err(|_| ("The download isn't a valid zip.".to_string(), true))?;
    let names: Vec<String> = (0..zip.len()).filter_map(|i| zip.by_index(i).ok().map(|f| f.name().to_string())).collect();
    if !names.iter().any(|n| n.eq_ignore_ascii_case("Bridge.exe")) {
        return Err(("The download doesn't contain Bridge.exe.".into(), true));
    }
    if names.iter().any(|n| n.contains(['/', '\\']) || n.contains("..")) {
        return Err(("The download has an unexpected layout, so it won't be installed.".into(), true));
    }
    Ok(())
}

fn free_space(path: &Path) -> Option<u64> {
    use windows::core::HSTRING;
    use windows::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
    let mut free = 0u64;
    unsafe { GetDiskFreeSpaceExW(&HSTRING::from(path.as_os_str()), Some(&mut free), None, None).ok()? };
    Some(free)
}

// MARK: - Installing

/// The files swapped so far, to put back if the new copy doesn't start.
pub struct Swap {
    dir: PathBuf,
    files: Vec<String>,
}

impl Swap {
    /// The old files back in place (the new ones deleted).
    pub fn undo(&self) {
        for name in &self.files {
            let target = self.dir.join(name);
            let old = self.dir.join(format!("{name}.old"));
            let _ = fs::remove_file(&target);
            if old.exists() { let _ = fs::rename(&old, &target); }
        }
    }
}

/// Moves each current file aside and writes the new one. Running programs
/// (this Bridge.exe, an adb server) can be renamed on Windows, just not
/// overwritten, which is what makes an in-place update possible.
pub fn swap_in(r: &Release) -> Result<Swap> {
    let exe = std::env::current_exe()?;
    let dir = exe.parent().ok_or_else(|| anyhow!("no folder for Bridge.exe"))?.to_path_buf();
    let mut zip = zip::ZipArchive::new(File::open(zip_file(r))?)?;
    let mut swap = Swap { dir: dir.clone(), files: vec![] };
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        if entry.is_dir() { continue; }
        let name = entry.name().to_string();
        let target = dir.join(&name);
        let staged = dir.join(format!("{name}.new"));
        let result = (|| -> Result<()> {
            let mut out = File::create(&staged).with_context(|| format!("writing {name}"))?;
            std::io::copy(&mut entry, &mut out)?;
            out.sync_all()?;
            drop(out);
            let old = dir.join(format!("{name}.old"));
            let _ = fs::remove_file(&old);
            if target.exists() { fs::rename(&target, &old).with_context(|| format!("moving the old {name} aside"))?; }
            fs::rename(&staged, &target).with_context(|| format!("putting the new {name} in place"))?;
            Ok(())
        })();
        if let Err(e) = result {
            let _ = fs::remove_file(&staged);
            swap.undo();
            return Err(e);
        }
        swap.files.push(name);
    }
    Ok(swap)
}

/// Starts the new Bridge.exe and waits (on this thread) up to 30 s for it to
/// say it's running. Err means it didn't, and the caller should undo.
pub fn launch_and_wait() -> Result<()> {
    use windows::core::HSTRING;
    use windows::Win32::Foundation::{CloseHandle, WAIT_OBJECT_0};
    use windows::Win32::System::Threading::{CreateEventW, WaitForSingleObject};
    let event = unsafe { CreateEventW(None, true, false, &HSTRING::from(UPDATED_EVENT))? };
    let exe = std::env::current_exe()?;
    let mut child = std::process::Command::new(&exe).arg("--after-update").spawn().context("starting the new version")?;
    let waited = unsafe { WaitForSingleObject(event, 30_000) };
    unsafe { let _ = CloseHandle(event); }
    if waited == WAIT_OBJECT_0 {
        return Ok(());
    }
    let _ = child.kill();
    Err(anyhow!("the new version didn't start"))
}

/// Called by the new copy once it's up (`--after-update`).
pub fn signal_updated() {
    use windows::core::HSTRING;
    use windows::Win32::System::Threading::{OpenEventW, SetEvent, EVENT_MODIFY_STATE};
    unsafe {
        if let Ok(ev) = OpenEventW(EVENT_MODIFY_STATE, false, &HSTRING::from(UPDATED_EVENT)) {
            let _ = SetEvent(ev);
        }
    }
}

/// The `.old` files from the previous version, once it has exited, and old downloads.
pub fn clean_up_later() {
    std::thread::spawn(|| {
        for wait in [3u64, 10, 30] {
            std::thread::sleep(Duration::from_secs(wait));
            let Ok(exe) = std::env::current_exe() else { return };
            let Some(dir) = exe.parent() else { return };
            let mut left = 0;
            for entry in fs::read_dir(dir).into_iter().flatten().flatten() {
                let p = entry.path();
                let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("");
                if name.ends_with(".old") || name.ends_with(".new") {
                    if fs::remove_file(&p).is_err() { left += 1; }
                }
            }
            if left == 0 { break; }
        }
        for entry in fs::read_dir(dir()).into_iter().flatten().flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            let v = name.trim_start_matches("Bridge-").split("-windows").next().unwrap_or("").to_string();
            if !is_newer(&v, current_version()) { let _ = fs::remove_file(entry.path()); }
        }
    });
}

/// Ends any program still running from Bridge's own folder, other than this
/// one: the adb server Bridge started (asked politely first), a stray
/// dumbpipe. Anything from elsewhere (Android Studio's adb) is left alone.
pub fn stop_own_helpers() {
    let Ok(exe) = std::env::current_exe() else { return };
    let Some(dir) = exe.parent() else { return };
    let adb = dir.join("adb.exe");
    let adb_name = adb.to_string_lossy().to_lowercase();
    if adb.exists() && own_processes(dir).iter().any(|(_, p)| p.to_string_lossy().to_lowercase() == adb_name) {
        let _ = std::process::Command::new(&adb).arg("kill-server").output();
        std::thread::sleep(Duration::from_millis(500));
    }
    for (pid, path) in own_processes(dir) {
        if path == exe { continue; }
        crate::log!("update", "stopping {}", path.display());
        terminate(pid);
    }
}

fn own_processes(dir: &Path) -> Vec<(u32, PathBuf)> {
    use windows::core::PWSTR;
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::ProcessStatus::EnumProcesses;
    use windows::Win32::System::Threading::{OpenProcess, QueryFullProcessImageNameW, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION};
    let mut pids = vec![0u32; 4096];
    let mut needed = 0u32;
    if unsafe { EnumProcesses(pids.as_mut_ptr(), (pids.len() * 4) as u32, &mut needed) }.is_err() { return vec![]; }
    pids.truncate(needed as usize / 4);
    let me = std::process::id();
    let mut out = vec![];
    for pid in pids {
        if pid == 0 || pid == me { continue; }
        unsafe {
            let Ok(h) = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid) else { continue };
            let mut buf = [0u16; 1024];
            let mut len = buf.len() as u32;
            if QueryFullProcessImageNameW(h, PROCESS_NAME_WIN32, PWSTR(buf.as_mut_ptr()), &mut len).is_ok() {
                let path = PathBuf::from(String::from_utf16_lossy(&buf[..len as usize]));
                if path.parent().map(|p| p.eq(dir)).unwrap_or(false) { out.push((pid, path)); }
            }
            let _ = CloseHandle(h);
        }
    }
    out
}

fn terminate(pid: u32) {
    use windows::Win32::Foundation::CloseHandle;
    use windows::Win32::System::Threading::{OpenProcess, TerminateProcess, PROCESS_TERMINATE};
    unsafe {
        if let Ok(h) = OpenProcess(PROCESS_TERMINATE, false, pid) {
            let _ = TerminateProcess(h, 0);
            let _ = CloseHandle(h);
        }
    }
}
