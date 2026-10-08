//! Files between the PC and the phone, through the tunnel to the phone's
//! helper (Daemon.kt): PUSH drops a file into the phone's Download folder,
//! LIST and PULL browse the phone's shared storage and fetch from it.
//! All three need a session's tunnel and helper, so they're offered while
//! mirroring.

use crate::tunnel::Stream;
use anyhow::{anyhow, bail, Context, Result};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

#[derive(Clone, Debug)]
pub struct Entry {
    pub dir: bool,
    pub size: u64,
    pub modified_ms: i64,
    pub name: String,
}

/// One folder of the phone's shared storage. Empty path = the top.
/// Returns the folder's real path (as the phone resolved it) and its entries.
pub fn list(secret: &str, path: &str) -> Result<(String, Vec<Entry>)> {
    let mut s = Stream::open(secret, Duration::from_secs(20))?;
    s.write_all(format!("LIST {path}\n").as_bytes())?;
    let head = s.read_line()?;
    let Some(resolved) = head.strip_prefix("OK ") else { bail!("phone: {head}") };
    let resolved = resolved.to_string();
    let mut entries = Vec::new();
    loop {
        let line = s.read_line()?;
        if line.is_empty() {
            break;
        }
        let mut f = line.splitn(4, '\t');
        let (Some(kind), Some(size), Some(modified), Some(name)) = (f.next(), f.next(), f.next(), f.next()) else { continue };
        entries.push(Entry {
            dir: kind == "d",
            size: size.parse().unwrap_or(0),
            modified_ms: modified.parse().unwrap_or(0),
            name: name.to_string(),
        });
    }
    s.close();
    Ok((resolved, entries))
}

/// Where downloads land: the user's Downloads folder.
pub fn downloads_dir() -> PathBuf {
    std::env::var("USERPROFILE").map(|h| PathBuf::from(h).join("Downloads")).unwrap_or_else(|_| PathBuf::from("."))
}

/// "photo.jpg" → "photo (1).jpg" …, so a download never overwrites a file.
fn free_name(dir: &Path, name: &str) -> PathBuf {
    let candidate = dir.join(name);
    if !candidate.exists() {
        return candidate;
    }
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => (&name[..i], &name[i..]),
        _ => (name, ""),
    };
    (1..).map(|i| dir.join(format!("{stem} ({i}){ext}"))).find(|p| !p.exists()).unwrap()
}

/// Copies one file off the phone into Downloads. `progress(done, total)` is
/// called a few times a second at most.
pub fn pull(secret: &str, remote: &str, mut progress: impl FnMut(u64, u64)) -> Result<PathBuf> {
    let mut s = Stream::open(secret, Duration::from_secs(60))?;
    s.write_all(format!("PULL {remote}\n").as_bytes())?;
    let head = s.read_line()?;
    let total: u64 = head.strip_prefix("OK ").and_then(|n| n.trim().parse().ok()).ok_or_else(|| anyhow!("phone: {head}"))?;
    let name = remote.rsplit('/').next().filter(|n| !n.is_empty()).unwrap_or("phone-file");
    let dir = downloads_dir();
    let _ = std::fs::create_dir_all(&dir);
    let target = free_name(&dir, name);
    // Write to a .part file, renamed at the end: a cancelled or dropped
    // transfer never leaves something that looks complete.
    let part = target.with_extension(format!("{}part", target.extension().map(|e| format!("{}.", e.to_string_lossy())).unwrap_or_default()));
    let mut out = std::fs::File::create(&part).with_context(|| format!("creating {}", part.display()))?;
    let mut buf = vec![0u8; 128 * 1024];
    let mut done = 0u64;
    let mut last = Instant::now();
    while done < total {
        let want = buf.len().min((total - done) as usize);
        let n = s.read(&mut buf[..want])?;
        if n == 0 {
            drop(out);
            let _ = std::fs::remove_file(&part);
            bail!("the phone stopped sending at {done} of {total} bytes");
        }
        out.write_all(&buf[..n])?;
        done += n as u64;
        if last.elapsed() > Duration::from_millis(250) {
            progress(done, total);
            last = Instant::now();
        }
    }
    drop(out);
    std::fs::rename(&part, &target)?;
    progress(total, total);
    Ok(target)
}

/// Sends one file to the phone's Download folder. Returns the phone's reply
/// ("saved to Download/…").
pub fn push(secret: &str, local: &Path, mut progress: impl FnMut(u64, u64)) -> Result<String> {
    let mut file = std::fs::File::open(local).with_context(|| format!("opening {}", local.display()))?;
    let total = file.metadata()?.len();
    if total == 0 {
        bail!("{} is empty", local.display());
    }
    // The name rides on the command line: one plain line, no path.
    let name: String = local
        .file_name()
        .map(|n| n.to_string_lossy().into_owned())
        .unwrap_or_else(|| "pc-file".into())
        .chars()
        .filter(|c| !c.is_control())
        .collect();
    let mut s = Stream::open(secret, Duration::from_secs(60))?;
    s.write_all(format!("PUSH {total} {name}\n").as_bytes())?;
    let mut buf = vec![0u8; 128 * 1024];
    let mut done = 0u64;
    let mut last = Instant::now();
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        s.write_all(&buf[..n])?;
        done += n as u64;
        if last.elapsed() > Duration::from_millis(250) {
            progress(done, total);
            last = Instant::now();
        }
    }
    // The phone answers once the file is on disk and scanned.
    s.set_timeout(Duration::from_secs(120));
    let reply = s.read_line()?;
    s.close();
    match reply.strip_prefix("OK ") {
        Some(r) => Ok(r.to_string()),
        None => bail!("phone: {reply}"),
    }
}

/// "3.2 MB", "870 KB" …
pub fn human_size(bytes: u64) -> String {
    let b = bytes as f64;
    if b >= 1e9 {
        format!("{:.1} GB", b / 1e9)
    } else if b >= 1e6 {
        format!("{:.1} MB", b / 1e6)
    } else if b >= 1e3 {
        format!("{:.0} KB", b / 1e3)
    } else {
        format!("{bytes} B")
    }
}
