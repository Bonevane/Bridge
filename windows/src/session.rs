//! One mirroring session: VIDEO, AUDIO and CTRL streams through the tunnel
//! (see ControlProxy.kt and Daemon.kt on the phone). Video packets go to the
//! decoder and out as frames; input from the window goes out as scrcpy
//! control messages. Port of Session.swift, including its adaptive bitrate.

use crate::tunnel::Stream;
use crate::{audio::AudioPlayer, scrcpy, video};
use anyhow::{anyhow, Result};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// Where decoded frames go: called on the video thread, as fast as they come.
pub type FrameSink = Arc<dyn Fn(video::Frame) + Send + Sync>;

/// What the session tells the UI.
pub enum SessionEvent {
    /// Video size from the server's session meta.
    Size(u32, u32),
    /// The phone's clipboard changed.
    Clipboard(String),
    Log(String),
    /// The video stream ended; the session is over.
    Ended(String),
}

struct Shared {
    frames: FrameSink,
    control: Mutex<Option<Stream>>,
    stopped: AtomicBool,
    restarting: AtomicBool,
    events: Sender<SessionEvent>,
    secret: String,
    base_options: String,
    target_bitrate: i64,
    level: Mutex<i32>,
    video_size: Mutex<(u16, u16)>,
}

pub struct Session {
    shared: Arc<Shared>,
}

impl Session {
    /// Opens the streams; `options` are scrcpy server options (video_bit_rate managed here).
    pub fn start(secret: &str, options: &str, events: Sender<SessionEvent>, frames: FrameSink) -> Result<Session> {
        let base: Vec<&str> = options.split(' ').filter(|o| !o.starts_with("video_bit_rate=")).collect();
        let target = options
            .split(' ')
            .find_map(|o| o.strip_prefix("video_bit_rate="))
            .and_then(|v| v.parse::<i64>().ok())
            .unwrap_or(4_000_000);
        let shared = Arc::new(Shared {
            frames,
            control: Mutex::new(None),
            stopped: AtomicBool::new(false),
            restarting: AtomicBool::new(false),
            events,
            secret: secret.to_string(),
            base_options: base.join(" "),
            target_bitrate: target,
            level: Mutex::new(0),
            video_size: Mutex::new((0, 0)),
        });
        open(&shared)?;
        Ok(Session { shared })
    }

    pub fn video_size(&self) -> (u16, u16) {
        *self.shared.video_size.lock().unwrap()
    }

    /// Sends one control message; drops it if the control stream is gone.
    pub fn send(&self, message: &[u8]) {
        if let Some(c) = self.shared.control.lock().unwrap().as_mut() {
            let _ = c.write_all(message);
        }
    }

    pub fn stop(&self) {
        self.shared.stopped.store(true, Ordering::SeqCst);
        if let Some(c) = self.shared.control.lock().unwrap().take() {
            c.close();
        }
    }
}

fn current_options(s: &Shared) -> String {
    let level = *s.level.lock().unwrap();
    let bitrate = (s.target_bitrate >> level).max(300_000);
    let mut opts = format!("{} video_bit_rate={bitrate}", s.base_options);
    if level >= 3 {
        opts.push_str(" max_size=720"); // keep text readable at very low rates
    }
    opts
}

fn open(shared: &Arc<Shared>) -> Result<()> {
    let options = current_options(shared);
    // Long timeouts on the media streams: a phone showing a static screen
    // sends no frames at all, and a dead tunnel shows up as a reset anyway.
    let mut v = Stream::open(&shared.secret, Duration::from_secs(600))?;
    v.write_all(format!("VIDEO {options}\n").as_bytes())?;
    let reply = v.read_line()?;
    let scid = reply.strip_prefix("OK scid=").ok_or_else(|| anyhow!("phone: {reply}"))?.to_string();

    let mut a = Stream::open(&shared.secret, Duration::from_secs(600))?;
    a.write_all(format!("AUDIO {scid}\n").as_bytes())?;
    let areply = a.read_line()?;
    if !areply.starts_with("OK") {
        return Err(anyhow!("phone: {areply}"));
    }

    let mut c = Stream::open(&shared.secret, Duration::from_secs(30))?;
    c.write_all(format!("CTRL {scid}\n").as_bytes())?;
    let creply = c.read_line()?;
    if !creply.starts_with("OK") {
        return Err(anyhow!("phone: {creply}"));
    }
    let _ = c.write_all(&scrcpy::get_clipboard()); // start in sync with the phone
    let control_reader = c.try_clone_for_read()?;
    *shared.control.lock().unwrap() = Some(c);

    let s = shared.clone();
    std::thread::spawn(move || read_video(s, v));
    let s = shared.clone();
    std::thread::spawn(move || read_audio(s, a));
    let s = shared.clone();
    std::thread::spawn(move || read_control(s, control_reader));
    Ok(())
}

fn be32(b: &[u8], o: usize) -> u32 {
    u32::from_be_bytes([b[o], b[o + 1], b[o + 2], b[o + 3]])
}

fn be64(b: &[u8], o: usize) -> u64 {
    (be32(b, o) as u64) << 32 | be32(b, o + 4) as u64
}

/// Restarts the stream one bitrate level up or down. Called from the video thread.
fn change_level(shared: &Arc<Shared>, new_level: i32, reason: &str) {
    if shared.stopped.load(Ordering::SeqCst) || !(0..=3).contains(&new_level) {
        return;
    }
    if shared.restarting.swap(true, Ordering::SeqCst) {
        return;
    }
    *shared.level.lock().unwrap() = new_level;
    let bitrate = (shared.target_bitrate >> new_level).max(300_000) / 1000;
    let _ = shared.events.send(SessionEvent::Log(format!("{reason}: bitrate → {bitrate} kbps")));
    if let Some(c) = shared.control.lock().unwrap().take() {
        c.close();
    }
    let s = shared.clone();
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(300)); // let the phone tear the old server down
        if let Err(e) = open(&s) {
            let _ = s.events.send(SessionEvent::Ended(format!("restart failed: {e:#}")));
        }
        s.restarting.store(false, Ordering::SeqCst);
    });
}

fn read_video(shared: Arc<Shared>, mut s: Stream) {
    let result: Result<()> = (|| {
        let mut name = [0u8; 64];
        s.read_exact(&mut name)?;
        let device = String::from_utf8_lossy(&name).trim_end_matches('\0').to_string();
        let mut codec = [0u8; 4];
        s.read_exact(&mut codec)?;
        let _ = shared.events.send(SessionEvent::Log(format!("Video from {device}, codec {}", String::from_utf8_lossy(&codec))));

        let mut decoder = video::Decoder::new()?;
        let mut first: Option<(i64, Instant)> = None;
        let mut min_lag = f64::INFINITY;
        let mut slow_since: Option<Instant> = None;
        let level_start = Instant::now();

        while !shared.stopped.load(Ordering::SeqCst) {
            let mut header = [0u8; 12];
            s.read_exact(&mut header)?;
            // Session meta and frame headers share the 12-byte slot; the top bit tells them apart.
            if header[0] & 0x80 != 0 {
                let (w, h) = (be32(&header, 4), be32(&header, 8));
                *shared.video_size.lock().unwrap() = (w as u16, h as u16);
                let _ = shared.events.send(SessionEvent::Size(w, h));
                continue;
            }
            let pts_and_flags = be64(&header, 0);
            let size = be32(&header, 8) as usize;
            let is_config = pts_and_flags & (1 << 62) != 0;
            let is_key = pts_and_flags & (1 << 61) != 0;
            let pts = (pts_and_flags & ((1 << 61) - 1)) as i64;
            let mut data = vec![0u8; size];
            s.read_exact(&mut data)?;
            match decoder.handle(&data, is_config, is_key, pts) {
                Ok(frames) => {
                    for f in frames {
                        (shared.frames)(f);
                    }
                }
                Err(e) => {
                    let _ = shared.events.send(SessionEvent::Log(format!("decoder: {e:#}")));
                }
            }
            if is_config {
                continue;
            }
            // Adaptive bitrate: if frames arrive later and later relative to
            // their capture time, bits are queueing up on the link.
            let now = Instant::now();
            let Some((first_pts, first_arrival)) = first else { first = Some((pts, now)); continue };
            let lag = now.duration_since(first_arrival).as_secs_f64() - (pts - first_pts) as f64 / 1_000_000.0;
            min_lag = min_lag.min(lag);
            let backlog = lag - min_lag;
            let level = *shared.level.lock().unwrap();
            if backlog > 0.35 {
                let since = *slow_since.get_or_insert(now);
                if now.duration_since(since).as_secs_f64() > 1.5 && level < 3 {
                    change_level(&shared, level + 1, &format!("Link is slow ({} ms behind)", (backlog * 1000.0) as i64));
                    return Ok(());
                }
            } else {
                slow_since = None;
                if level > 0 && now.duration_since(level_start).as_secs() > 30 && backlog < 0.1 {
                    change_level(&shared, level - 1, "Link looks fine again");
                    return Ok(());
                }
            }
        }
        Ok(())
    })();
    if let Err(e) = result {
        if !shared.stopped.load(Ordering::SeqCst) && !shared.restarting.load(Ordering::SeqCst) {
            let _ = shared.events.send(SessionEvent::Ended(format!("{e:#}")));
        }
    }
}

/// Same framing as video: 4-byte codec id, then 12-byte packet headers.
/// A codec id of 0 or 1 means the phone couldn't capture audio.
fn read_audio(shared: Arc<Shared>, mut s: Stream) {
    let _ = (|| -> Result<()> {
        let mut codec = [0u8; 4];
        s.read_exact(&mut codec)?;
        let id = be32(&codec, 0);
        if id == 0 || id == 1 {
            let _ = shared.events.send(SessionEvent::Log(format!("Phone: no audio (code {id})")));
            return Ok(());
        }
        let mut player = AudioPlayer::new();
        while !shared.stopped.load(Ordering::SeqCst) {
            let mut header = [0u8; 12];
            s.read_exact(&mut header)?;
            let pts_and_flags = be64(&header, 0);
            let size = be32(&header, 8) as usize;
            let mut data = vec![0u8; size];
            s.read_exact(&mut data)?;
            player.handle(&data, pts_and_flags & (1 << 62) != 0);
        }
        Ok(())
    })();
}

/// Device → client messages (clipboard etc.). Must be drained or the server blocks.
fn read_control(shared: Arc<Shared>, mut s: Stream) {
    while !shared.stopped.load(Ordering::SeqCst) {
        let mut kind = [0u8; 1];
        if s.read_exact(&mut kind).is_err() {
            return;
        }
        match kind[0] {
            0 => {
                let mut len = [0u8; 4];
                if s.read_exact(&mut len).is_err() {
                    return;
                }
                let n = be32(&len, 0) as usize;
                if n > 1 << 20 {
                    return;
                }
                let mut text = vec![0u8; n];
                if s.read_exact(&mut text).is_err() {
                    return;
                }
                let _ = shared.events.send(SessionEvent::Clipboard(String::from_utf8_lossy(&text).into_owned()));
            }
            1 => {
                let mut seq = [0u8; 8];
                if s.read_exact(&mut seq).is_err() {
                    return;
                }
            }
            2 => {
                let mut head = [0u8; 4];
                if s.read_exact(&mut head).is_err() {
                    return;
                }
                let size = (head[2] as usize) << 8 | head[3] as usize;
                let mut data = vec![0u8; size];
                if s.read_exact(&mut data).is_err() {
                    return;
                }
            }
            _ => return,
        }
    }
}
