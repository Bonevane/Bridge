//! The internet tunnel to the phone and the one-line control protocol over
//! it. Same shape as the Mac: a bundled `dumbpipe.exe` listens on
//! 127.0.0.1:7555 and forwards every connection over iroh to the phone's
//! ControlProxy. Every stream opens with `AUTH <secret>\n` (see Pairing.kt).
//!
//! dumbpipe is a separate process rather than iroh-in-process for now: it is
//! proven against the phone, and this client can't be run on the machine it
//! is written on, so fewer moving parts wins. Swapping in the iroh crate
//! later changes only this file.

use anyhow::{anyhow, bail, Context, Result};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{Shutdown, TcpStream};
use std::process::{Child, Command, Stdio};
use std::time::Duration;

pub const LOCAL_PORT: u16 = 7555;

pub struct Tunnel {
    child: Child,
}

impl Tunnel {
    /// `dumbpipe.exe` next to Bridge.exe (the CI build puts it there), or on PATH.
    fn dumbpipe() -> Result<std::path::PathBuf> {
        let beside = std::env::current_exe()?.with_file_name("dumbpipe.exe");
        if beside.exists() {
            return Ok(beside);
        }
        Ok(std::path::PathBuf::from("dumbpipe.exe"))
    }

    pub fn start(ticket: &str) -> Result<Tunnel> {
        // A previous Bridge that was killed leaves its dumbpipe holding the
        // port; the new one would then fail with "address in use".
        Self::reap_stale();
        let exe = Self::dumbpipe()?;
        let mut child = Command::new(&exe)
            .args(["connect-tcp", "--addr", &format!("127.0.0.1:{LOCAL_PORT}"), ticket])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .creation_flags_no_window()
            .spawn()
            .with_context(|| format!("starting {}", exe.display()))?;
        crate::log!("tunnel", "dumbpipe started");
        if let Some(stderr) = child.stderr.take() {
            std::thread::spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    // dumbpipe prints this machine's own iroh key on start; keep it out of the log.
                    let line = if line.starts_with("using secret key") { "using secret key (redacted)".to_string() } else { line };
                    crate::log!("tunnel", "{line}");
                }
            });
        }
        Ok(Tunnel { child })
    }

    pub fn is_running(&mut self) -> bool {
        matches!(self.child.try_wait(), Ok(None))
    }

    pub fn stop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
        crate::log!("tunnel", "dumbpipe stopped");
    }

    fn reap_stale() {
        // Only dumbpipe processes; never someone else's server on the port.
        let _ = Command::new("taskkill").args(["/F", "/IM", "dumbpipe.exe"]).stdout(Stdio::null()).stderr(Stdio::null())
            .creation_flags_no_window().status();
    }
}

impl Drop for Tunnel {
    fn drop(&mut self) {
        let _ = self.child.kill();
    }
}

/// Hides the console window a child process would otherwise flash open.
pub(crate) trait NoWindow {
    fn creation_flags_no_window(&mut self) -> &mut Self;
}
impl NoWindow for Command {
    fn creation_flags_no_window(&mut self) -> &mut Self {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        self.creation_flags(CREATE_NO_WINDOW)
    }
}

/// One connection through the tunnel, already authenticated.
pub struct Stream {
    inner: TcpStream,
}

impl Stream {
    pub fn open(secret: &str, timeout: Duration) -> Result<Stream> {
        let addr = format!("127.0.0.1:{LOCAL_PORT}").parse().unwrap();
        let mut inner = TcpStream::connect_timeout(&addr, Duration::from_secs(5)).context("tunnel not listening")?;
        inner.set_read_timeout(Some(timeout))?;
        inner.set_nodelay(true)?;
        inner.write_all(format!("AUTH {secret}\n").as_bytes())?;
        Ok(Stream { inner })
    }

    pub fn write_all(&mut self, bytes: &[u8]) -> Result<()> {
        Ok(self.inner.write_all(bytes)?)
    }

    pub fn read_line(&mut self) -> Result<String> {
        let mut line = Vec::new();
        let mut byte = [0u8; 1];
        loop {
            let n = self.inner.read(&mut byte)?;
            if n == 0 {
                bail!("connection closed");
            }
            if byte[0] == b'\n' {
                break;
            }
            line.push(byte[0]);
            if line.len() > 4096 {
                bail!("line too long");
            }
        }
        Ok(String::from_utf8_lossy(&line).into_owned())
    }

    pub fn read_exact(&mut self, buf: &mut [u8]) -> Result<()> {
        Ok(self.inner.read_exact(buf)?)
    }

    pub fn close(&self) {
        let _ = self.inner.shutdown(Shutdown::Both);
    }

    /// A second handle on the same socket, so one thread can read device
    /// messages while another writes control messages.
    pub fn try_clone_for_read(&self) -> Result<Stream> {
        let inner = self.inner.try_clone()?;
        inner.set_read_timeout(None)?;
        Ok(Stream { inner })
    }
}

/// One-line command → one-line reply ("OK …" / "ERR …").
pub fn control(secret: &str, command: &str, timeout: Duration) -> Result<String> {
    let mut s = Stream::open(secret, timeout)?;
    s.write_all(format!("{command}\n").as_bytes())?;
    let reply = s.read_line()?;
    s.close();
    if reply.is_empty() {
        return Err(anyhow!("no reply"));
    }
    Ok(reply)
}
