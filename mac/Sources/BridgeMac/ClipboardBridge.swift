import AppKit
import Foundation

/// Keeps the clipboard synced when no mirroring window is open.
///
/// It holds its own tunnel to the phone, brings the daemon up, and opens a
/// standalone `CLIP` stream (a control-only scrcpy-server on the phone, no
/// video). Clipboard changes flow both ways over it, using the same
/// scrcpy clipboard messages a full session uses.
///
/// Only runs while there is no video session (the session owns the clipboard
/// then). Needs the phone's daemon, so it works when "keep ready" is on or a
/// session left the daemon up; with lock-down idle it simply waits.
final class ClipboardBridge {
    private let onPhoneText: (String) -> Void          // phone → Mac (shared echo guard)
    private let macText: () -> String?                 // current Mac clipboard
    private let markSynced: (String) -> Void           // update the shared guard

    private var thread: Thread?
    private var stream: TCPStream?
    private var ownTunnel: Process?
    private var enabled = false
    private var lastChangeCount = 0

    init(onPhoneText: @escaping (String) -> Void,
         macText: @escaping () -> String?,
         markSynced: @escaping (String) -> Void) {
        self.onPhoneText = onPhoneText
        self.macText = macText
        self.markSynced = markSynced
    }

    func start(ticket: String, port: Int) {
        guard !enabled else { return }
        enabled = true
        let t = Thread { [weak self] in self?.run(ticket: ticket, port: port) }
        t.stackSize = 512 * 1024
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
            // Make sure a tunnel exists (reuse the app's, else start our own).
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

            if enabled, let reply = Control.send("START", port: port, timeout: 20), reply.hasPrefix("OK") {
                openClip(port: port)   // blocks until the stream drops
            }

            if startedOwn { ownTunnel?.terminate(); ownTunnel = nil }
            if enabled { Thread.sleep(forTimeInterval: 5) }   // retry after a drop
        }
    }

    private func openClip(port: Int) {
        guard let s = try? TCPStream(port: port) else { return }
        do {
            try s.write("CLIP\n")
            guard try s.readLine().hasPrefix("OK") else { return }
        } catch { return }
        stream = s
        lastChangeCount = NSPasteboard.general.changeCount

        // Poll the Mac clipboard on a side thread; the main loop reads the phone's.
        let poller = Thread { [weak self] in
            guard let self = self else { return }
            while self.enabled, self.stream === s {
                let pb = NSPasteboard.general
                if pb.changeCount != self.lastChangeCount {
                    self.lastChangeCount = pb.changeCount
                    if let text = pb.string(forType: .string) {
                        self.markSynced(text)
                        try? s.write(ScrcpyProtocol.clipboard(text))
                    }
                }
                Thread.sleep(forTimeInterval: 0.5)
            }
        }
        poller.start()

        // Device → client messages; we only care about clipboard (type 0).
        while enabled {
            guard let type = try? s.readExactly(1).first else { break }
            switch type {
            case 0:
                guard let len = try? s.readExactly(4) else { return }
                let n = Int(len[0]) << 24 | Int(len[1]) << 16 | Int(len[2]) << 8 | Int(len[3])
                guard let data = try? s.readExactly(n) else { return }
                let text = String(decoding: data, as: UTF8.self)
                DispatchQueue.main.async { self.onPhoneText(text) }
            case 1:
                _ = try? s.readExactly(8)
            case 2:
                guard let h = try? s.readExactly(4) else { return }
                _ = try? s.readExactly(Int(h[2]) << 8 | Int(h[3]))
            default:
                return
            }
        }
        stream = nil
    }
}
