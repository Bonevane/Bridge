import AppKit
import Foundation
import UserNotifications

/// Shows the phone's notifications on the Mac.
///
/// Keeps a `NOTIF` stream open to the phone and posts each line it receives as
/// a real macOS notification. The phone side is an ordinary notification
/// listener, so this keeps working with USB debugging off.
final class NotificationBridge {
    private var thread: Thread?
    private var stream: TCPStream?
    private var ownTunnel: Process?
    private var enabled = false
    private var warnedAboutAccess = false
    private let log: (String) -> Void

    init(log: @escaping (String) -> Void) {
        self.log = log
    }

    func start(ticket: String, port: Int) {
        guard !enabled else { return }
        enabled = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { granted, error in
            if let error = error {
                self.log("Notifications: \(error.localizedDescription)")
            } else if !granted {
                self.log("macOS refused permission to show notifications.")
            }
        }
        let t = Thread { [weak self] in self?.run(ticket: ticket, port: port) }
        thread = t
        t.start()
    }

    func stop() {
        enabled = false
        stream?.closeStream(); stream = nil
        ownTunnel?.terminate(); ownTunnel = nil
    }

    private func run(ticket: String, port: Int) {
        while enabled {
            var startedOwn = false
            if !TCPStream.portOpen(port), let dumbpipe = Shell.find("dumbpipe"), ticket.hasPrefix("endpoint") {
                let p = Process()
                p.executableURL = URL(fileURLWithPath: dumbpipe)
                p.arguments = ["connect-tcp", "--addr", "127.0.0.1:\(port)", ticket]
                p.environment = Shell.environment
                p.standardOutput = FileHandle.nullDevice
                p.standardError = FileHandle.nullDevice
                try? p.run()
                ownTunnel = p; startedOwn = true
                Thread.sleep(forTimeInterval: 3)
            }
            let connected = listen(port: port)
            if startedOwn { ownTunnel?.terminate(); ownTunnel = nil }
            // Back off when the phone isn't answering, so an unreachable phone
            // doesn't mean spawning a tunnel every few seconds all day.
            if enabled { Thread.sleep(forTimeInterval: connected ? 5 : 60) }
        }
    }

    /// Returns true if it actually got a stream, so the caller knows how long to wait.
    @discardableResult
    private func listen(port: Int) -> Bool {
        guard let s = try? TCPStream(port: port, timeout: 0) else { return false }
        do {
            try s.write("NOTIF\n")
            let reply = try s.readLine()
            guard reply.hasPrefix("OK") else {
                // Usually "notification access not granted on the phone". Keep
                // retrying quietly: the user may grant it at any moment, and
                // giving up here would mean it silently never starts.
                if !warnedAboutAccess {
                    log(reply)
                    warnedAboutAccess = true
                }
                s.closeStream()
                return false
            }
        } catch { s.closeStream(); return false }
        stream = s
        warnedAboutAccess = false
        log("Mirroring phone notifications.")

        while enabled {
            guard let line = try? s.readLine() else { break }   // nil = phone gone
            if line.isEmpty { continue }                            // keepalive
            let parts = line.components(separatedBy: "\t")
            guard parts.count >= 3 else { continue }
            show(app: parts[0], title: parts[1], body: parts[2])
        }
        s.closeStream()
        stream = nil
        return true
    }

    private func show(app: String, title: String, body: String) {
        Self.post(app: app, title: title, body: body)
    }

    /// Posts one phone notification on the Mac. Shared by both transports
    /// (Bluetooth when the phone is nearby, the tunnel when it isn't).
    static func post(app: String, title: String, body: String) {
        let content = UNMutableNotificationContent()
        // The app name is the most useful thing to lead with; the phone's own
        // title goes in the subtitle so both are visible.
        content.title = app
        content.subtitle = title
        content.body = body
        content.sound = nil
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    /// macOS only shows notifications once the user has allowed them.
    static func requestPermission() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }
}
