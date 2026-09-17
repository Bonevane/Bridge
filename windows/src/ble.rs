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

use crate::protocol::{self, Handshake, Inbox, Kind, Step};
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
    GattDeviceService, GattOpenStatus, GattProtectionLevel, GattSharingMode, GattValueChangedEventArgs,
    GattWriteOption,
};
use windows::Devices::Bluetooth::{BluetoothCacheMode, BluetoothConnectionStatus, BluetoothLEDevice};
use windows::Devices::Enumeration::{DeviceInformation, DevicePairingKinds, DevicePairingRequestedEventArgs, DevicePairingResultStatus};
use windows::Foundation::TypedEventHandler;
use windows::Storage::Streams::{DataReader, DataWriter};

// Same UUIDs as BleLink.kt.
const SERVICE: GUID = GUID::from_u128(0xb71d0001_5c8f_4b1e_9a3a_3f1f0a7c9e11);
const TX: GUID = GUID::from_u128(0xb71d0002_5c8f_4b1e_9a3a_3f1f0a7c9e11);
const RX: GUID = GUID::from_u128(0xb71d0003_5c8f_4b1e_9a3a_3f1f0a7c9e11);

/// What the link tells the rest of the app.
#[derive(Debug)]
pub enum Event {
    Searching,
    Linked,
    Dropped(String),
    Notification { app: String, title: String, body: String },
    Clipboard(String),
    Status(std::collections::HashMap<String, String>),
}

/// The live link, shared between the WinRT callbacks and the app.
struct Link {
    device: Option<BluetoothLEDevice>,
    rx: Option<GattCharacteristic>,
    inbox: Inbox,
    handshake: Handshake,
    verified: bool,
    last_heard: Instant,
    mtu_payload: usize,
}

/// One connect attempt at a time: a rescan mid-attempt used to start a second.
static CONNECTING: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

pub struct Ble {
    secret: String,
    events: Sender<Event>,
    link: Arc<Mutex<Link>>,
    watcher: Option<BluetoothLEAdvertisementWatcher>,
}

impl Ble {
    pub fn new(secret: &str, events: Sender<Event>) -> Self {
        Ble {
            secret: secret.to_string(),
            events,
            link: Arc::new(Mutex::new(Link {
                device: None,
                rx: None,
                inbox: Inbox::default(),
                handshake: Handshake::new(secret),
                verified: false,
                last_heard: Instant::now(),
                mtu_payload: 20,
            })),
            watcher: None,
        }
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

    /// Removes the Windows bond with the phone, so the next attempt pairs afresh.
    pub fn unpair_all() {
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

    pub fn send(&self, kind: Kind, text: &str) -> Result<()> {
        let l = self.link.lock().unwrap();
        if !l.verified && kind != Kind::Command {
            bail!("not linked");
        }
        write_chunks(&l, kind, text)
    }

    pub fn send_command(&self, text: &str) -> Result<()> {
        self.send(Kind::Command, text)
    }
}

fn write_chunks(l: &Link, kind: Kind, text: &str) -> Result<()> {
    let rx = l.rx.as_ref().ok_or_else(|| anyhow!("no RX characteristic"))?;
    for chunk in protocol::chunk(kind, text, l.mtu_payload) {
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
    let advertised = BluetoothLEDevice::FromBluetoothAddressAsync(address)?.get().context("opening the device")?;
    let name = advertised.Name()?.to_string_lossy();
    let is_paired = advertised.DeviceInformation()?.Pairing()?.IsPaired()?;
    crate::log!("bluetooth", "found {name} (paired={is_paired})");

    // Once bonded, the phone must be reached through its *paired device
    // record*, not through the random address it advertises: Windows connects
    // to the bonded identity, and a device object made from the advertised
    // address reports "Connected" while its ATT requests go nowhere.
    let device = if is_paired {
        match paired_record(&name)? {
            Some(d) => {
                crate::log!("bluetooth", "using the paired record {} ({:?})", d.DeviceId()?.to_string_lossy(), d.BluetoothAddressType()?);
                d
            }
            None => {
                crate::log!("bluetooth", "no paired record by that name; using the advertised address");
                advertised
            }
        }
    } else {
        advertised
    };
    let pairing = device.DeviceInformation()?.Pairing()?;
    if is_paired {
        // What kind of bond Windows holds. An LE bond made with a passkey
        // reports EncryptionAndAuthentication; a classic-only bond shows up
        // differently here, which would explain encryption never coming up.
        crate::log!("bluetooth", "bond protection level: {:?}", pairing.ProtectionLevel()?);
    }

    // Discovery first, on whatever link we have. It needs no encryption, and
    // doing it *after* bonding hit a Windows quirk where the post-bond link
    // came up "Connected" but every ATT request timed out.
    let service = find_service(&device)?;
    let open = service.OpenAsync(GattSharingMode::SharedReadAndWrite)?.get()?;
    if open != GattOpenStatus::Success && open != GattOpenStatus::AlreadyOpened {
        bail!("couldn't open the Bridge service: {:?}", open);
    }
    let tx = characteristic(&service, TX)?;
    let rx = characteristic(&service, RX)?;
    crate::log!("bluetooth", "service and characteristics found");

    // Bond if we haven't, on this same link. The phone's characteristics are
    // encrypted, so an unbonded link gets nothing past this point.
    if !pairing.IsPaired()? {
        crate::log!("bluetooth", "pairing: confirm the code on the phone");
        let custom = pairing.Custom()?;
        custom.PairingRequested(&TypedEventHandler::new(
            |_: &Option<windows::Devices::Enumeration::DeviceInformationCustomPairing>,
             args: &Option<DevicePairingRequestedEventArgs>| {
                if let Some(args) = args {
                    if let Ok(pin) = args.Pin() {
                        let pin = pin.to_string_lossy();
                        if !pin.is_empty() {
                            crate::log!("bluetooth", "pairing code {pin}");
                        }
                    }
                    args.Accept()?;
                }
                Ok(())
            },
        ))?;
        let result = custom
            .PairAsync(DevicePairingKinds::ConfirmOnly | DevicePairingKinds::ConfirmPinMatch | DevicePairingKinds::DisplayPin)?
            .get()?;
        let status = result.Status()?;
        if status != DevicePairingResultStatus::Paired && status != DevicePairingResultStatus::AlreadyPaired {
            bail!("pairing failed: {:?}", status);
        }
        crate::log!("bluetooth", "paired");
    }

    // Encryption on the characteristics, so the subscribe goes over an
    // encrypted link (the phone's descriptor insists).
    tx.SetProtectionLevel(GattProtectionLevel::EncryptionAndAuthenticationRequired)?;
    rx.SetProtectionLevel(GattProtectionLevel::EncryptionAndAuthenticationRequired)?;

    {
        let mut l = link.lock().unwrap();
        l.rx = Some(rx.clone());
        l.handshake = Handshake::new(secret);
        l.verified = false;
        l.last_heard = Instant::now();
        l.mtu_payload = 20; // conservative; the phone's chunking allows for it
    }

    // Incoming chunks arrive here.
    let link_cb = link.clone();
    let events_cb = events.clone();
    tx.ValueChanged(&TypedEventHandler::new(
        move |_: &Option<GattCharacteristic>, args: &Option<GattValueChangedEventArgs>| {
            let Some(args) = args else { return Ok(()) };
            let buffer = args.CharacteristicValue()?;
            let reader = DataReader::FromBuffer(&buffer)?;
            let mut bytes = vec![0u8; buffer.Length()? as usize];
            reader.ReadBytes(&mut bytes)?;
            receive(&bytes, &link_cb, &events_cb);
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
                        l.device = None;
                        crate::log!("bluetooth", "disconnected");
                        let _ = events_cs.send(Event::Dropped("disconnected".into()));
                    }
                }
            }
            Ok(())
        },
    ))?;

    // Bring the encryption up before the subscribe: a read of the encrypted
    // characteristic makes Windows start it (it won't for a descriptor write
    // on some stacks, which then fails as "write not permitted").
    for attempt in 1..=5 {
        let r = rx.ReadValueWithCacheModeAsync(BluetoothCacheMode::Uncached)?.get()?;
        let st = r.Status()?;
        crate::log!("bluetooth", "encrypted read {attempt}/5: {st:?} (connection {:?})", device.ConnectionStatus()?);
        if st == GattCommunicationStatus::Success || st == GattCommunicationStatus::ProtocolError {
            break; // ProtocolError here = encrypted but the phone forbids reads: fine, the link is up
        }
        std::thread::sleep(Duration::from_millis(2000));
    }

    // Subscribe. The high-level helper first; if it errors, write the CCCD
    // descriptor (0x2902) by hand, which more stacks accept.
    let mut subscribed = false;
    for attempt in 1..=6 {
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
        crate::log!("bluetooth", "subscribe {attempt}/6 via helper: {status}; trying the descriptor directly");
        if let Ok(direct) = subscribe_direct(&tx) {
            if direct {
                subscribed = true;
                break;
            }
        }
        std::thread::sleep(Duration::from_millis(2000));
    }
    if !subscribed {
        bail!("subscribe failed");
    }
    link.lock().unwrap().device = Some(device);
    crate::log!("bluetooth", "subscribed; waiting for the phone's challenge");
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

/// The bonded record for a phone with this name, from Windows' paired-device list.
fn paired_record(name: &str) -> Result<Option<BluetoothLEDevice>> {
    let selector = BluetoothLEDevice::GetDeviceSelectorFromPairingState(true)?;
    let infos = DeviceInformation::FindAllAsyncAqsFilter(&selector)?.get()?;
    let mut fallback = None;
    for i in 0..infos.Size()? {
        let info = infos.GetAt(i)?;
        let Ok(d) = BluetoothLEDevice::FromIdAsync(&info.Id()?).and_then(|op| op.get()) else { continue };
        let n = d.Name()?.to_string_lossy();
        crate::log!("bluetooth", "paired record: {n} ({:?})", d.BluetoothAddressType()?);
        if n == name {
            return Ok(Some(d));
        }
        if fallback.is_none() && infos.Size()? == 1 {
            fallback = Some(d);
        }
    }
    Ok(fallback)
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
fn receive(bytes: &[u8], link: &Arc<Mutex<Link>>, events: &Sender<Event>) {
    let mut l = link.lock().unwrap();
    l.last_heard = Instant::now();
    let Some((kind, text)) = l.inbox.push(bytes) else { return };

    if kind == Kind::Auth {
        match l.handshake.handle(&text) {
            Step::Reply(r) => {
                if let Err(e) = write_chunks(&l, Kind::Command, &r) {
                    crate::log!("bluetooth", "couldn't answer the challenge: {e:#}");
                }
            }
            Step::Verified => {
                l.verified = true;
                crate::log!("bluetooth", "linked to the phone (verified)");
                let _ = events.send(Event::Linked);
            }
            Step::Failed => {
                crate::log!("bluetooth", "the phone failed our challenge; dropping it");
                l.device = None;
                l.rx = None;
            }
            Step::Ignore => {}
        }
        return;
    }
    if !l.verified {
        return; // nothing from an unverified phone counts
    }
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
