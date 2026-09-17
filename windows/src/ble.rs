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
    GattProtectionLevel, GattValueChangedEventArgs, GattWriteOption,
};
use windows::Devices::Bluetooth::{BluetoothConnectionStatus, BluetoothLEDevice};
use windows::Devices::Enumeration::{DevicePairingKinds, DevicePairingRequestedEventArgs, DevicePairingResultStatus};
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
                std::thread::spawn(move || {
                    if let Err(e) = connect(address, &secret, link, events.clone()) {
                        crate::log!("bluetooth", "connect failed: {e:#}");
                        let _ = events.send(Event::Dropped(format!("{e:#}")));
                    }
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
    let device = BluetoothLEDevice::FromBluetoothAddressAsync(address)?.get().context("opening the device")?;
    crate::log!("bluetooth", "found {}", device.Name()?.to_string_lossy());

    // Bond if we haven't. The phone's characteristics are encrypted, so an
    // unbonded link gets nothing. Accept whatever pairing kind Android offers
    // (usually a passkey to confirm on both screens).
    let pairing = device.DeviceInformation()?.Pairing()?;
    if !pairing.IsPaired()? {
        crate::log!("bluetooth", "pairing: accept the request on both screens");
        let custom = pairing.Custom()?;
        custom.PairingRequested(&TypedEventHandler::new(
            |_: &Option<windows::Devices::Enumeration::DeviceInformationCustomPairing>,
             args: &Option<DevicePairingRequestedEventArgs>| {
                if let Some(args) = args {
                    if let Ok(pin) = args.Pin() {
                        let pin = pin.to_string_lossy();
                        if !pin.is_empty() {
                            crate::log!("bluetooth", "pairing code {pin}: confirm it on the phone");
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

    let services = device.GetGattServicesForUuidAsync(SERVICE)?.get()?;
    if services.Status()? != GattCommunicationStatus::Success || services.Services()?.Size()? == 0 {
        bail!("Bridge service not found on the phone");
    }
    let service = services.Services()?.GetAt(0)?;
    let tx = characteristic(&service, TX)?;
    let rx = characteristic(&service, RX)?;
    // Encryption first, or the phone's encrypted descriptor refuses the subscribe.
    tx.SetProtectionLevel(GattProtectionLevel::EncryptionAndAuthentication)?;
    rx.SetProtectionLevel(GattProtectionLevel::EncryptionAndAuthentication)?;

    {
        let mut l = link.lock().unwrap();
        l.rx = Some(rx);
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

    let status = tx
        .WriteClientCharacteristicConfigurationDescriptorAsync(GattClientCharacteristicConfigurationDescriptorValue::Notify)?
        .get()?;
    if status != GattCommunicationStatus::Success {
        bail!("subscribe failed: {:?}", status);
    }
    link.lock().unwrap().device = Some(device);
    crate::log!("bluetooth", "subscribed; waiting for the phone's challenge");
    Ok(())
}

fn characteristic(
    service: &windows::Devices::Bluetooth::GenericAttributeProfile::GattDeviceService,
    uuid: GUID,
) -> Result<GattCharacteristic> {
    let result = service.GetCharacteristicsForUuidAsync(uuid)?.get()?;
    if result.Status()? != GattCommunicationStatus::Success || result.Characteristics()?.Size()? == 0 {
        bail!("characteristic {uuid:?} not found");
    }
    Ok(result.Characteristics()?.GetAt(0)?)
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
