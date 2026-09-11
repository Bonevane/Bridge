import Foundation

/// One mirroring session: a VIDEO stream and a CTRL stream through the tunnel
/// (see ControlProxy.kt and Daemon.kt on the phone). Video packets go to the
/// player; input from the window goes out as scrcpy control messages.
final class Session {
    let port: Int
    let player: H264Player
    private(set) var deviceName = ""
    /// Current video size, as reported by the server's session meta.
    private(set) var videoWidth: UInt16 = 0
    private(set) var videoHeight: UInt16 = 0

    private var video: TCPStream?
    private var control: TCPStream?
    private let controlQueue = DispatchQueue(label: "bridge.control")
    private var stopped = false

    var onSize: ((Int, Int) -> Void)?
    var onEnd: ((String?) -> Void)?
    var onLog: ((String) -> Void)?

    init(port: Int, player: H264Player) {
        self.port = port
        self.player = player
    }

    /// Opens both streams. Throws with a readable message on failure.
    func start(options: String) throws {
        let v = try TCPStream(port: port)
        try v.write("VIDEO \(options)\n")
        let reply = try v.readLine()
        guard reply.hasPrefix("OK scid=") else { throw TCPStream.StreamError.failed("phone: \(reply)") }
        let scid = String(reply.dropFirst("OK scid=".count))

        let c = try TCPStream(port: port)
        try c.write("CTRL \(scid)\n")
        let creply = try c.readLine()
        guard creply.hasPrefix("OK") else { throw TCPStream.StreamError.failed("phone: \(creply)") }

        video = v
        control = c
        Thread(block: { [weak self] in self?.readVideo(v) }).start()
        Thread(block: { [weak self] in self?.readControl(c) }).start()
    }

    func stop() {
        stopped = true
        video?.closeStream()
        control?.closeStream()
    }

    func send(_ message: [UInt8]) {
        controlQueue.async { [weak self] in
            guard let c = self?.control else { return }
            try? c.write(message)
        }
    }

    // MARK: - Reading

    private func readVideo(_ s: TCPStream) {
        do {
            let name = try s.readExactly(64)
            deviceName = String(decoding: name.prefix { $0 != 0 }, as: UTF8.self)
            let codec = try s.readExactly(4)
            onLog?("Video from \(deviceName), codec \(String(decoding: codec, as: UTF8.self))")

            while !stopped {
                let header = try s.readExactly(12)
                // Session meta and frame headers share the 12-byte slot; the top bit tells them apart.
                if header[0] & 0x80 != 0 {
                    let w = Int(be32(header, 4)), h = Int(be32(header, 8))
                    videoWidth = UInt16(w); videoHeight = UInt16(h)
                    DispatchQueue.main.async { self.onSize?(w, h) }
                    continue
                }
                let ptsAndFlags = be64(header, 0)
                let size = Int(be32(header, 8))
                let isConfig = ptsAndFlags & (1 << 62) != 0
                let isKey = ptsAndFlags & (1 << 61) != 0
                let data = try s.readExactly(size)
                player.handle(packet: data, isConfig: isConfig, isKeyframe: isKey)
            }
        } catch {
            if !stopped { DispatchQueue.main.async { self.onEnd?(error.localizedDescription) } }
        }
    }

    /// Device → client messages (clipboard etc.). We don't use them yet, but the
    /// stream must be drained or the server blocks.
    private func readControl(_ s: TCPStream) {
        while !stopped {
            guard let type = try? s.readExactly(1).first else { return }
            switch type {
            case 0:  // clipboard: 4-byte length + text
                guard let len = try? s.readExactly(4), let _ = try? s.readExactly(Int(be32(len, 0))) else { return }
            case 1:  // ack clipboard: 8-byte sequence
                guard (try? s.readExactly(8)) != nil else { return }
            case 2:  // uhid output: 2-byte id, 2-byte size, data
                guard let head = try? s.readExactly(4) else { return }
                let size = Int(head[2]) << 8 | Int(head[3])
                guard (try? s.readExactly(size)) != nil else { return }
            default:
                return
            }
        }
    }

    private func be32(_ b: [UInt8], _ o: Int) -> UInt32 {
        UInt32(b[o]) << 24 | UInt32(b[o + 1]) << 16 | UInt32(b[o + 2]) << 8 | UInt32(b[o + 3])
    }

    private func be64(_ b: [UInt8], _ o: Int) -> UInt64 {
        UInt64(be32(b, o)) << 32 | UInt64(be32(b, o + 4))
    }
}
