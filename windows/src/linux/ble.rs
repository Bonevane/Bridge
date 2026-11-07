//! The Bluetooth LE link to the phone on Linux, through BlueZ: same service,
//! framing and handshake as ble.rs, but shaped differently because BlueZ is
//! async. One worker thread owns the whole link: it scans, connects, and then
//! a single loop handles notifications, outgoing messages and liveness, so
//! there's no shared link state to lock.
//!
//! Like Windows this uses the phone's *plain* door: no bond, and AES-GCM at
//! our own layer once the handshake has passed. Nothing here ever pairs.

pub use crate::event::Event;
use crate::event;
use crate::protocol::{self, Handshake, Inbox, Kind, SessionCrypto, Step};
use anyhow::{anyhow, bail, Context, Result};
use bluer::gatt::remote::{Characteristic, CharacteristicWriteRequest};
use bluer::gatt::WriteOp;
use bluer::{Adapter, AdapterEvent, AdapterProperty, Device, DeviceEvent, DeviceProperty, DiscoveryFilter, DiscoveryTransport, Uuid};
use futures::{pin_mut, StreamExt};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::{mpsc, watch};
use tokio::time::{sleep, timeout};

const SERVICE: Uuid = Uuid::from_u128(0xb71d0001_5c8f_4b1e_9a3a_3f1f0a7c9e11);
const TX: Uuid = Uuid::from_u128(0xb71d0004_5c8f_4b1e_9a3a_3f1f0a7c9e11);
const RX: Uuid = Uuid::from_u128(0xb71d0005_5c8f_4b1e_9a3a_3f1f0a7c9e11);

/// Nothing to hold on to: the radio watcher is a thread of its own here.
pub type Radio = ();

/// Reports the Bluetooth adapter's state, as on Windows, so the app can say
/// "Bluetooth is off" rather than look for the phone forever.
pub fn watch_radio(events: Sender<Event>) -> Option<Radio> {
    std::thread::spawn(move || {
        let Some(rt) = runtime() else { return };
        rt.block_on(async move {
            let adapter = match default_adapter().await {
                Ok(a) => a,
                Err(e) => {
                    crate::log!("bluetooth", "no adapter: {e:#}");
                    let _ = events.send(Event::Radio(None));
                    return;
                }
            };
            let powered = adapter.is_powered().await.unwrap_or(true);
            let _ = events.send(Event::Radio(Some(powered)));
            let Ok(changes) = adapter.events().await else { return };
            pin_mut!(changes);
            while let Some(change) = changes.next().await {
                if let AdapterEvent::PropertyChanged(AdapterProperty::Powered(on)) = change {
                    let _ = events.send(Event::Radio(Some(on)));
                }
            }
        });
    });
    Some(())
}

fn runtime() -> Option<tokio::runtime::Runtime> {
    tokio::runtime::Builder::new_current_thread().enable_all().build().ok()
}

async fn default_adapter() -> Result<Adapter> {
    let session = bluer::Session::new().await.context("talking to BlueZ (is bluetoothd running?)")?;
    session.default_adapter().await.context("finding a Bluetooth adapter")
}

/// What the app can see of the worker.
#[derive(Default)]
struct Shared {
    verified: AtomicBool,
    /// The phone said it's restarting for an update (see phone_is_restarting).
    restart_expected: Mutex<Option<Instant>>,
}

struct Worker {
    outbox: mpsc::UnboundedSender<(Kind, String)>,
    stop: watch::Sender<bool>,
    thread: std::thread::JoinHandle<()>,
}

pub struct Ble {
    secret: String,
    events: Sender<Event>,
    shared: Arc<Shared>,
    worker: Option<Worker>,
}

impl Ble {
    pub fn new(secret: &str, events: Sender<Event>) -> Self {
        Ble { secret: secret.to_string(), events, shared: Arc::default(), worker: None }
    }

    pub fn is_linked(&self) -> bool {
        self.shared.verified.load(Ordering::SeqCst)
    }

    /// Start looking for the phone. The worker rescans by itself after any
    /// drop, so this does nothing if it's already running.
    pub fn start(&mut self) -> Result<()> {
        if self.worker.as_ref().is_some_and(|w| !w.thread.is_finished()) {
            return Ok(());
        }
        let (outbox, outbox_rx) = mpsc::unbounded_channel();
        let (stop, stop_rx) = watch::channel(false);
        let (secret, events, shared) = (self.secret.clone(), self.events.clone(), self.shared.clone());
        let thread = std::thread::spawn(move || {
            let Some(rt) = runtime() else { return };
            rt.block_on(run(secret, events, shared, outbox_rx, stop_rx));
        });
        self.worker = Some(Worker { outbox, stop, thread });
        Ok(())
    }

    pub fn stop(&mut self) {
        self.shared.verified.store(false, Ordering::SeqCst);
        if let Some(w) = self.worker.take() {
            let _ = w.stop.send(true);
            // Short by construction: the worker's disconnect has a 2 s limit.
            let _ = w.thread.join();
        }
    }

    /// The worker watches the link itself.
    pub fn tick(&mut self) {}

    /// Bridge never bonds on Linux, so there's nothing to undo.
    pub fn unpair_all() {}
    pub fn unpair_device(_address: u64, _events: Sender<Event>) {}

    /// Queues a message for the phone. Err only if there is no link at all.
    pub fn send(&self, kind: Kind, text: &str) -> Result<()> {
        if !self.is_linked() && kind != Kind::Command {
            bail!("not linked");
        }
        let worker = self.worker.as_ref().ok_or_else(|| anyhow!("not running"))?;
        worker.outbox.send((kind, text.to_string())).map_err(|_| anyhow!("writer gone"))
    }

    pub fn send_command(&self, text: &str) -> Result<()> {
        self.send(Kind::Command, text)
    }

    /// The phone is about to restart for an update: its app goes away while
    /// the Bluetooth connection lingers, so reconnect as soon as it's quiet.
    pub fn phone_is_restarting(&self) {
        *self.shared.restart_expected.lock().unwrap() = Some(Instant::now());
    }
}

impl Drop for Ble {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Scan, link, and start over, until told to stop.
async fn run(
    secret: String,
    events: Sender<Event>,
    shared: Arc<Shared>,
    mut outbox: mpsc::UnboundedReceiver<(Kind, String)>,
    mut stop: watch::Receiver<bool>,
) {
    while !*stop.borrow() {
        let _ = events.send(Event::Searching);
        let outcome = attempt(&secret, &events, &shared, &mut outbox, &mut stop).await;
        shared.verified.store(false, Ordering::SeqCst);
        if *stop.borrow() {
            break;
        }
        if let Err(e) = outcome {
            crate::log!("bluetooth", "{e:#}");
            let _ = events.send(Event::Dropped(format!("{e:#}")));
        }
        tokio::select! {
            _ = sleep(Duration::from_secs(3)) => {}
            _ = stop.changed() => {}
        }
    }
}

/// One scan and whatever link comes of it. Ok only if told to stop.
async fn attempt(
    secret: &str,
    events: &Sender<Event>,
    shared: &Shared,
    outbox: &mut mpsc::UnboundedReceiver<(Kind, String)>,
    stop: &mut watch::Receiver<bool>,
) -> Result<()> {
    let adapter = default_adapter().await?;
    let device = tokio::select! {
        found = find_phone(&adapter, secret) => found?,
        _ = stop.changed() => return Ok(()),
    };
    // Stopping cancels the link wherever it has got to (connecting can take
    // 20 s), and the disconnect after it always runs.
    let result = tokio::select! {
        ended = run_link(&device, secret, events, shared, outbox) => ended,
        _ = stop.changed() => Ok(()),
    };
    let _ = timeout(Duration::from_secs(2), device.disconnect()).await;
    result
}

/// Waits for an advert from this pairing's phone.
async fn find_phone(adapter: &Adapter, secret: &str) -> Result<Device> {
    if !adapter.is_powered().await? {
        bail!("Bluetooth is off");
    }
    adapter
        .set_discovery_filter(DiscoveryFilter {
            uuids: [SERVICE].into_iter().collect(),
            transport: DiscoveryTransport::Le,
            duplicate_data: true,
            ..Default::default()
        })
        .await
        .context("setting the scan filter")?;
    // "with changes" because a phone BlueZ already remembers is never "added"
    // again; it only changes (its signal strength appears) as it comes back.
    let found = adapter.discover_devices_with_changes().await.context("starting the scan")?;
    pin_mut!(found);
    while let Some(change) = found.next().await {
        if let AdapterEvent::DeviceAdded(address) = change {
            let device = adapter.device(address)?;
            if is_our_phone(&device, secret).await {
                return Ok(device);
            }
        }
    }
    bail!("the scan ended")
}

async fn is_our_phone(device: &Device, secret: &str) -> bool {
    let offers_bridge = device.uuids().await.ok().flatten().is_some_and(|u| u.contains(&SERVICE));
    // No signal strength = BlueZ remembers it but isn't hearing it now: the
    // phone's address rotates, so that may be a ghost nobody can connect to.
    if !offers_bridge || device.rssi().await.ok().flatten().is_none() {
        return false;
    }
    // A phone whose advert says it belongs to another pairing (a tag from its
    // secret; see Pairing.advertTag) is skipped without connecting. No tag
    // (older app): try it.
    let makers = device.manufacturer_data().await.ok().flatten().unwrap_or_default();
    match makers.get(&0xFFFF) {
        Some(tag) if tag.len() >= 4 => hex::encode(&tag[..4]) == protocol::hmac_hex(secret, "bridge-advertise")[..8],
        _ => true,
    }
}

/// Connects and runs the link until it ends, which is always an error.
async fn run_link(
    device: &Device,
    secret: &str,
    events: &Sender<Event>,
    shared: &Shared,
    outbox: &mut mpsc::UnboundedReceiver<(Kind, String)>,
) -> Result<()> {
    let name = device.name().await.ok().flatten().unwrap_or_else(|| "phone".into());
    crate::log!("bluetooth", "found {name}");
    if !device.is_connected().await? {
        timeout(Duration::from_secs(20), device.connect()).await.context("connecting timed out")?.context("connecting")?;
    }
    let (tx, rx) = timeout(Duration::from_secs(20), characteristics(device)).await.context("finding the Bridge service timed out")??;
    let notifications = tx.notify().await.context("subscribing")?;
    pin_mut!(notifications);
    let changes = device.events().await?;
    pin_mut!(changes);
    // Chunk to the negotiated MTU rather than the 20-byte minimum. Asking
    // takes the write socket for a moment; writes then go through BlueZ.
    let mtu_payload = rx.write_io().await.map(|w| w.mtu().saturating_sub(3).clamp(20, 500)).unwrap_or(20);
    crate::log!("bluetooth", "subscribed (chunks of {mtu_payload} bytes); waiting for the phone's challenge");

    let mut inbox = Inbox::default();
    let mut handshake = Handshake::new(secret);
    let mut crypto: Option<SessionCrypto> = None;
    let mut verified = false;
    let mut last_heard = Instant::now();
    let mut probed_at: Option<Instant> = None;
    let mut clock = tokio::time::interval(Duration::from_secs(5));

    loop {
        tokio::select! {
            chunk = notifications.next() => {
                let Some(chunk) = chunk else { bail!("disconnected") };
                last_heard = Instant::now();
                let Some((kind, raw)) = inbox.push_bytes(&chunk) else { continue };
                if kind == Kind::Auth {
                    match handshake.handle(&String::from_utf8_lossy(&raw)) {
                        Step::Reply(r) => write_chunks(&rx, Kind::Command, r.as_bytes(), mtu_payload).await.context("answering the challenge")?,
                        Step::Verified => {
                            if let Some((phone_nonce, our_nonce)) = handshake.nonces.clone() {
                                crypto = Some(SessionCrypto::new(secret, &phone_nonce, &our_nonce));
                            }
                            verified = true;
                            shared.verified.store(true, Ordering::SeqCst);
                            crate::log!("bluetooth", "linked to the phone (verified, encrypted session)");
                            let _ = events.send(Event::Linked);
                        }
                        Step::Failed => bail!("the phone failed our challenge"),
                        Step::Ignore => {}
                    }
                    continue;
                }
                if !verified {
                    continue; // nothing from an unverified phone counts
                }
                let text = match crypto.as_ref() {
                    Some(c) => match c.open(&raw) {
                        Some(p) => String::from_utf8_lossy(&p).into_owned(),
                        None => {
                            crate::log!("bluetooth", "dropped a message that didn't decrypt");
                            continue;
                        }
                    },
                    None => String::from_utf8_lossy(&raw).into_owned(),
                };
                event::deliver(kind, text, events);
            }
            Some(change) = changes.next() => {
                if let DeviceEvent::PropertyChanged(DeviceProperty::Connected(false)) = change {
                    bail!("disconnected");
                }
            }
            Some((kind, text)) = outbox.recv() => {
                if !verified && kind != Kind::Command {
                    continue; // nobody to talk to; the message is stale anyway
                }
                let payload = match crypto.as_mut() {
                    Some(c) if verified => c.seal(text.as_bytes()),
                    _ => text.into_bytes(),
                };
                if let Err(e) = write_chunks(&rx, kind, &payload, mtu_payload).await {
                    crate::log!("bluetooth", "send failed: {e:#}");
                }
            }
            _ = clock.tick() => {
                let silent = last_heard.elapsed();
                if !verified {
                    if silent > Duration::from_secs(20) {
                        bail!("the phone never sent its challenge");
                    }
                    continue;
                }
                let mut restart = shared.restart_expected.lock().unwrap();
                if restart.is_some() && silent > Duration::from_secs(8) {
                    *restart = None;
                    bail!("the phone app is restarting for its update");
                }
                if restart.is_some_and(|t| t.elapsed() > Duration::from_secs(300)) {
                    *restart = None; // it never happened; back to normal
                }
                drop(restart);
                // An idle phone's CPU sleeps and its heartbeat with it, while
                // the link is fine, so a quiet phone is asked before it's
                // given up on (see ble.rs tick).
                if silent > Duration::from_secs(60) {
                    match probed_at {
                        Some(at) if at > last_heard => {
                            if at.elapsed() > Duration::from_secs(20) {
                                bail!("phone didn't answer");
                            }
                        }
                        _ => {
                            probed_at = Some(Instant::now());
                            let sealed = crypto.as_mut().map(|c| c.seal(b"status")).unwrap_or_else(|| b"status".to_vec());
                            let _ = write_chunks(&rx, Kind::Command, &sealed, mtu_payload).await;
                        }
                    }
                }
            }
        }
    }
}

/// The phone's plain TX and RX. A fresh link can take a moment to list them.
async fn characteristics(device: &Device) -> Result<(Characteristic, Characteristic)> {
    for attempt in 1..=3 {
        for service in device.services().await? {
            if service.uuid().await? != SERVICE {
                continue;
            }
            let (mut tx, mut rx) = (None, None);
            for c in service.characteristics().await? {
                match c.uuid().await? {
                    TX => tx = Some(c),
                    RX => rx = Some(c),
                    _ => {}
                }
            }
            if let (Some(tx), Some(rx)) = (tx, rx) {
                return Ok((tx, rx));
            }
        }
        crate::log!("bluetooth", "service query {attempt}/3: Bridge's service isn't listed yet");
        sleep(Duration::from_millis(1500)).await;
    }
    bail!("Bridge service not found on the phone")
}

async fn write_chunks(rx: &Characteristic, kind: Kind, payload: &[u8], mtu_payload: usize) -> Result<()> {
    let request = CharacteristicWriteRequest { op_type: WriteOp::Command, ..Default::default() };
    for chunk in protocol::chunk_bytes(kind, payload, mtu_payload) {
        timeout(Duration::from_secs(5), rx.write_ext(&chunk, &request)).await.context("write timed out")??;
    }
    Ok(())
}
