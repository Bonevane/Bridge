import AppKit
import CoreBluetooth
import CryptoKit
import Foundation

/// The short-range link to the phone.
///
/// Notifications and clipboard are a few hundred bytes each, which doesn't
/// justify holding an internet tunnel open all day (measured: ~5.5 MB/hour just
/// in keepalives). Over Bluetooth they cost almost nothing and the phone needs
/// no privileges at all.
///
/// The Mac is the central: it scans for the phone's Bridge service, subscribes
/// to its notify characteristic, and writes to the other one. The phone requires
/// encryption on both, so this only works with a Mac the phone is paired with.
final class BluetoothLink: NSObject {

    /// What the link is doing, so the menu can say so instead of just "not nearby".
    enum LinkState {
        case off             // Bluetooth is switched off on the Mac
        case unauthorized    // macOS hasn't granted Bridge permission
        case searching       // looking for the phone
        case linked          // connected and subscribed
    }

    private(set) var state: LinkState = .off {
        didSet { if state != oldValue { onStateChange?(state) } }
    }
    var onStateChange: ((LinkState) -> Void)?
    private static let service = CBUUID(string: "b71d0001-5c8f-4b1e-9a3a-3f1f0a7c9e11")
    private static let txUUID = CBUUID(string: "b71d0002-5c8f-4b1e-9a3a-3f1f0a7c9e11")   // phone → Mac
    private static let rxUUID = CBUUID(string: "b71d0003-5c8f-4b1e-9a3a-3f1f0a7c9e11")   // Mac → phone

    private static let typeNotification: UInt8 = 1
    private static let typeClipboard: UInt8 = 2
    private static let typePing: UInt8 = 3
    private static let typeStatus: UInt8 = 4
    private static let typeCommand: UInt8 = 5
    /// The pairing handshake (see BleLink.kt). Nothing is trusted before it.
    private static let typeAuth: UInt8 = 6

    private var central: CBCentralManager?
    private var phone: CBPeripheral?
    private var rx: CBCharacteristic?
    private var inbox: [UInt8] = []
    private var wanted = false
    private var watchdog: Timer?
    /// When the phone last said anything. Its heartbeat arrives every 30 s.
    private var lastHeard = Date.distantPast
    /// Our half of the handshake: the nonce the phone must sign back.
    private var ourNonce: String?
    /// Set once the phone has proved it knows the pairing secret.
    private var verified = false

    private let log: (String) -> Void
    private let onNotification: (String) -> Void
    private let onClipboard: (String) -> Void
    /// What the phone says about itself: every field it reports, by name.
    var onStatus: (([String: String]) -> Void)?

    /// True while the phone is connected and subscribed.
    private(set) var isLinked = false {
        didSet { if isLinked != oldValue { onLinkChange?(isLinked) } }
    }
    var onLinkChange: ((Bool) -> Void)?

    init(log: @escaping (String) -> Void,
         onNotification: @escaping (String) -> Void,
         onClipboard: @escaping (String) -> Void) {
        self.log = log
        self.onNotification = onNotification
        self.onClipboard = onClipboard
        super.init()
    }

    func start() {
        guard !wanted else { return }
        wanted = true
        switch CBCentralManager.authorization {
        case .denied, .restricted:
            state = .unauthorized
            log("Bluetooth: Bridge isn't allowed to use Bluetooth (System Settings › Privacy & Security).")
        case .notDetermined:
            log("Bluetooth: asking macOS for permission…")
        default:
            break
        }
        // Created lazily: making a CBCentralManager is what triggers macOS's
        // Bluetooth permission prompt, so don't do it until it's wanted.
        central = CBCentralManager(delegate: self, queue: nil)
        // The phone's GATT server goes away whenever its app restarts, and
        // CoreBluetooth can sit on a stale peripheral for a long time before it
        // notices. Re-check every 10 s and start scanning again if we're adrift.
        watchdog = Timer.scheduledTimer(withTimeInterval: 10, repeats: true) { [weak self] _ in
            self?.ensureScanning()
        }
    }

    /// Starts the hunt again from scratch: drops anything half-connected and
    /// rescans. What the menu's refresh button calls.
    func rescan() {
        guard let central = central, central.state == .poweredOn else { return }
        if let stale = phone { central.cancelPeripheralConnection(stale) }
        phone = nil
        rx = nil
        isLinked = false
        state = .searching
        central.stopScan()
        central.scanForPeripherals(withServices: [Self.service])
        log("Bluetooth: looking again")
    }

    func stop() {
        wanted = false
        watchdog?.invalidate()
        watchdog = nil
        if let phone = phone { central?.cancelPeripheralConnection(phone) }
        central?.stopScan()
        phone = nil
        rx = nil
        isLinked = false
    }

    /// Drops anything stale and makes sure a scan is running when not linked.
    private func ensureScanning() {
        guard wanted, let central = central, central.state == .poweredOn else { return }
        // A link can look alive long after the phone's app was replaced: the
        // Bluetooth connection survives even though the service behind it is
        // gone. Missing two heartbeats means it's really finished.
        if isLinked, Date().timeIntervalSince(lastHeard) > 90 {
            log("Bluetooth: phone stopped answering, reconnecting")
            isLinked = false
        }
        guard !isLinked else { return }
        // A connection that's mid-handshake is not stale: give it the tick
        // after this one before treating it as dead. Without this the watchdog
        // tore down every link between "subscribed" and "verified".
        if let stale = phone {
            if stale.state == .connected, Date().timeIntervalSince(lastHeard) < 20 { return }
            central.cancelPeripheralConnection(stale)
            self.phone = nil
            rx = nil
        }
        if !central.isScanning {
            central.scanForPeripherals(withServices: [Self.service])
        }
    }

    /// Sends the Mac's clipboard to the phone. A normal Android app is allowed
    /// to *write* the clipboard in the background, so this needs no daemon.
    func sendClipboard(_ text: String) {
        send(type: Self.typeClipboard, text: text)
    }

    /// Asks the phone to switch its internet tunnel on or off. This is what lets
    /// the phone idle in the free Bluetooth-only mode until mirroring is wanted.
    func setPhoneTunnel(_ on: Bool) {
        send(type: Self.typeCommand, text: on ? "tunnel on" : "tunnel off")
    }

    /// Tells the phone which apps with an Android twin are open here, as
    /// Android package names, so it can hold back duplicate notifications.
    func reportOpenTwins(_ packages: Set<String>) {
        send(type: Self.typeCommand, text: "macapps " + packages.sorted().joined(separator: " "))
    }

    /// Asks the phone to report its state now, rather than waiting for the
    /// next 30-second heartbeat.
    func requestStatus() {
        send(type: Self.typeCommand, text: "status")
    }

    /// Pushes the "keep ready" choice to the phone. Works with no tunnel.
    func setKeepReady(_ on: Bool, changedAt: Double) {
        send(type: Self.typeCommand,
             text: "keep \(on ? "on" : "off") at=\(Int64(changedAt))")
    }

    /// Tells the phone the session is over, so it can turn USB debugging off.
    /// Bluetooth is the right channel for this: the tunnel is often exactly what
    /// has just died.
    func endSession() {
        send(type: Self.typeCommand, text: "session over")
    }

    private func send(type: UInt8, text: String) {
        guard let phone = phone, let rx = rx else { return }
        // Leave room for the 3-byte ATT header plus our own 2-byte prefix.
        let room = max(20, phone.maximumWriteValueLength(for: .withResponse) - 2)
        let bytes = Array(text.utf8)
        var offset = 0
        while offset < bytes.count {
            let size = min(room, bytes.count - offset)
            let more: UInt8 = (offset + size < bytes.count) ? 1 : 0
            let chunk = [type, more] + bytes[offset..<(offset + size)]
            phone.writeValue(Data(chunk), for: rx, type: .withResponse)
            offset += size
        }
    }

    /// "challenge <nonce>": answer with our HMAC and a nonce of our own.
    /// "ok <hmac>": the phone signed our nonce; now, and only now, it's ours.
    private func handshake(_ text: String) {
        let words = text.split(separator: " ").map(String.init)
        let secret = Keychain.get("pairSecret") ?? ""
        guard words.count == 2, !secret.isEmpty else {
            log("Bluetooth: can't answer the phone's challenge (no pairing secret; set up over USB)")
            return
        }
        switch words[0] {
        case "challenge":
            let nonce = Self.nonce()
            ourNonce = nonce
            send(type: Self.typeCommand, text: "auth \(Self.hmac(secret, words[1])) \(nonce)")
        case "ok":
            guard let nonce = ourNonce, Self.hmac(secret, nonce) == words[1] else {
                log("Bluetooth: the phone failed our challenge; dropping it")
                if let phone = phone { central?.cancelPeripheralConnection(phone) }
                return
            }
            ourNonce = nil
            verified = true
            isLinked = true
            state = .linked
            lastHeard = Date()
            log("Bluetooth: linked to the phone (verified)")
        default:
            break
        }
    }

    private static func hmac(_ secret: String, _ message: String) -> String {
        let key = SymmetricKey(data: Data(secret.utf8))
        return HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key)
            .map { String(format: "%02x", $0) }.joined()
    }

    private static func nonce() -> String {
        (0..<16).map { _ in String(format: "%02x", UInt8.random(in: 0...255)) }.joined()
    }

    /// Reassembles a chunked message from the phone.
    private func receive(_ data: Data) {
        lastHeard = Date()
        guard data.count >= 2 else { return }
        let type = data[data.startIndex]
        let more = data[data.startIndex + 1] == 1
        inbox.append(contentsOf: data.dropFirst(2))
        guard !more else { return }
        let text = String(decoding: inbox, as: UTF8.self)
        inbox.removeAll(keepingCapacity: true)
        if type == Self.typeAuth {
            handshake(text)
            return
        }
        guard verified else { return }          // nothing from an unverified phone counts
        switch type {
        case Self.typeNotification: onNotification(text)
        case Self.typeClipboard: onClipboard(text)
        case Self.typePing: break        // liveness only
        case Self.typeStatus:
            let fields = Dictionary(uniqueKeysWithValues: text.split(separator: " ").compactMap { field -> (String, String)? in
                let parts = field.split(separator: "=", maxSplits: 1)
                return parts.count == 2 ? (String(parts[0]), String(parts[1])) : nil
            })
            onStatus?(fields)
        default: break
        }
    }
}

extension BluetoothLink: CBCentralManagerDelegate, CBPeripheralDelegate {

    func centralManagerDidUpdateState(_ manager: CBCentralManager) {
        switch manager.state {
        case .poweredOn:
            guard wanted else { return }
            state = .searching
            manager.scanForPeripherals(withServices: [Self.service])
            log("Bluetooth: looking for the phone")
        case .unauthorized:
            state = .unauthorized
            log("Bluetooth: macOS hasn't given Bridge permission")
        case .poweredOff:
            state = .off
            log("Bluetooth: turned off")
            isLinked = false
        case .unsupported:
            state = .off
            log("Bluetooth: not supported on this Mac")
        case .unknown, .resetting:
            // Still starting up, or waiting on the permission prompt. Saying
            // "off" here was a lie, and hid a pending prompt behind a wrong label.
            state = .searching
        @unknown default:
            break
        }
    }

    func centralManager(_ manager: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        // Anyone can advertise our service UUID, but the handshake is what
        // decides; a wrong phone is dropped in a second. Pinning the last
        // verified identifier here was a mistake: after the bond is forgotten
        // and remade, the phone gets a new identifier, and the Mac then
        // ignored the real phone forever. So: connect to whoever advertises,
        // and let the challenge sort it out.
        manager.stopScan()
        phone = peripheral
        peripheral.delegate = self
        manager.connect(peripheral)
    }

    func centralManager(_ manager: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([Self.service])
    }

    func centralManager(_ manager: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        isLinked = false
        verified = false
        rx = nil
        // The reason matters: "out of range" is a guess. CoreBluetooth gives a
        // code (6 = timeout, 7 = peer closed, 13 = we closed it).
        log("Bluetooth: disconnected" + (error.map { " (\(($0 as NSError).code): \($0.localizedDescription))" } ?? " (by this Mac)"))
        if wanted {
            state = .searching
            manager.scanForPeripherals(withServices: [Self.service])
        }
    }

    func centralManager(_ manager: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        if wanted { manager.scanForPeripherals(withServices: [Self.service]) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: { $0.uuid == Self.service }) else { return }
        peripheral.discoverCharacteristics([Self.txUUID, Self.rxUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == Self.txUUID {
                peripheral.setNotifyValue(true, for: characteristic)
            } else if characteristic.uuid == Self.rxUUID {
                rx = characteristic
            }
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        if let error = error {
            log("Bluetooth: \(error.localizedDescription)")
            return
        }
        // Subscribed is not yet linked: the phone now challenges us, and we it.
        verified = false
        if characteristic.isNotifying {
            lastHeard = Date()
            log("Bluetooth: subscribed; waiting for the phone's challenge")
        } else {
            isLinked = false
            state = .searching
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard let data = characteristic.value else { return }
        receive(data)
    }
}
