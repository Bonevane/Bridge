import AppKit
import Foundation

/// Everything the menu does: pairing over USB, and Connect / Disconnect.
///
/// Connect runs the same three steps you did by hand:
///   1. dumbpipe connect-tcp --addr 127.0.0.1:7555 <ticket>   (window 1)
///   2. adb connect 127.0.0.1:7555                             (window 2)
///   3. scrcpy -s 127.0.0.1:7555 ...                           (window 2)
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
    }

    // MARK: - State helpers

    var isBusy: Bool {
        if case .working = phase { return true }
        return false
    }

    var isConnected: Bool { phase == .connected }

    var menuIcon: String {
        switch phase {
        case .idle: return "iphone"
        case .working: return "iphone.and.arrow.forward"
        case .connected: return "iphone.radiowaves.left.and.right"
        case .failed: return "exclamationmark.triangle"
        }
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
            fail("adb not found. Install it with: brew install --cask android-platform-tools")
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

            phase = .working("Enabling network debugging...")
            let tcpip = await background { Shell.run(adb, ["-d", "tcpip", "5555"], timeout: 15) }
            appendLog(tcpip.output, source: "adb")
            guard tcpip.output.contains("restarting") || tcpip.ok else {
                fail("adb tcpip failed: \(tcpip.output)")
                return
            }

            // Lets the phone app switch USB debugging on/off by itself (see AdbToggle.kt).
            let package = BridgeController.phonePackage
            let grant = await background {
                Shell.run(adb, ["-d", "shell", "pm", "grant", package,
                                "android.permission.WRITE_SECURE_SETTINGS"], timeout: 15)
            }
            if !grant.ok { appendLog(grant.output, source: "adb") }

            phase = .idle
            notice = "Set up. You can unplug the phone and click Connect."
        }
    }

    // MARK: - Connect

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
        phase = .working("Opening tunnel...")
        let serial = self.serial
        let port = localPort

        Task {
            _ = await background { Shell.run(adb, ["start-server"]) }
            _ = await background { Shell.run(adb, ["disconnect", serial], timeout: 5) }

            // Step 1: the tunnel (what terminal window 1 did).
            let tunnelProcess = Process()
            tunnelProcess.executableURL = URL(fileURLWithPath: dumbpipe)
            tunnelProcess.arguments = ["connect-tcp", "--addr", serial, currentTicket]
            tunnelProcess.environment = Shell.environment
            streamOutput(of: tunnelProcess, source: "tunnel")
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
            NSApp.activate(ignoringOtherApps: true)
            window.makeKeyAndOrderFront(nil)
            phase = .connected
        }
    }

    // MARK: - Disconnect

    func disconnect() {
        userStopped = true
        let hadTunnel = tunnel?.isRunning ?? false
        let port = localPort
        mirror?.terminate()
        session?.stop()
        guard hadTunnel else { stopProcesses(); phase = .idle; return }
        phase = .working("Turning USB debugging off...")
        Task {
            let reply = await background { Control.send("STOP", port: port, timeout: 20) }
            appendLog(reply ?? "No reply to STOP; USB debugging may still be on.", source: "phone")
            stopProcesses()
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
