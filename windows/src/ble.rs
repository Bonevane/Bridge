//! The Bluetooth LE link to the phone, as the *central*: scan for the Bridge
//! service, connect, pair (bond) if needed, subscribe to the phone's TX
//! characteristic, run the handshake, then exchange chunked messages.
//!
//! This is BluetoothLink.swift on WinRT. Same service and characteristic
//! UUIDs, same framing, same handshake (protocol.rs).
//!
//! Windows differences that matter:
//!  - Bonding is explicit: `DeviceInformation.Pairing.Custom.PairAsync`, with
//!    a handler that accepts the pairing kinds the phone offers.
//!  - The encrypted CCCD on the phone means the subscribe fails until the
//!    link is encrypted; setting the characteristic's protection level to
//!    EncryptionAndAuthentication makes WinRT bring the encryption up first.
//!  - There is no "connect": WinRT connects lazily when a GATT operation
//!    needs the link, and drops it when nothing holds the device object.
//!    We keep the `BluetoothLEDevice` alive for the life of the link.

use crate::protocol::{self, Handshake, Inbox, Kind, SessionCrypto, Step};
use anyhow::{anyhow, bail, Context, Result};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use windows::core::{GUID, HSTRING};
use windows::Devices::Bluetooth::Advertisement::{
    BluetoothLEAdvertisementReceivedEventArgs, BluetoothLEAdvertisementWatcher,
};
use windows::Devices::Bluetooth::GenericAttributeProfile::{
    GattCharacteristic, GattClientCharacteristicConfigurationDescriptorValue, GattCommunicationStatus,
    GattDeviceService, GattOpenStatus, GattSession, GattSharingMode, GattValueChangedEventArgs, GattWriteOption,
};
use windows::Devices::Bluetooth::{BluetoothCacheMode, BluetoothConnectionStatus, BluetoothLEDevice};
use windows::Devices::Enumeration::DeviceInformation;
use windows::Foundation::TypedEventHandler;
use windows::Storage::Streams::{DataReader, DataWriter};

// Same UUIDs as BleLink.kt.
const SERVICE: GUID = GUID::from_u128(0xb71d0001_5c8f_4b1e_9a3a_3f1f0a7c9e11);
// The phone's *plain* door (see BleLink.kt): no bond, no link-layer
// encryption requirement; the session is AES-GCM encrypted at our layer once
// the handshake has passed. Windows' bond store was not reliable enough with
// Android as a peripheral to depend on.
const TX: GUID = GUID::from_u128(0xb71d0004_5c8f_4b1e_9a3a_3f1f0a7c9e11);
const RX: GUID = GUID::from_u128(0xb71d0005_5c8f_4b1e_9a3a_3f1f0a7c9e11);

/// What the link tells the rest of the app.
#[derive(Debug)]
pub enum Event {
    Searching,
    Linked,
    Dropped(String),
    Notification { app: String, title: String, body: String },
    Clipboard(String),
    Status(std::collections::HashMap<String, String>),
    /// From the tunnel thread: which Connect attempt, and START's reply or why it failed.
    Connected(u32, Result<String, String>),
}

/// The live link, shared between the WinRT callbacks and the app.
struct Link {
    device: Option<BluetoothLEDevice>,
    rx: Option<GattCharacteristic>,
    /// Kept alive on purpose: releasing the TX characteristic (or its
    /// service) drops the ValueChanged subscription with it, and the phone's
    /// notifications then arrive at nothing.
    tx: Option<GattCharacteristic>,
    service: Option<GattDeviceService>,
    inbox: Inbox,
    handshake: Handshake,
    verified: bool,
    last_heard: Instant,
    mtu_payload: usize,
    crypto: Option<SessionCrypto>,
}

/// One connect attempt at a time: a rescan mid-attempt used to start a second.
static CONNECTING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub struct Ble {
    secret: String,
    events: Sender<Event>,
    link: Arc<Mutex<Link>>,
    watcher: Option<BluetoothLEAdvertisementWatcher>,
    /// Outgoing messages. A writer thread drains this, so a slow or dead
    /// link never blocks the window (a write to a bonded phone could take
    /// 60 s to time out, and it used to do so on the UI thread).
    outbox: Sender<(Kind, String)>,
}

impl Ble {
    pub fn new(secret: &str, events: Sender<Event>) -> Self {
        let (outbox, outbox_rx) = std::sync::mpsc::channel::<(Kind, String)>();
        let link = Arc::new(Mutex::new(Link {
                device: None,
                rx: None,
                tx: None,
                service: None,
                inbox: Inbox::default(),
                handshake: Handshake::new(secret),
                verified: false,
                last_heard: Instant::now(),
                mtu_payload: 20,
                crypto: None,
            }));
        let writer_link = link.clone();
        std::thread::spawn(move || {
            for (kind, text) in outbox_rx {
                let mut l = writer_link.lock().unwrap();
                if !l.verified && kind != Kind::Command {
                    continue; // nobody to talk to; the message is stale anyway
                }
                let verified = l.verified;
                let payload = match l.crypto.as_mut() {
                    Some(c) if verified => c.seal(text.as_bytes()),
                    _ => text.as_bytes().to_vec(),
                };
                if let Err(e) = write_chunks(&l, kind, &payload) {
                    crate::log!("bluetooth", "send failed: {e:#}");
                }
            }
        });
        Ble { secret: secret.to_string(), events, link, watcher: None, outbox }
    }

    pub fn is_linked(&self) -> bool {
        self.link.lock().unwrap().verified
    }

    /// Start looking for the phone. Safe to call again to rescan.
    pub fn start(&mut self) -> Result<()> {
        self.stop_scan();
        let watcher = BluetoothLEAdvertisementWatcher::new()?;
        watcher.AdvertisementFilter()?.Advertisement()?.ServiceUuids()?.Append(SERVICE)?;
        let link = self.link.clone();
        let events = self.events.clone();
        let secret = self.secret.clone();
        watcher.Received(&TypedEventHandler::new(
            move |w: &Option<BluetoothLEAdvertisementWatcher>, args: &Option<BluetoothLEAdvertisementReceivedEventArgs>| {
                let (Some(w), Some(args)) = (w, args) else { return Ok(()) };
                if link.lock().unwrap().verified {
                    return Ok(()); // already linked; a rescan mustn't tear that down
                }
                // One at a time: stop scanning while we try this one.
                let _ = w.Stop();
                let address = args.BluetoothAddress()?;
                let link = link.clone();
                let events = events.clone();
                let secret = secret.clone();
                if CONNECTING.swap(true, std::sync::atomic::Ordering::SeqCst) {
                    return Ok(()); // an attempt is already running
                }
                std::thread::spawn(move || {
                    if let Err(e) = connect(address, &secret, link, events.clone()) {
                        crate::log!("bluetooth", "connect failed: {e:#}");
                        let _ = events.send(Event::Dropped(format!("{e:#}")));
                    }
                    CONNECTING.store(false, std::sync::atomic::Ordering::SeqCst);
                });
                Ok(())
            },
        ))?;
        watcher.Start()?;
        self.watcher = Some(watcher);
        let _ = self.events.send(Event::Searching);
        crate::log!("bluetooth", "looking for the phone");
        Ok(())
    }

    fn stop_scan(&mut self) {
        if let Some(w) = self.watcher.take() {
            let _ = w.Stop();
        }
    }

    /// Removes any Windows bond with a Bridge phone. Off the UI thread: each
    /// probe of a bonded-but-absent device can take a minute to time out.
    pub fn unpair_all() {
        std::thread::spawn(Self::unpair_all_now);
    }

    fn unpair_all_now() {
        let Ok(selector) = BluetoothLEDevice::GetDeviceSelectorFromPairingState(true) else { return };
        let Ok(infos) = DeviceInformation::FindAllAsyncAqsFilter(&selector).and_then(|op| op.get()) else { return };
        for i in 0..infos.Size().unwrap_or(0) {
            let Ok(info) = infos.GetAt(i) else { continue };
            let Ok(d) = BluetoothLEDevice::FromIdAsync(&info.Id().unwrap_or_default()).and_then(|op| op.get()) else { continue };
            // Only phones running Bridge: the ones advertising our service.
            let has_service = d
                .GetGattServicesForUuidWithCacheModeAsync(SERVICE, BluetoothCacheMode::Cached)
                .and_then(|op| op.get())
                .and_then(|r| r.Services())
                .and_then(|s| s.Size())
                .map(|n| n > 0)
                .unwrap_or(false);
            if !has_service {
                continue;
            }
            let name = d.Name().map(|n| n.to_string_lossy()).unwrap_or_default();
            match d.DeviceInformation().and_then(|i| i.Pairing()).and_then(|p| p.UnpairAsync()).and_then(|op| op.get()) {
                Ok(r) => crate::log!("bluetooth", "unpaired {name}: {:?}", r.Status()),
                Err(e) => crate::log!("bluetooth", "unpair {name} failed: {e}"),
            }
        }
    }

    pub fn stop(&mut self) {
        self.stop_scan();
        let mut l = self.link.lock().unwrap();
        l.verified = false;
        l.rx = None;
        l.tx = None;
        l.service = None;
        l.device = None; // dropping the device object lets WinRT close the link
    }

    /// Called every few seconds by the app: notices a silent link and rescans.
    pub fn tick(&mut self) {
        let dead = {
            let l = self.link.lock().unwrap();
            l.device.is_some() && l.last_heard.elapsed() > Duration::from_secs(90)
        };
        if dead {
            crate::log!("bluetooth", "phone stopped answering, reconnecting");
            self.stop();
            let _ = self.events.send(Event::Dropped("no heartbeat".into()));
            let _ = self.start();
        }
    }

    /// Queues a message for the phone. Returns Err only if there is no link
    /// at all; a queued message to a link that then drops is logged, not raised.
    pub fn send(&self, kind: Kind, text: &str) -> Result<()> {
        if !self.link.lock().unwrap().verified && kind != Kind::Command {
            bail!("not linked");
        }
        self.outbox.send((kind, text.to_string())).map_err(|_| anyhow!("writer gone"))
    }

    pub fn send_command(&self, text: &str) -> Result<()> {
        self.send(Kind::Command, text)
    }
}

fn write_chunks(l: &Link, kind: Kind, payload: &[u8]) -> Result<()> {
    let rx = l.rx.as_ref().ok_or_else(|| anyhow!("no RX characteristic"))?;
    for chunk in protocol::chunk_bytes(kind, payload, l.mtu_payload) {
        let writer = DataWriter::new()?;
        writer.WriteBytes(&chunk)?;
        let buffer = writer.DetachBuffer()?;
        let result = rx.WriteValueWithResultAndOptionAsync(&buffer, GattWriteOption::WriteWithoutResponse)?.get()?;
        if result.Status()? != GattCommunicationStatus::Success {
            bail!("write failed: {:?}", result.Status()?);
        }
    }
    Ok(())
}

/// The whole connect sequence, on its own thread: device → pair → service →
/// characteristics → subscribe. The handshake then runs in the ValueChanged
/// callback.
fn connect(address: u64, secret: &str, link: Arc<Mutex<Link>>, events: Sender<Event>) -> Result<()> {
    let device = BluetoothLEDevice::FromBluetoothAddressAsync(address)?.get().context("opening the device")?;
    let name = device.Name()?.to_string_lossy();
    let is_paired = device.DeviceInformation()?.Pairing()?.IsPaired()?;
    crate::log!("bluetooth", "found {name} (paired={is_paired})");
    if is_paired {
        // Not needed any more, and Windows behaves worse with one: the bonded
        // link came up "Connected" with no working ATT. Say so once.
        crate::log!("bluetooth", "note: the phone is bonded in Windows settings; Bridge no longer needs that, and removing it avoids trouble");
    }

    let service = find_service(&device)?;
    let open = service.OpenAsync(GattSharingMode::SharedReadAndWrite)?.get()?;
    if open != GattOpenStatus::Success && open != GattOpenStatus::AlreadyOpened {
        bail!("couldn't open the Bridge service: {:?}", open);
    }
    let tx = characteristic(&service, TX)?;
    let rx = characteristic(&service, RX)?;
    crate::log!("bluetooth", "service and characteristics found");

    {
        let mut l = link.lock().unwrap();
        l.rx = Some(rx.clone());
        l.tx = Some(tx.clone());
        l.service = Some(service.clone());
        l.handshake = Handshake::new(secret);
        l.verified = false;
        l.crypto = None;
        l.last_heard = Instant::now();
        l.mtu_payload = 20; // conservative; the phone's chunking allows for it
    }

    // Incoming chunks arrive here.
    let link_cb = link.clone();
    let events_cb = events.clone();
    let secret_cb = secret.to_string();
    tx.ValueChanged(&TypedEventHandler::new(
        move |_: &Option<GattCharacteristic>, args: &Option<GattValueChangedEventArgs>| {
            let Some(args) = args else { return Ok(()) };
            let buffer = args.CharacteristicValue()?;
            let reader = DataReader::FromBuffer(&buffer)?;
            let mut bytes = vec![0u8; buffer.Length()? as usize];
            reader.ReadBytes(&mut bytes)?;
            receive(&bytes, &link_cb, &events_cb, &secret_cb);
            Ok(())
        },
    ))?;

    // Notice the link going away.
    let link_cs = link.clone();
    let events_cs = events.clone();
    device.ConnectionStatusChanged(&TypedEventHandler::new(
        move |d: &Option<BluetoothLEDevice>, _: &Option<windows::core::IInspectable>| {
            if let Some(d) = d {
                if d.ConnectionStatus()? == BluetoothConnectionStatus::Disconnected {
                    let mut l = link_cs.lock().unwrap();
                    if l.device.is_some() {
                        l.verified = false;
                        l.rx = None;
                        l.tx = None;
                        l.service = None;
                        l.device = None;
                        l.crypto = None;
                        crate::log!("bluetooth", "disconnected");
                        let _ = events_cs.send(Event::Dropped("disconnected".into()));
                    }
                }
            }
            Ok(())
        },
    ))?;

    // Subscribe: the helper first; if it errors, the descriptor by hand.
    let mut subscribed = false;
    for attempt in 1..=4 {
        let status = match tx.WriteClientCharacteristicConfigurationDescriptorAsync(
            GattClientCharacteristicConfigurationDescriptorValue::Notify,
        ) {
            Ok(op) => op.get().map(|s| format!("{s:?}")).unwrap_or_else(|e| format!("error {e}")),
            Err(e) => format!("error {e}"),
        };
        if status == format!("{:?}", GattCommunicationStatus::Success) {
            subscribed = true;
            break;
        }
        crate::log!("bluetooth", "subscribe {attempt}/4 via helper: {status}; trying the descriptor directly");
        if let Ok(true) = subscribe_direct(&tx) {
            subscribed = true;
            break;
        }
        std::thread::sleep(Duration::from_millis(1500));
    }
    if !subscribed {
        bail!("subscribe failed");
    }
    // Chunk to the negotiated MTU rather than the 20-byte minimum: Windows
    // and Android usually agree on 500+, which makes long clipboard text and
    // notifications arrive in one or two chunks instead of dozens.
    let mtu_payload = GattSession::FromDeviceIdAsync(&device.BluetoothDeviceId()?)
        .and_then(|op| op.get())
        .and_then(|session| session.MaxPduSize())
        .map(|pdu| (pdu as usize).saturating_sub(3).clamp(20, 500))
        .unwrap_or(20);
    {
        let mut l = link.lock().unwrap();
        l.mtu_payload = mtu_payload;
        l.device = Some(device);
    }
    crate::log!("bluetooth", "subscribed (chunks of {mtu_payload} bytes); waiting for the phone's challenge");
    Ok(())
}

/// Writes the Client Characteristic Configuration descriptor (0x2902) by hand.
fn subscribe_direct(tx: &GattCharacteristic) -> Result<bool> {
    let cccd = GUID::from_u128(0x00002902_0000_1000_8000_00805f9b34fb);
    let r = tx.GetDescriptorsForUuidWithCacheModeAsync(cccd, BluetoothCacheMode::Uncached)?.get()?;
    if r.Status()? != GattCommunicationStatus::Success || r.Descriptors()?.Size()? == 0 {
        crate::log!("bluetooth", "no CCCD descriptor visible ({:?})", r.Status()?);
        return Ok(false);
    }
    let d = r.Descriptors()?.GetAt(0)?;
    let writer = DataWriter::new()?;
    writer.WriteBytes(&[1, 0])?; // notifications on
    let result = d.WriteValueWithResultAsync(&writer.DetachBuffer()?)?.get()?;
    let st = result.Status()?;
    crate::log!("bluetooth", "direct CCCD write: {st:?} (protocol error {:?})", result.ProtocolError().ok().and_then(|p| p.Value().ok()));
    Ok(st == GattCommunicationStatus::Success)
}

/// The Bridge service, with a few retries: the first query on a fresh link
/// can come back Unreachable while the connection is still being set up.
fn find_service(device: &BluetoothLEDevice) -> Result<GattDeviceService> {
    let mut last = GattCommunicationStatus::Unreachable;
    for attempt in 1..=4 {
        let r = device.GetGattServicesForUuidWithCacheModeAsync(SERVICE, BluetoothCacheMode::Uncached)?.get()?;
        last = r.Status()?;
        if last == GattCommunicationStatus::Success && r.Services()?.Size()? > 0 {
            return Ok(r.Services()?.GetAt(0)?);
        }
        crate::log!("bluetooth", "service query {attempt}/4: {last:?} (connection {:?})", device.ConnectionStatus()?);
        std::thread::sleep(Duration::from_millis(1500));
    }
    bail!("Bridge service not found on the phone ({last:?})")
}

fn characteristic(service: &GattDeviceService, uuid: GUID) -> Result<GattCharacteristic> {
    let result = service.GetCharacteristicsForUuidWithCacheModeAsync(uuid, BluetoothCacheMode::Uncached)?.get()?;
    let status = result.Status()?;
    let found = result.Characteristics()?;
    if status != GattCommunicationStatus::Success || found.Size()? == 0 {
        // Say what the phone did offer, so a mismatch is obvious from the log.
        let all = service.GetCharacteristicsWithCacheModeAsync(BluetoothCacheMode::Uncached)?.get()?;
        let listed: Vec<String> = (0..all.Characteristics()?.Size()?)
            .filter_map(|i| all.Characteristics().ok()?.GetAt(i).ok()?.Uuid().ok().map(|u| format!("{u:?}")))
            .collect();
        bail!(
            "characteristic {uuid:?} not found (query status {status:?}, all-characteristics status {:?}, offered: [{}])",
            all.Status()?,
            listed.join(", ")
        );
    }
    Ok(found.GetAt(0)?)
}

/// One chunk in from the phone.
fn receive(bytes: &[u8], link: &Arc<Mutex<Link>>, events: &Sender<Event>, secret: &str) {
    let mut l = link.lock().unwrap();
    l.last_heard = Instant::now();
    let Some((kind, raw)) = l.inbox.push_bytes(bytes) else { return };
    if !l.verified {
        crate::log!("bluetooth", "message from the phone: {kind:?}, {} bytes", raw.len());
    }

    if kind == Kind::Auth {
        let text = String::from_utf8_lossy(&raw).into_owned();
        match l.handshake.handle(&text) {
            Step::Reply(r) => {
                if let Err(e) = write_chunks(&l, Kind::Command, r.as_bytes()) {
                    crate::log!("bluetooth", "couldn't answer the challenge: {e:#}");
                }
            }
            Step::Verified => {
                if let Some((phone_nonce, our_nonce)) = l.handshake.nonces.clone() {
                    l.crypto = Some(SessionCrypto::new(secret, &phone_nonce, &our_nonce));
                }
                l.verified = true;
                crate::log!("bluetooth", "linked to the phone (verified, encrypted session)");
                let _ = events.send(Event::Linked);
            }
            Step::Failed => {
                crate::log!("bluetooth", "the phone failed our challenge; dropping it");
                l.device = None;
                l.rx = None;
                l.tx = None;
                l.service = None;
            }
            Step::Ignore => {}
        }
        return;
    }
    if !l.verified {
        return; // nothing from an unverified phone counts
    }
    let text = match l.crypto.as_ref() {
        Some(c) => match c.open(&raw) {
            Some(p) => String::from_utf8_lossy(&p).into_owned(),
            None => {
                crate::log!("bluetooth", "dropped a message that didn't decrypt");
                return;
            }
        },
        None => String::from_utf8_lossy(&raw).into_owned(),
    };
    match kind {
        Kind::Notification => {
            if let Some((app, title, body)) = protocol::parse_notification(&text) {
                let _ = events.send(Event::Notification { app, title, body });
            }
        }
        Kind::Clipboard => {
            let _ = events.send(Event::Clipboard(text));
        }
        Kind::Status => {
            let _ = events.send(Event::Status(protocol::parse_status(&text)));
        }
        Kind::Ping | Kind::Command | Kind::Auth => {}
    }
}

/// A name for the log, without the address (which is a stable identifier).
#[allow(dead_code)]
pub fn describe(device: &BluetoothLEDevice) -> String {
    device.Name().map(|n| n.to_string_lossy()).unwrap_or_else(|_| "phone".into())
}

#[allow(dead_code)]
fn hstring(s: &str) -> HSTRING {
    HSTRING::from(s)
}
