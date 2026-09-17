//! Where the ticket and pairing secret live: `%APPDATA%\Bridge\credentials`,
//! encrypted with DPAPI (the Windows equivalent of the Mac's keychain: only
//! this user account on this machine can decrypt it, no prompt needed).

use anyhow::{anyhow, Context, Result};
use std::path::PathBuf;
use windows::Win32::Foundation::{HLOCAL, LocalFree};
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
    let base = std::env::var_os("APPDATA")
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("."));
    base.join("Bridge")
}

fn path() -> PathBuf {
    data_dir().join("credentials")
}

pub fn load() -> Result<Credentials> {
    let Ok(blob) = std::fs::read(path()) else { return Ok(Credentials::default()) };
    let plain = unprotect(&blob)?;
    let text = String::from_utf8(plain)?;
    let (ticket, secret) = text.split_once('\n').unwrap_or((&text, ""));
    Ok(Credentials { ticket: ticket.to_string(), secret: secret.to_string() })
}

pub fn save(c: &Credentials) -> Result<()> {
    std::fs::create_dir_all(data_dir())?;
    let blob = protect(format!("{}\n{}", c.ticket, c.secret).as_bytes())?;
    std::fs::write(path(), blob).context("writing credentials")
}

pub fn clear() -> Result<()> {
    match std::fs::remove_file(path()) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.into()),
    }
}

fn protect(plain: &[u8]) -> Result<Vec<u8>> {
    dpapi(plain, true)
}

fn unprotect(blob: &[u8]) -> Result<Vec<u8>> {
    dpapi(blob, false)
}

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
