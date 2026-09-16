import Foundation

/// A blocking TCP connection to 127.0.0.1:<port> (the local end of the tunnel).
/// Plain POSIX sockets: simple, no delegate dance, and we always read from a
/// dedicated background thread anyway.
final class TCPStream {
    private var fd: Int32 = -1

    /// Connects and sends the pairing line the phone expects first on every
    /// stream, so nothing else on this Mac can use the tunnel just by finding
    /// its local port.
    convenience init(port: Int, timeout: TimeInterval = 30) throws {
        try self.init(port: port, timeout: timeout, raw: false)
        let secret = Keychain.get("pairSecret") ?? ""
        try write("AUTH \(secret)\n")
    }

    init(port: Int, timeout: TimeInterval, raw: Bool) throws {
        fd = socket(AF_INET, SOCK_STREAM, 0)
        guard fd >= 0 else { throw StreamError.failed("socket()") }
        var tv = timeval(tv_sec: Int(timeout), tv_usec: 0)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
        var one: Int32 = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, socklen_t(MemoryLayout<Int32>.size))
        setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, socklen_t(MemoryLayout<Int32>.size))

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(port).bigEndian
        addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        let rc = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard rc == 0 else { close(fd); fd = -1; throw StreamError.failed("connect: \(String(cString: strerror(errno)))") }
    }

    deinit { closeStream() }

    func closeStream() {
        if fd >= 0 { shutdown(fd, SHUT_RDWR); close(fd); fd = -1 }
    }

    /// True if something is already listening on 127.0.0.1:<port>.
    static func portOpen(_ port: Int) -> Bool {
        guard let s = try? TCPStream(port: port, timeout: 1, raw: true) else { return false }
        s.closeStream(); return true
    }

    func write(_ bytes: [UInt8]) throws {
        var offset = 0
        while offset < bytes.count {
            let n = bytes[offset...].withUnsafeBufferPointer { Darwin.write(fd, $0.baseAddress, $0.count) }
            guard n > 0 else { throw StreamError.closed }
            offset += n
        }
    }

    func write(_ text: String) throws { try write(Array(text.utf8)) }

    /// Nothing legitimate on these streams is bigger than a video frame.
    static let maxRead = 64 * 1024 * 1024

    func readExactly(_ count: Int) throws -> [UInt8] {
        guard count >= 0, count <= Self.maxRead else { throw StreamError.failed("absurd length \(count)") }
        var out = [UInt8](repeating: 0, count: count)
        var offset = 0
        while offset < count {
            let n = out[offset...].withUnsafeMutableBufferPointer { Darwin.read(fd, $0.baseAddress, $0.count) }
            guard n > 0 else { throw StreamError.closed }
            offset += n
        }
        return out
    }

    func readLine() throws -> String {
        var bytes = [UInt8]()
        while true {
            let b = try readExactly(1)[0]
            if b == 10 { break }
            bytes.append(b)
            if bytes.count > 4096 { throw StreamError.failed("line too long") }
        }
        return String(decoding: bytes, as: UTF8.self)
    }

    enum StreamError: Error, LocalizedError {
        case closed
        case failed(String)
        var errorDescription: String? {
            switch self {
            case .closed: return "connection closed"
            case .failed(let s): return s
            }
        }
    }
}
