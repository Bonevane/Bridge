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
    private var audio: TCPStream?
    private var control: TCPStream?
    let audioPlayer = AudioPlayer()
    private let controlQueue = DispatchQueue(label: "bridge.control")
    private var stopped = false

    var onSize: ((Int, Int) -> Void)?
    var onEnd: ((String?) -> Void)?
    var onLog: ((String) -> Void)?

    // MARK: Adaptive bitrate
    //
    // Each frame carries the phone's capture time (PTS). If frames arrive later
    // and later relative to their PTS, the link can't carry the bitrate: bits
    // are queueing up somewhere between the encoder and us. The encoder can't
    // change bitrate live, so we restart the stream one step lower (the window
    // just gets a fresh keyframe). After a quiet stretch we step back up.
    private var baseOptions = ""
    private var targetBitrate = 0          // bits/s the user chose
    private var level = 0                  // 0 = target, 1 = half, 2 = quarter, 3 = eighth
    private var restarting = false
    private var firstPts: Int64 = 0, firstArrival: Double = 0
    private var minLag: Double = .infinity
    private var slowSince: Double? = nil
    private var levelStart: Double = 0

    init(port: Int, player: H264Player) {
        self.port = port
        self.player = player
    }

    /// Opens both streams. Throws with a readable message on failure.
    /// `options` are scrcpy server options; `video_bit_rate=` is managed here.
    func start(options: String) throws {
        baseOptions = options.split(separator: " ").filter { !$0.hasPrefix("video_bit_rate=") }.joined(separator: " ")
        if let bits = options.split(separator: " ").first(where: { $0.hasPrefix("video_bit_rate=") }) {
            targetBitrate = Int(bits.dropFirst("video_bit_rate=".count)) ?? 4_000_000
        }
        try open()
    }

    private func currentOptions() -> String {
        let bitrate = max(targetBitrate >> level, 300_000)
        var opts = "\(baseOptions) video_bit_rate=\(bitrate)"
        if level >= 3 { opts += " max_size=720" }   // keep text readable at very low rates
        return opts
    }

    private func open() throws {
        let options = currentOptions()
        let v = try TCPStream(port: port)
        try v.write("VIDEO \(options)\n")
        let reply = try v.readLine()
        guard reply.hasPrefix("OK scid=") else { throw TCPStream.StreamError.failed("phone: \(reply)") }
        let scid = String(reply.dropFirst("OK scid=".count))

        let a = try TCPStream(port: port)
        try a.write("AUDIO \(scid)\n")
        let areply = try a.readLine()
        guard areply.hasPrefix("OK") else { throw TCPStream.StreamError.failed("phone: \(areply)") }

        let c = try TCPStream(port: port)
        try c.write("CTRL \(scid)\n")
        let creply = try c.readLine()
        guard creply.hasPrefix("OK") else { throw TCPStream.StreamError.failed("phone: \(creply)") }

        video = v
        audio = a
        control = c
        firstArrival = 0; minLag = .infinity; slowSince = nil; levelStart = Date().timeIntervalSince1970
        Thread(block: { [weak self] in self?.readVideo(v) }).start()
        Thread(block: { [weak self] in self?.readAudio(a) }).start()
        Thread(block: { [weak self] in self?.readControl(c) }).start()
    }

    /// Restarts the stream one bitrate level up or down. Called from the video thread.
    private func changeLevel(to newLevel: Int, reason: String) {
        guard !restarting, !stopped, newLevel != level, (0...3).contains(newLevel) else { return }
        restarting = true
        level = newLevel
        onLog?("\(reason): bitrate → \(max(targetBitrate >> level, 300_000) / 1000) kbps")
        video?.closeStream(); audio?.closeStream(); control?.closeStream()
        Thread { [weak self] in
            guard let self = self else { return }
            Thread.sleep(forTimeInterval: 0.3)   // let the phone tear the old server down
            do { try self.open() } catch {
                DispatchQueue.main.async { self.onEnd?("restart failed: \(error.localizedDescription)") }
            }
            self.restarting = false
        }.start()
    }

    /// Per-frame lag bookkeeping. `pts` in microseconds from the phone.
    private func observe(pts: Int64) {
        let now = Date().timeIntervalSince1970
        if firstArrival == 0 { firstArrival = now; firstPts = pts; return }
        // How much later than "expected" did this frame arrive, relative to the first one?
        let lag = (now - firstArrival) - Double(pts - firstPts) / 1_000_000
        minLag = min(minLag, lag)
        let backlog = lag - minLag           // seconds of queued video, roughly
        if backlog > 0.5 {
            if slowSince == nil { slowSince = now }
            if now - slowSince! > 2, level < 3 { changeLevel(to: level + 1, reason: "Link is slow (\(Int(backlog * 1000)) ms behind)") }
        } else {
            slowSince = nil
            // Clean for 30 s at a reduced level: try one step up.
            if level > 0, now - levelStart > 30, backlog < 0.1 { changeLevel(to: level - 1, reason: "Link looks fine again") }
        }
    }

    func stop() {
        stopped = true
        video?.closeStream()
        audio?.closeStream()
        control?.closeStream()
        audioPlayer.stop()
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
                if !isConfig { observe(pts: Int64(ptsAndFlags & ((1 << 61) - 1))) }
            }
        } catch {
            if !stopped && !restarting && s === video { DispatchQueue.main.async { self.onEnd?(error.localizedDescription) } }
        }
    }

    /// Same framing as video: 4-byte codec id, then 12-byte packet headers.
    /// A codec id of 0 or 1 means the phone couldn't capture audio (0: continue
    /// without, 1: configuration error).
    private func readAudio(_ s: TCPStream) {
        do {
            let codec = try s.readExactly(4)
            let id = be32(codec, 0)
            if id == 0 || id == 1 { onLog?("Phone: no audio (code \(id))"); return }
            while !stopped {
                let header = try s.readExactly(12)
                let ptsAndFlags = be64(header, 0)
                let size = Int(be32(header, 8))
                let data = try s.readExactly(size)
                audioPlayer.handle(packet: data, isConfig: ptsAndFlags & (1 << 62) != 0)
            }
        } catch {
            // Audio is best-effort; the video stream decides when the session ends.
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
