import Foundation

/// One-line commands to the phone app over the tunnel (see ControlProxy.kt).
/// Opens a TCP connection to the local dumbpipe port, sends "START\n" etc. and
/// returns the one-line reply ("OK …" / "ERR …"), or nil if nothing answered.
enum Control {
    static func send(_ command: String, port: Int, timeout: TimeInterval = 60) -> String? {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { return nil }
        defer { close(fd) }

        var tv = timeval(tv_sec: Int(timeout), tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(port).bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let connected = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard connected == 0 else { return nil }

        let secret = Keychain.get("pairSecret") ?? ""
        let payload = Array(("AUTH \(secret)\n" + command + "\n").utf8)
        guard payload.withUnsafeBufferPointer({ write(fd, $0.baseAddress, $0.count) }) == payload.count else { return nil }

        var reply = [UInt8]()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while !reply.contains(10) {  // until newline
            let n = read(fd, &buffer, buffer.count)
            if n <= 0 { break }
            reply.append(contentsOf: buffer[0..<n])
        }
        guard !reply.isEmpty else { return nil }
        return String(decoding: reply, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
