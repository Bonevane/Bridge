//! Where the ticket and pairing secret live: `%APPDATA%\Bridge\credentials`,
//! encrypted with DPAPI (the Windows equivalent of the Mac's keychain: only
//! this user account on this machine can decrypt it, no prompt needed).
//! On Linux: the desktop's keyring when there is one, otherwise a file in
//! `~/.config/Bridge` that only this user can read.

use anyhow::{Context, Result};
#[cfg(windows)]
use anyhow::anyhow;
use std::path::PathBuf;
#[cfg(windows)]
use windows::Win32::Foundation::{HLOCAL, LocalFree};
#[cfg(windows)]
use windows::Win32::Security::Cryptography::{CryptProtectData, CryptUnprotectData, CRYPT_INTEGER_BLOB};

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Credentials {
    pub ticket: String,
    pub secret: String,
}

impl Credentials {
    pub fn is_paired(&self) -> bool {
        self.ticket.starts_with("endpoint") && self.secret.len() == 64
    }

    /// The phone's Copy button puts "endpoint… <secret>" on the clipboard.
    pub fn parse(text: &str) -> Option<Credentials> {
        let words: Vec<&str> = text.split_whitespace().collect();
        match words.as_slice() {
            [ticket, secret] if ticket.starts_with("endpoint") && secret.len() == 64 => Some(Credentials {
                ticket: ticket.to_string(),
                secret: secret.to_string(),
            }),
            _ => None,
        }
    }
}

pub fn data_dir() -> PathBuf {
    #[cfg(windows)]
    let base = std::env::var_os("APPDATA");
    #[cfg(target_os = "linux")]
    let base = std::env::var_os("XDG_CONFIG_HOME")
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".config").into()));
    base.map(PathBuf::from).unwrap_or_else(|| PathBuf::from(".")).join("Bridge")
}

fn path() -> PathBuf {
    data_dir().join("credentials")
}

fn decode(text: &str) -> Credentials {
    let (ticket, secret) = text.split_once('\n').unwrap_or((text, ""));
    Credentials { ticket: ticket.to_string(), secret: secret.to_string() }
}

#[cfg(windows)]
pub fn load() -> Result<Credentials> {
    let Ok(blob) = std::fs::read(path()) else { return Ok(Credentials::default()) };
    let plain = unprotect(&blob)?;
    Ok(decode(&String::from_utf8(plain)?))
}

#[cfg(windows)]
pub fn save(c: &Credentials) -> Result<()> {
    std::fs::create_dir_all(data_dir())?;
    let blob = protect(format!("{}\n{}", c.ticket, c.secret).as_bytes())?;
    std::fs::write(path(), blob).context("writing credentials")
}

#[cfg(windows)]
pub fn clear() -> Result<()> {
    match std::fs::remove_file(path()) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.into()),
    }
}

#[cfg(windows)]
fn protect(plain: &[u8]) -> Result<Vec<u8>> {
    dpapi(plain, true)
}

#[cfg(windows)]
fn unprotect(blob: &[u8]) -> Result<Vec<u8>> {
    dpapi(blob, false)
}

#[cfg(windows)]
fn dpapi(input: &[u8], encrypt: bool) -> Result<Vec<u8>> {
    let mut data = input.to_vec();
    let in_blob = CRYPT_INTEGER_BLOB { cbData: data.len() as u32, pbData: data.as_mut_ptr() };
    let mut out_blob = CRYPT_INTEGER_BLOB::default();
    let ok = unsafe {
        if encrypt {
            CryptProtectData(&in_blob, None, None, None, None, 0, &mut out_blob)
        } else {
            CryptUnprotectData(&in_blob, None, None, None, None, 0, &mut out_blob)
        }
    };
    ok.map_err(|e| anyhow!("DPAPI: {e}"))?;
    let out = unsafe { std::slice::from_raw_parts(out_blob.pbData, out_blob.cbData as usize).to_vec() };
    unsafe { let _ = LocalFree(HLOCAL(out_blob.pbData as _)); }
    Ok(out)
}

#[cfg(target_os = "linux")]
fn keyring() -> Option<keyring::Entry> {
    keyring::Entry::new("Bridge", "credentials").ok()
}

#[cfg(target_os = "linux")]
pub fn load() -> Result<Credentials> {
    if let Some(text) = keyring().and_then(|k| k.get_password().ok()) {
        return Ok(decode(&text));
    }
    let Ok(text) = std::fs::read_to_string(path()) else { return Ok(Credentials::default()) };
    Ok(decode(&text))
}

#[cfg(target_os = "linux")]
pub fn save(c: &Credentials) -> Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let text = format!("{}\n{}", c.ticket, c.secret);
    if keyring().is_some_and(|k| k.set_password(&text).is_ok()) {
        let _ = std::fs::remove_file(path()); // from before there was a keyring
        return Ok(());
    }
    crate::log!("", "no desktop keyring; keeping the pairing in a private file");
    std::fs::create_dir_all(data_dir())?;
    // Created private, not tightened afterwards, so it's never readable by others.
    let _ = std::fs::remove_file(path());
    let mut file = std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(path()).context("writing credentials")?;
    file.write_all(text.as_bytes()).context("writing credentials")
}

#[cfg(target_os = "linux")]
pub fn clear() -> Result<()> {
    if let Some(k) = keyring() {
        let _ = k.delete_credential();
    }
    match std::fs::remove_file(path()) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.into()),
    }
}
