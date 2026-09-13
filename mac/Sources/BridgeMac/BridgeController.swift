import AppKit
import Foundation
import ServiceManagement

/// Everything the menu does: pairing over USB, and Connect / Disconnect.
///
/// Connect: dumbpipe connect-tcp --addr 127.0.0.1:7555 <ticket>, then "START"
/// over that port (phone brings up its daemon), then the VIDEO/CTRL streams.
/// adb is only used by "Set up over USB".
@MainActor
final class BridgeController: ObservableObject {
    static let shared = BridgeController()

    enum Phase: Equatable {
        case idle
        case working(String)
        case connected
        case failed(String)
    }

    // Must match the Android app.
    static let phonePackage = "com.bonevane.bridge"
    static let ticketURI = "content://com.bonevane.bridge.ticket/ticket"

    @Published var phase: Phase = .idle
    @Published var notice: String?
    @Published var log: [String] = []
    // Which disclosure groups are open in the menu. These live here instead of
    // `@State` in the view because `@State` is a compiler macro on recent SDKs
    // and the Command Line Tools (no Xcode) can't expand SwiftUI macros.
    @Published var showOptions = false
    @Published var showLog = false

    @Published var ticket: String {
        didSet { UserDefaults.standard.set(ticket, forKey: "ticket") }
    }
    @Published var bitrateMbps: Int {
        didSet { UserDefaults.standard.set(bitrateMbps, forKey: "bitrateMbps") }
    }
    @Published var maxSize: Int {
        didSet { UserDefaults.standard.set(maxSize, forKey: "maxSize") }
    }
    @Published var turnScreenOff: Bool {
        didSet { UserDefaults.standard.set(turnScreenOff, forKey: "turnScreenOff") }
    }
    /// Take the phone's audio instead of copying it (phone goes silent while mirroring).
    @Published var mutePhone: Bool {
        didSet { UserDefaults.standard.set(mutePhone, forKey: "mutePhone") }
    }
    /// Sync the clipboard both ways while a session is open.
    @Published var syncClipboard: Bool {
        didSet { UserDefaults.standard.set(syncClipboard, forKey: "syncClipboard")
                 syncClipboard ? startClipboardWatch() : stopClipboardWatch() }
    }
    /// Keep the clipboard synced even with no mirroring window.
    /// This only works while the phone's daemon is alive, so it implies "keep ready".
    @Published var backgroundClipboard: Bool {
        didSet {
            UserDefaults.standard.set(backgroundClipboard, forKey: "backgroundClipboard")
            if backgroundClipboard {
                if !syncClipboard { syncClipboard = true }
                if !keepReady {
                    keepReady = true   // pushes MODE keep; the daemon must stay up
                    notice = "Keeping the phone ready too: background clipboard needs it."
                }
            }
            updateBackgroundClipboard()
        }
    }
    /// Prefer Bluetooth for notifications and clipboard when the phone is nearby.
    @Published var useBluetooth: Bool {
        didSet {
            UserDefaults.standard.set(useBluetooth, forKey: "useBluetooth")
            updateBluetooth()
        }
    }
    /// True while the phone is linked over Bluetooth.
    @Published var bluetoothLinked = false
    /// What the Bluetooth link is doing, for the menu's spinner and wording.
    @Published var bluetoothState: BluetoothLink.LinkState = .off
    /// Reported by the phone over Bluetooth, so the Mac can say what works.
    @Published var phoneDaemonAlive = false
    @Published var phoneTunnelOn = false

    /// Keep mirroring notifications when the phone is out of Bluetooth range, by
    /// holding a tunnel open. Costs battery and data, so it's off by default.
    @Published var notificationsAnywhere: Bool {
        didSet {
            UserDefaults.standard.set(notificationsAnywhere, forKey: "notificationsAnywhere")
            updateNotificationBridge()
        }
    }

    /// Show the phone's notifications on the Mac (works with USB debugging off).
    @Published var mirrorNotifications: Bool {
        didSet {
            UserDefaults.standard.set(mirrorNotifications, forKey: "mirrorNotifications")
            updateNotificationBridge()
        }
    }

    /// Start Bridge when you log in, so the menu-bar icon is always there.
    /// Backed by the system's login-items service, not a copied file.
    @Published var launchAtLogin: Bool {
        didSet {
            guard launchAtLogin != oldValue else { return }
            do {
                if launchAtLogin { try SMAppService.mainApp.register() }
                else { try SMAppService.mainApp.unregister() }
            } catch {
                appendLog("Couldn't change the login item: \(error.localizedDescription)")
                launchAtLogin = oldValue
            }
        }
    }

    /// "Keep ready after disconnect". Both devices can change it, so each keeps
    /// the time of its own last change and the newer one wins; neither can
    /// silently overwrite the other.
    @Published var keepReady: Bool {
        didSet {
            guard keepReady != oldValue else { return }
            UserDefaults.standard.set(keepReady, forKey: "keepReady")
            if !keepReady && backgroundClipboard {
                backgroundClipboard = false   // can't run without a live daemon
            }
            if !adoptingFromPhone {
                keepReadyChangedAt = Date().timeIntervalSince1970 * 1000
            }
            pushMode()
        }
    }

    /// Milliseconds since 1970, matching what the phone sends.
    var keepReadyChangedAt: Double {
        get { UserDefaults.standard.double(forKey: "keepReadyChangedAt") }
        set { UserDefaults.standard.set(newValue, forKey: "keepReadyChangedAt") }
    }

    let localPort = 7555
    var serial: String { "127.0.0.1:\(localPort)" }

    private var tunnel: Process?
    private var mirror: Process?
    private var session: Session?
    private var sessionWindow: SessionWindow?
    private var userStopped = false

    private init() {
        let defaults = UserDefaults.standard
        ticket = defaults.string(forKey: "ticket") ?? ""
        bitrateMbps = defaults.object(forKey: "bitrateMbps") as? Int ?? 4
        maxSize = defaults.object(forKey: "maxSize") as? Int ?? 1280
        turnScreenOff = defaults.bool(forKey: "turnScreenOff")
        keepReady = defaults.bool(forKey: "keepReady")
        syncClipboard = defaults.object(forKey: "syncClipboard") as? Bool ?? true
        backgroundClipboard = defaults.bool(forKey: "backgroundClipboard")
        mutePhone = defaults.bool(forKey: "mutePhone")
        launchAtLogin = SMAppService.mainApp.status == .enabled
        mirrorNotifications = defaults.bool(forKey: "mirrorNotifications")
        useBluetooth = defaults.object(forKey: "useBluetooth") as? Bool ?? true
        notificationsAnywhere = defaults.bool(forKey: "notificationsAnywhere")
    }

    // MARK: - State helpers

    var isBusy: Bool {
        if case .working = phase { return true }
        return false
    }

    var isConnected: Bool { phase == .connected }

    var isFailed: Bool {
        if case .failed = phase { return true }
        return false
    }

    func appendLog(_ text: String, source: String? = nil) {
        for line in text.split(whereSeparator: \.isNewline) where !line.isEmpty {
            log.append(source.map { "[\($0)] \(line)" } ?? String(line))
        }
        if log.count > 400 {
            log.removeFirst(log.count - 400)
        }
    }

    private func fail(_ message: String) {
        phase = .failed(message)
        appendLog("Error: \(message)")
    }

    /// Runs blocking work (like waiting for adb) off the main thread.
    nonisolated private func background<T>(_ work: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: work())
            }
        }
    }

    private func adbPath() -> String? {
        guard let adb = Shell.find("adb") else {
            fail("adb not found. Install it with \"brew install --cask android-platform-tools\", or set ANDROID_HOME to your SDK.")
            return nil
        }
        return adb
    }

    static func extractTicket(from text: String) -> String? {
        guard let range = text.range(of: "endpoint[a-z0-9]+", options: .regularExpression) else {
            return nil
        }
        return String(text[range])
    }

    // MARK: - Set up over USB

    /// Plug in the phone, click once: starts the phone app, reads its ticket,
    /// and switches adb to network mode (`adb tcpip 5555`).
    func setUpOverUSB() {
        guard !isBusy, !isConnected, let adb = adbPath() else { return }
        notice = nil
        phase = .working("Looking for your phone on USB...")
        let activity = "\(Self.phonePackage)/.MainActivity"
        let startAction = "\(Self.phonePackage).START"
        let ticketURI = Self.ticketURI

        Task {
            // The phone may be sitting in Bluetooth-only mode with its tunnel off.
            // Ask it to start one rather than failing with "couldn't reach phone".
            if bluetoothLinked && !phoneTunnelOn {
                phase = .working("Waking the phone's tunnel…")
                if await !wakePhoneTunnel() {
                    fail("The phone didn't start its tunnel. Turn it on from the phone's screen.")
                    return
                }
                try? await Task.sleep(nanoseconds: 2_000_000_000)   // let it reach a relay
            }
            _ = await background { Shell.run(adb, ["start-server"]) }

            let state = await background { Shell.run(adb, ["-d", "get-state"], timeout: 10) }
            guard state.ok, state.output.hasSuffix("device") else {
                appendLog(state.output, source: "adb")
                fail("No phone found on USB. Plug it in, unlock it, and accept the USB debugging prompt.")
                return
            }

            phase = .working("Starting Bridge on your phone...")
            let launch = await background {
                Shell.run(adb, ["-d", "shell", "am", "start", "-n", activity, "-a", startAction], timeout: 10)
            }
            appendLog(launch.output, source: "adb")
            if launch.output.contains("does not exist") {
                fail("The Bridge app isn't installed on the phone yet.")
                return
            }

            phase = .working("Waiting for the phone's ticket...")
            var found: String?
            for _ in 0..<25 {
                let query = await background {
                    Shell.run(adb, ["-d", "shell", "content", "query", "--uri", ticketURI], timeout: 10)
                }
                if query.output.contains("ready=1"), let t = Self.extractTicket(from: query.output) {
                    found = t
                    break
                }
                if query.output.contains("Unknown authority") || query.output.contains("Could not find provider") {
                    fail("The Bridge app isn't installed on the phone yet.")
                    return
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            guard let newTicket = found else {
                fail("The phone didn't report a ticket. Open Bridge on the phone and check its log.")
                return
            }
            ticket = newTicket
            appendLog("Got ticket from phone.")

            // Lets the phone app switch USB debugging on/off by itself (see AdbToggle.kt).
            let package = BridgeController.phonePackage
            let grant = await background {
                Shell.run(adb, ["-d", "shell", "pm", "grant", package,
                                "android.permission.WRITE_SECURE_SETTINGS"], timeout: 15)
            }
            if !grant.ok { appendLog(grant.output, source: "adb") }

            // With "keep ready" on, start the phone's helper over the cable: the
            // one way to do it without Wi-Fi, which is what makes "plug in and
            // set up" the answer when the helper has died on cellular (a
            // reboot, an app update). With it off, the phone is meant to be
            // locked down between sessions, so leaving a helper running here
            // would quietly break that rule; the helper starts at Mirror time.
            if keepReady {
                phase = .working("Starting the phone's helper over USB…")
                let spawn = await background {
                    Shell.run(adb, ["-d", "shell",
                        "apk=$(pm path \(package) | head -1 | cut -d: -f2); " +
                        "(CLASSPATH=$apk exec setsid app_process / com.bonevane.bridge.Daemon " +
                        "</dev/null >/data/local/tmp/bridge-daemon.out 2>&1) & sleep 1"], timeout: 15)
                }
                if !spawn.ok { appendLog(spawn.output, source: "adb") }
                phase = .idle
                notice = "Set up, and the helper is running. You can unplug the phone and click Mirror Phone."
            } else {
                phase = .idle
                notice = "Set up. The helper starts when you mirror (that needs Wi-Fi), or turn on \"Keep ready\" to start it now over the cable."
            }
        }
    }

    // MARK: - Connect

    /// A `dumbpipe connect-tcp` process for the current ticket. Before making
    /// one, any dumbpipe left holding the local port is killed: if Bridge is
    /// force-quit its children outlive it, and the next tunnel then fails with
    /// "Address already in use" until someone finds the orphan by hand.
    private func makeTunnelProcess(dumbpipe: String) -> Process {
        for pid in Self.reapStaleTunnel(port: localPort) {
            appendLog("Stopped a leftover tunnel (pid \(pid)) that was holding port \(localPort).", source: "tunnel")
        }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: dumbpipe)
        p.arguments = ["connect-tcp", "--addr", serial, ticket.trimmingCharacters(in: .whitespacesAndNewlines)]
        p.environment = Shell.environment
        streamOutput(of: p, source: "tunnel")
        return p
    }

    /// Kills any dumbpipe listening on `port` and returns their pids.
    nonisolated static func reapStaleTunnel(port: Int) -> [Int32] {
        let listeners = Shell.run("/usr/sbin/lsof", ["-tiTCP:\(port)", "-sTCP:LISTEN"], timeout: 5).output
        var killed: [Int32] = []
        for pid in listeners.split(whereSeparator: \.isNewline).compactMap({ Int32($0.trimmingCharacters(in: .whitespaces)) }) {
            let command = Shell.run("/bin/ps", ["-o", "comm=", "-p", "\(pid)"], timeout: 5).output
            guard command.contains("dumbpipe") else { continue }   // never kill someone else's server
            kill(pid, SIGTERM)
            killed.append(pid)
        }
        return killed
    }

    func connect() {
        guard !isBusy, !isConnected else { return }

        let currentTicket = ticket.trimmingCharacters(in: .whitespacesAndNewlines)
        guard currentTicket.hasPrefix("endpoint") else {
            fail("No ticket yet. Use \"Set up over USB\" or paste the ticket from the phone app.")
            return
        }
        guard let adb = adbPath() else { return }
        guard let dumbpipe = Shell.find("dumbpipe") else {
            fail("dumbpipe not found. Install it with: brew install dumbpipe")
            return
        }

        userStopped = false
        notice = nil
        clipboardBridge.stop()   // the session will own the clipboard
        phase = .working("Opening tunnel...")
        let serial = self.serial
        let port = localPort

        Task {
            // The phone may be sitting in Bluetooth-only mode with its tunnel off.
            // Ask it to start one rather than failing with "couldn't reach phone".
            if bluetoothLinked && !phoneTunnelOn {
                phase = .working("Waking the phone's tunnel…")
                if await !wakePhoneTunnel() {
                    fail("The phone didn't start its tunnel. Turn it on from the phone's screen.")
                    return
                }
                try? await Task.sleep(nanoseconds: 2_000_000_000)   // let it reach a relay
            }
            _ = await background { Shell.run(adb, ["start-server"]) }
            _ = await background { Shell.run(adb, ["disconnect", serial], timeout: 5) }

            // Step 1: the tunnel (what terminal window 1 did).
            let tunnelProcess = makeTunnelProcess(dumbpipe: dumbpipe)
            tunnelProcess.terminationHandler = { process in
                Task { @MainActor in BridgeController.shared.processEnded(process) }
            }
            do {
                try tunnelProcess.run()
            } catch {
                fail("Couldn't start dumbpipe: \(error.localizedDescription)")
                return
            }
            tunnel = tunnelProcess

            // Step 2: tell the phone to switch USB debugging on for this session.
            // Retried because the tunnel needs a moment to find the phone.
            phase = .working("Waking the phone...")
            var reply: String?
            for _ in 0..<15 {
                if userStopped { return }
                reply = await background { Control.send("START", port: port) }
                if reply != nil { break }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            guard let startReply = reply else {
                stopProcesses()
                fail("The phone didn't answer. Is the tunnel on in the phone app?")
                return
            }
            appendLog(startReply, source: "phone")
            guard startReply.hasPrefix("OK") else {
                stopProcesses()
                fail("Phone: \(startReply)")
                return
            }

            // Step 3: open the video and control streams and show the window.
            // No adb involved: the phone's daemon runs scrcpy's server for us.
            phase = .working("Starting mirroring...")
            let window = SessionWindow()
            let session = Session(port: port, player: window.player)
            window.session = session
            session.onLog = { [weak self] line in self?.appendLog(line, source: "video") }
            session.onSize = { [weak window] w, h in window?.apply(videoWidth: w, videoHeight: h) }
            session.onClipboard = { [weak self] text in self?.phoneClipboardChanged(text) }
            session.onEnd = { [weak self] message in
                guard let self = self, !self.userStopped else { return }
                self.appendLog("Video stream ended: \(message ?? "")")
                self.disconnect()
            }
            window.onClose = { [weak self] in
                guard let self = self, !self.userStopped else { return }
                self.appendLog("Mirroring window closed.")
                self.disconnect()
            }
            var options = "max_size=\(maxSize) video_bit_rate=\(bitrateMbps)000000"
            if turnScreenOff { options += " power_off_on_close=false" }
            // "playback" capture takes the audio away from the speaker (Android 13+);
            // audio_dup gives it back, i.e. the phone keeps playing too.
            options += mutePhone ? " audio_source=playback" : " audio_source=playback audio_dup=true"
            let finalOptions = options
            let startError: String? = await background {
                do { try session.start(options: finalOptions); return nil } catch { return error.localizedDescription }
            }
            if let startError = startError {
                stopProcesses()
                fail("Couldn't start mirroring: \(startError)")
                return
            }
            if turnScreenOff { session.send(ScrcpyProtocol.displayPower(on: false)) }
            self.session = session
            self.sessionWindow = window
            startClipboardWatch()
            startSessionWatchdog()
            if bluetoothLinked { bluetoothLink.requestStatus() } else { refreshPhoneStatus() }
            // Show in the Dock and ⌘-Tab while the phone window is open.
            NSApp.setActivationPolicy(.regular)
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            phase = .connected
        }
    }

    // MARK: - Disconnect

    // MARK: - Session liveness

    private var sessionWatchdog: Timer?
    private var missedPings = 0

    /// Video alone can't tell us the phone is gone: a still screen legitimately
    /// sends nothing for a long time, and dumbpipe keeps the local socket open
    /// even after the far end vanishes. So ask the phone something small every
    /// so often, and give up after two silences.
    private func startSessionWatchdog() {
        stopSessionWatchdog()
        missedPings = 0
        let port = localPort
        sessionWatchdog = Timer.scheduledTimer(withTimeInterval: 15, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self = self, self.isConnected else { return }
                let reply = await self.background { Control.send("STATUS", port: port, timeout: 6) }
                if reply == nil {
                    self.missedPings += 1
                    if self.missedPings >= 2 {
                        self.appendLog("The phone stopped answering; ending the session.")
                        self.disconnect()
                    }
                } else {
                    self.missedPings = 0
                }
            }
        }
    }

    private func stopSessionWatchdog() {
        sessionWatchdog?.invalidate()
        sessionWatchdog = nil
    }

    // MARK: - Clipboard (both directions, while a session is open)

    private lazy var clipboardBridge = ClipboardBridge(
        onPhoneText: { [weak self] text in self?.phoneClipboardChanged(text) },
        macText: { NSPasteboard.general.string(forType: .string) },
        markSynced: { [weak self] text in self?.lastSyncedText = text })

    /// Asks the phone (over Bluetooth) to start its tunnel, and waits for it to
    /// say it has. Returns false if it never does.
    private func wakePhoneTunnel() async -> Bool {
        guard bluetoothLinked, !phoneTunnelOn else { return phoneTunnelOn }
        appendLog("Asking the phone to start its tunnel…", source: "bluetooth")
        bluetoothLink.setPhoneTunnel(true)
        for _ in 0..<30 {
            try? await Task.sleep(nanoseconds: 500_000_000)
            if phoneTunnelOn { return true }
        }
        return phoneTunnelOn
    }

    /// Settles "keep ready" between the two devices: the side that changed it
    /// most recently wins, and the stale side is corrected rather than obeyed.
    func reconcileKeepReady(phoneValue: Bool, phoneChangedAt: Double) {
        if phoneChangedAt >= keepReadyChangedAt {
            if phoneValue != keepReady {
                adoptingFromPhone = true
                keepReady = phoneValue
                adoptingFromPhone = false
                appendLog("Took \"keep ready\" = \(phoneValue) from the phone (changed there more recently).")
            }
            keepReadyChangedAt = phoneChangedAt
        } else if phoneValue != keepReady {
            appendLog("Sending \"keep ready\" = \(keepReady) to the phone (changed here more recently).")
            pushKeepReady()
        }
    }

    private func pushKeepReady() {
        bluetoothLink.setKeepReady(keepReady, changedAt: keepReadyChangedAt)
    }

    /// Menu action: look for the phone again, and re-read what it says it can do.
    func refreshBluetooth() {
        guard useBluetooth else {
            notice = "Bluetooth is switched off in Settings."
            return
        }
        if bluetoothLinked {
            bluetoothLink.requestStatus()   // a resync without dropping the link
        } else {
            bluetoothLink.rescan()
        }
        refreshPhoneStatus()
    }

    /// Menu action: switch the phone's tunnel on or off from here.
    func togglePhoneTunnel() {
        guard bluetoothLinked else {
            notice = "The phone isn't nearby, so it can't be reached over Bluetooth."
            return
        }
        let turningOff = phoneTunnelOn
        if turningOff && (isConnected || isBusy) {
            // Mirroring can't survive without the tunnel, so close it cleanly
            // rather than leaving a window reading a socket that never answers.
            notice = "Ending the session first: mirroring needs the tunnel."
            disconnect()
        }
        bluetoothLink.setPhoneTunnel(!turningOff)
    }

    // MARK: - What works right now

    enum Capability {
        case working(String)        // green: works, with a short note
        case limited(String)        // amber: works, but with a caveat
        case off(String)            // grey: doesn't work, and why

        var symbol: String {
            switch self {
            case .working: return "checkmark.circle.fill"
            case .limited: return "exclamationmark.circle.fill"
            case .off: return "circle.dotted"
            }
        }
        var detail: String {
            switch self {
            case .working(let s), .limited(let s), .off(let s): return s
            }
        }
    }

    /// Mirroring needs the phone's tunnel: Bluetooth can't carry video.
    var screenCapability: Capability {
        if isConnected { return .working("Mirroring now") }
        if ticket.trimmingCharacters(in: .whitespaces).isEmpty { return .off("Not paired yet") }
        if bluetoothLinked && !phoneTunnelOn { return .off("Turn the tunnel on, on the phone") }
        return .working("Ready to connect")
    }

    var notificationCapability: Capability {
        if !mirrorNotifications { return .off("Switched off") }
        if bluetoothLinked { return .working("Over Bluetooth") }
        if notificationsAnywhere { return .limited("Phone is far: using the tunnel") }
        return .off("Phone out of Bluetooth range")
    }

    var clipboardCapability: Capability {
        if !syncClipboard { return .off("Switched off") }
        if isConnected { return .working("Both ways, in this session") }
        if bluetoothLinked {
            return phoneDaemonAlive
                ? .working("Both ways, over Bluetooth")
                : .limited("Mac to phone only — turn on \"Keep the phone ready\" for both")
        }
        return .off("Phone out of Bluetooth range")
    }

    // MARK: - Settings sync with the phone
    //
    // Only one setting lives on both sides: "keep ready". The Mac pushes it
    // whenever it changes (if the phone is reachable without dialling out), and
    // pulls the phone's view when the menu opens, so the two can't drift apart
    // if it was changed on the phone's own screen.

    /// Sends the current mode to the phone, but only over a tunnel that already
    /// exists: changing a switch should never start dialling the phone.
    var adoptingFromPhone = false

    private func pushMode() {
        guard !adoptingFromPhone else { return }
        // Bluetooth first: the tunnel is off most of the time now, and this
        // setting used to sit unsent because of that.
        if bluetoothLinked {
            pushKeepReady()
            return
        }
        guard TCPStream.portOpen(localPort) else { return }
        let mode = keepReady ? "MODE keep" : "MODE lock"
        let port = localPort
        Task { _ = await background { Control.send(mode, port: port, timeout: 8) } }
    }

    /// Pulls `keep=` and `paused=` from the phone and reflects them in the menu.
    func refreshPhoneStatus() {
        guard TCPStream.portOpen(localPort) else { return }
        let port = localPort
        Task {
            guard let reply = await background { Control.send("STATUS", port: port, timeout: 8) },
                  reply.hasPrefix("OK") else { return }
            var phoneKeep: Bool?
            var phoneKeepAt: Double = 0
            var phonePaused = false
            for field in reply.split(separator: " ") {
                if field.hasPrefix("keep=") { phoneKeep = field.hasSuffix("true") }
                if field.hasPrefix("keepAt=") { phoneKeepAt = Double(field.dropFirst("keepAt=".count)) ?? 0 }
                if field.hasPrefix("paused=") { phonePaused = field.hasSuffix("true") }
            }
            if let phoneKeep = phoneKeep {
                reconcileKeepReady(phoneValue: phoneKeep, phoneChangedAt: phoneKeepAt)
            }
            phonePausedForBanking = phonePaused
        }
    }

    @Published var phonePausedForBanking = false

    /// Watches which twin apps are open and tells the phone over Bluetooth.
    private lazy var twins = MacTwins { [weak self] packages in
        self?.bluetoothLink.reportOpenTwins(packages)
    }

    private lazy var bluetoothLink = BluetoothLink(
        log: { [weak self] line in Task { @MainActor in self?.appendLog(line, source: "bluetooth") } },
        onNotification: { [weak self] line in Task { @MainActor in self?.showPhoneNotification(line) } },
        onClipboard: { [weak self] text in Task { @MainActor in self?.phoneClipboardChanged(text) } })

    func updateBluetooth() {
        guard useBluetooth else {
            bluetoothLink.stop()
            bluetoothLinked = false
            return
        }
        bluetoothLink.onStateChange = { [weak self] state in
            Task { @MainActor in
                guard let self = self else { return }
                self.bluetoothState = state
                // A fresh link: the phone has no idea what's open here yet.
                if state == .linked { self.twins.report(force: true) }
            }
        }
        bluetoothLink.onStatus = { [weak self] fields in
            Task { @MainActor in
                guard let self = self else { return }
                let wasOn = self.phoneTunnelOn
                let daemon = fields["daemon"] == "1"
                let tunnel = fields["tunnel"] == "1"
                self.phoneDaemonAlive = daemon
                self.phoneTunnelOn = tunnel
                if let paused = fields["paused"] { self.phonePausedForBanking = paused == "1" }
                if let keep = fields["keep"] {
                    self.reconcileKeepReady(phoneValue: keep == "1",
                                            phoneChangedAt: Double(fields["keepAt"] ?? "0") ?? 0)
                }
                // Mirroring rides the tunnel, so if the phone drops it the
                // session is already dead: the video socket would otherwise just
                // stall, leaving a frozen window until a read finally times out.
                if wasOn && !tunnel && self.isConnected {
                    self.appendLog("The phone turned its tunnel off; ending the session.")
                    self.disconnect()
                }
            }
        }
        bluetoothLink.onLinkChange = { [weak self] linked in
            Task { @MainActor in
                guard let self = self else { return }
                self.bluetoothLinked = linked
                if !linked { self.phoneDaemonAlive = false; self.phoneTunnelOn = false }
                // Bluetooth covers both while it's in range, so the tunnel-based
                // helpers should stand down, and the clipboard watcher should
                // start (or stop) with the link.
                self.updateNotificationBridge()
                self.updateBackgroundClipboard()
                if linked { self.startClipboardWatch() } else if !self.isConnected { self.stopClipboardWatch() }
            }
        }
        bluetoothLink.start()
    }

    /// One notification from the phone: "app\ttitle\ttext".
    func showPhoneNotification(_ line: String) {
        let parts = line.components(separatedBy: "\t")
        guard parts.count >= 3, mirrorNotifications else { return }
        NotificationBridge.post(app: parts[0], title: parts[1], body: parts[2])
    }

    private lazy var notificationBridge = NotificationBridge(
        log: { [weak self] line in Task { @MainActor in self?.appendLog(line, source: "phone") } })

    func updateNotificationBridge() {
        let t = ticket.trimmingCharacters(in: .whitespacesAndNewlines)
        // Bluetooth is far cheaper, so only fall back to the tunnel when the
        // phone is out of range and the user asked for notifications anywhere.
        if mirrorNotifications, !bluetoothLinked, notificationsAnywhere, t.hasPrefix("endpoint") {
            notificationBridge.start(ticket: t, port: localPort)
        } else {
            notificationBridge.stop()
        }
    }

    /// The background bridge runs only when enabled AND no window is mirroring.
    func updateBackgroundClipboard() {
        let t = ticket.trimmingCharacters(in: .whitespacesAndNewlines)
        if backgroundClipboard, !isConnected, t.hasPrefix("endpoint") {
            clipboardBridge.start(ticket: t, port: localPort)
        } else {
            clipboardBridge.stop()
        }
    }

    private var clipboardTimer: Timer?
    private var lastChangeCount = 0
    private var lastSyncedText: String?   // to break the Mac<->phone echo loop

    /// Watches the Mac clipboard and pushes changes to the phone over whichever
    /// transport is available: the session's control socket while mirroring,
    /// otherwise Bluetooth. Runs whenever either of those exists.
    func startClipboardWatch() {
        stopClipboardWatch()
        guard syncClipboard, isConnected || bluetoothLinked else { return }
        lastChangeCount = NSPasteboard.general.changeCount
        clipboardTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            let pb = NSPasteboard.general
            guard pb.changeCount != self.lastChangeCount else { return }
            self.lastChangeCount = pb.changeCount
            guard let text = pb.string(forType: .string), text != self.lastSyncedText else { return }
            self.lastSyncedText = text
            if let session = self.session {
                session.send(ScrcpyProtocol.clipboard(text))
            } else if self.bluetoothLinked {
                self.bluetoothLink.sendClipboard(text)
            }
        }
    }

    private func stopClipboardWatch() {
        clipboardTimer?.invalidate()
        clipboardTimer = nil
    }

    /// The phone copied something; put it on the Mac clipboard (without echoing back).
    private func phoneClipboardChanged(_ text: String) {
        guard syncClipboard, text != lastSyncedText else { return }
        lastSyncedText = text
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString(text, forType: .string)
        lastChangeCount = pb.changeCount
    }

    func disconnect() {
        guard !disconnecting else { return }
        disconnecting = true
        userStopped = true
        stopSessionWatchdog()
        session?.stop()

        let port = localPort
        let tunnelAlive = tunnel?.isRunning == true
        let viaBluetooth = bluetoothLinked
        let currentTicket = ticket.trimmingCharacters(in: .whitespacesAndNewlines)
        let dumbpipe = Shell.find("dumbpipe")
        phase = .working("Ending the session…")

        Task {
            // Bluetooth first. When mirroring ends because the tunnel dropped,
            // talking to the phone *through* that tunnel is waiting on a corpse,
            // which is what used to leave this stuck for minutes.
            if viaBluetooth {
                bluetoothLink.endSession()
                appendLog("Told the phone over Bluetooth.", source: "phone")
            } else {
                var temp: Process?
                if !tunnelAlive, let dumbpipe = dumbpipe, currentTicket.hasPrefix("endpoint") {
                    let p = makeTunnelProcess(dumbpipe: dumbpipe)
                    try? p.run()
                    temp = p
                }
                // A short, bounded attempt: three tries, five seconds each.
                var reply: String?
                for _ in 0..<3 {
                    reply = await background { Control.send("STOP", port: port, timeout: 5) }
                    if reply != nil { break }
                }
                temp?.terminate()
                appendLog(reply ?? "Couldn't reach the phone to turn USB debugging off. Use the phone's \"USB debugging off\" button.",
                          source: "phone")
            }

            stopProcesses()
            phase = .idle
            disconnecting = false
            if syncClipboard && !keepReady {
                // Locking down kills the helper that reads the phone's clipboard,
                // so copying *on the phone* stops reaching the Mac by itself.
                notice = "Phone locked down. Copying on the phone now needs its \"Send clipboard\" tile; turn on \"Keep the phone ready\" to keep it automatic."
            }
            self.startClipboardWatch()         // Bluetooth may still be carrying it
            self.updateBackgroundClipboard()   // resume background sync if enabled
        }
    }

    private var disconnecting = false

    /// Sends one control line to the phone, opening a temporary tunnel if needed.
    func sendCommand(_ command: String, label: String) {
        guard !isBusy else { return }
        let port = localPort
        if tunnel?.isRunning == true {
            Task {
                let reply = await background { Control.send(command, port: port, timeout: 30) }
                appendLog(reply ?? "No reply", source: "phone")
                notice = reply
            }
            return
        }
        let currentTicket = ticket.trimmingCharacters(in: .whitespacesAndNewlines)
        guard currentTicket.hasPrefix("endpoint"), let dumbpipe = Shell.find("dumbpipe") else {
            fail("No ticket, or dumbpipe is missing.")
            return
        }
        phase = .working(label)
        Task {
            let p = makeTunnelProcess(dumbpipe: dumbpipe)
            do { try p.run() } catch { fail("Couldn't start dumbpipe"); return }
            var reply: String?
            for _ in 0..<15 {
                reply = await background { Control.send(command, port: port, timeout: 30) }
                if reply != nil { break }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
            p.terminate()
            appendLog(reply ?? "The phone didn't answer.", source: "phone")
            notice = reply ?? "The phone didn't answer."
            phase = .idle
        }
    }

    /// Called by the app delegate when quitting.
    func stopProcessesNow() {
        userStopped = true
        mirror?.terminate()
        tunnel?.terminate()
    }

    private func stopProcesses() {
        // Clear references first so processEnded() ignores these exits.
        let oldMirror = mirror
        let oldTunnel = tunnel
        mirror = nil
        tunnel = nil
        session?.stop()
        session = nil
        if let w = sessionWindow { sessionWindow = nil; w.onClose = nil; w.close() }
        NSApp.setActivationPolicy(.accessory)   // back to menu-bar only
        if let m = oldMirror, m.isRunning { m.terminate() }
        if let adb = Shell.find("adb") {
            let serial = self.serial
            DispatchQueue.global().async { _ = Shell.run(adb, ["disconnect", serial], timeout: 5) }
        }
        if let t = oldTunnel, t.isRunning { t.terminate() }
    }

    private func processEnded(_ process: Process) {
        if process === mirror {
            mirror = nil
            if !userStopped {
                appendLog("Mirroring window closed.")
                disconnect()
            }
        } else if process === tunnel {
            tunnel = nil
            if !userStopped, isConnected {
                stopProcesses()
                fail("Lost the connection to the phone.")
            }
        }
    }

    private func streamOutput(of process: Process, source: String) {
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { handle in
            let data = handle.availableData
            if data.isEmpty {
                handle.readabilityHandler = nil
                return
            }
            let text = String(decoding: data, as: UTF8.self)
            Task { @MainActor in BridgeController.shared.appendLog(text, source: source) }
        }
    }
}
