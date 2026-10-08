import AppKit
import SwiftUI

/// Files between the Mac and the phone, through the tunnel to the phone's
/// helper (Daemon.kt): PUSH drops a file into the phone's Download folder,
/// LIST and PULL browse the phone's shared storage and fetch from it. All
/// three need a session's tunnel and helper, so they're offered while mirroring.
enum PhoneFiles {
    struct Entry: Identifiable {
        let id = UUID()
        let isDir: Bool
        let size: UInt64
        let modified: Date
        let name: String
    }

    static let storageRoot = "/storage/emulated/0"

    /// When a transfer last moved data. The mirroring session holds its
    /// bitrate while this is recent (see Session.observe).
    private static let lock = NSLock()
    private static var lastTransferBytes = Date.distantPast
    static func noteTransfer() { lock.lock(); lastTransferBytes = Date(); lock.unlock() }
    static var transferActive: Bool {
        lock.lock(); defer { lock.unlock() }
        return Date().timeIntervalSince(lastTransferBytes) < 5
    }

    /// One folder; empty path = the top. Returns the phone's resolved path too.
    static func list(port: Int, path: String) throws -> (String, [Entry]) {
        let s = try TCPStream(port: port, timeout: 20)
        defer { s.closeStream() }
        try s.write("LIST \(path)\n")
        let head = try s.readLine()
        guard head.hasPrefix("OK ") else { throw TCPStream.StreamError.failed("phone: \(head)") }
        // The rest is text up to a blank line; read it in chunks (readLine
        // goes a byte at a time, slow for a folder like DCIM/Camera).
        var body: [UInt8] = []
        while body != [10] && !(body.count >= 2 && body[body.count - 2] == 10 && body[body.count - 1] == 10) {
            let chunk = try s.readSome(64 * 1024)
            if chunk.isEmpty { break }
            body += chunk
            if body.count > 32 * 1024 * 1024 { throw TCPStream.StreamError.failed("folder listing too large") }
        }
        var entries: [Entry] = []
        for line in String(decoding: body, as: UTF8.self).split(separator: "\n", omittingEmptySubsequences: false) {
            if line.isEmpty { break }
            let f = line.split(separator: "\t", maxSplits: 3, omittingEmptySubsequences: false)
            guard f.count == 4 else { continue }
            entries.append(Entry(isDir: f[0] == "d", size: UInt64(f[1]) ?? 0,
                                 modified: Date(timeIntervalSince1970: (Double(f[2]) ?? 0) / 1000),
                                 name: String(f[3])))
        }
        return (String(head.dropFirst(3)), entries)
    }

    /// "photo.jpg" → "photo (1).jpg" …, so nothing is ever overwritten.
    static func freeURL(in dir: URL, name: String) -> URL {
        var url = dir.appendingPathComponent(name)
        let ext = (name as NSString).pathExtension
        let stem = (name as NSString).deletingPathExtension
        var i = 1
        while FileManager.default.fileExists(atPath: url.path) {
            url = dir.appendingPathComponent(ext.isEmpty ? "\(stem) (\(i))" : "\(stem) (\(i)).\(ext)")
            i += 1
        }
        return url
    }

    static var downloads: URL {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0]
    }

    /// Copies one file off the phone into ~/Downloads, in chunks (never the
    /// whole file in memory), via a .part file renamed at the end.
    static func pull(port: Int, remote: String, progress: (UInt64, UInt64) -> Void) throws -> URL {
        let s = try TCPStream(port: port, timeout: 60)
        defer { s.closeStream() }
        try s.write("PULL \(remote)\n")
        let head = try s.readLine()
        guard head.hasPrefix("OK "), let total = UInt64(head.dropFirst(3)) else {
            throw TCPStream.StreamError.failed("phone: \(head)")
        }
        let name = remote.split(separator: "/").last.map(String.init) ?? "phone-file"
        let target = freeURL(in: downloads, name: name)
        let part = target.appendingPathExtension("part")
        FileManager.default.createFile(atPath: part.path, contents: nil)
        guard let out = FileHandle(forWritingAtPath: part.path) else {
            throw TCPStream.StreamError.failed("can't write to \(downloads.path)")
        }
        var done: UInt64 = 0
        var last = Date.distantPast
        do {
            while done < total {
                let chunk = try s.readExactly(Int(min(1 << 20, total - done)))
                out.write(Data(chunk))
                done += UInt64(chunk.count)
                noteTransfer()
                if Date().timeIntervalSince(last) > 0.25 { progress(done, total); last = Date() }
            }
        } catch {
            try? out.close()
            try? FileManager.default.removeItem(at: part)
            throw error
        }
        try? out.close()
        try FileManager.default.moveItem(at: part, to: target)
        progress(total, total)
        return target
    }

    /// Sends one file to the phone's Download folder, streamed from disk.
    /// Returns the phone's reply ("saved to Download/…").
    static func push(port: Int, url: URL, progress: (UInt64, UInt64) -> Void) throws -> String {
        // One command per line: keep line breaks and other control characters out of the name.
        let name = String(url.lastPathComponent.unicodeScalars.filter { $0.value >= 32 && $0.value != 127 })
        guard let input = FileHandle(forReadingAtPath: url.path) else {
            throw TCPStream.StreamError.failed("can't read \(name)")
        }
        defer { try? input.close() }
        let total = (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? UInt64) ?? 0
        guard total > 0 else { throw TCPStream.StreamError.failed("\(name) is empty") }
        let s = try TCPStream(port: port, timeout: 120)
        defer { s.closeStream() }
        try s.write("PUSH \(total) \(name)\n")
        var done: UInt64 = 0
        var last = Date.distantPast
        while true {
            let data = input.readData(ofLength: 1 << 20)
            if data.isEmpty { break }
            try s.write([UInt8](data))
            done += UInt64(data.count)
            noteTransfer()
            if Date().timeIntervalSince(last) > 0.25 { progress(done, total); last = Date() }
        }
        let reply = try s.readLine()
        guard reply.hasPrefix("OK ") else { throw TCPStream.StreamError.failed("phone: \(reply)") }
        return String(reply.dropFirst(3))
    }

    static func humanSize(_ bytes: UInt64) -> String {
        ByteCountFormatter.string(fromByteCount: Int64(bytes), countStyle: .file)
    }
}

/// What the files window shows. `@Published` rather than `@State`: the
/// Command Line Tools can't expand the `@State` macro (see CLAUDE.md).
final class PhoneFilesModel: ObservableObject {
    @Published var path = ""
    @Published var entries: [PhoneFiles.Entry] = []
    @Published var status = ""
    @Published var loading = false
    let port: Int
    /// One transfer at a time; drops queue up behind each other.
    private let transfers = DispatchQueue(label: "bridge.files")

    init(port: Int) { self.port = port }

    var atTop: Bool { path == PhoneFiles.storageRoot }

    var shownPath: String {
        guard path.hasPrefix(PhoneFiles.storageRoot) else { return path }
        let rest = path.dropFirst(PhoneFiles.storageRoot.count)
        return ("Phone storage" + rest).replacingOccurrences(of: "/", with: " › ")
    }

    func list(_ target: String) {
        loading = true
        status = "Loading…"
        let port = self.port
        DispatchQueue.global().async {
            let result = Result { try PhoneFiles.list(port: port, path: target) }
            DispatchQueue.main.async {
                self.loading = false
                switch result {
                case .success(let (resolved, entries)):
                    self.path = resolved
                    self.entries = entries.sorted { ($0.isDir ? 0 : 1, $0.name.lowercased()) < ($1.isDir ? 0 : 1, $1.name.lowercased()) }
                    self.status = entries.isEmpty ? "Empty folder" : "\(entries.count) items"
                case .failure(let error):
                    self.status = "Couldn't list: \(error.localizedDescription)"
                }
            }
        }
    }

    func refresh() { list(path) }

    func up() {
        guard !atTop, let cut = path.range(of: "/", options: .backwards) else { return }
        list(String(path[..<cut.lowerBound]))
    }

    func open(_ entry: PhoneFiles.Entry) {
        let full = path + "/" + entry.name
        if entry.isDir { list(full); return }
        status = "Downloading \(entry.name)…"
        let port = self.port
        transfers.async {
            do {
                let url = try PhoneFiles.pull(port: port, remote: full) { done, total in
                    let pct = total > 0 ? done * 100 / total : 100
                    DispatchQueue.main.async { self.status = "Downloading \(entry.name)… \(pct)% of \(PhoneFiles.humanSize(total))" }
                }
                DispatchQueue.main.async {
                    self.status = "Saved \(url.lastPathComponent) to Downloads"
                    NotificationBridge.post(app: "Bridge", title: "File downloaded", body: url.lastPathComponent)
                }
            } catch {
                DispatchQueue.main.async { self.status = "Download failed: \(error.localizedDescription)" }
            }
        }
    }

    func send(_ urls: [URL]) {
        let port = self.port
        for url in urls {
            transfers.async {
                let name = url.lastPathComponent
                do {
                    let reply = try PhoneFiles.push(port: port, url: url) { done, total in
                        let pct = total > 0 ? done * 100 / total : 100
                        DispatchQueue.main.async { self.status = "Sending \(name)… \(pct)%" }
                    }
                    DispatchQueue.main.async {
                        self.status = "\(name): \(reply)"
                        if self.path.hasSuffix("/Download") { self.refresh() }
                    }
                } catch {
                    DispatchQueue.main.async { self.status = "\(name): \(error.localizedDescription)" }
                }
            }
        }
    }
}

struct PhoneFilesView: View {
    @ObservedObject var model: PhoneFilesModel

    var body: some View {
        VStack(spacing: 8) {
            HStack {
                Button { model.up() } label: { Label("Up", systemImage: "arrow.up") }
                    .disabled(model.atTop || model.loading)
                Text(model.shownPath).lineLimit(1).truncationMode(.head)
                    .foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading)
                Button { model.refresh() } label: { Image(systemName: "arrow.clockwise") }
                    .disabled(model.loading)
            }
            List(model.entries) { entry in
                Button { model.open(entry) } label: {
                    HStack(spacing: 10) {
                        Image(systemName: entry.isDir ? "folder.fill" : "doc")
                            .foregroundStyle(entry.isDir ? Color.accentColor : .secondary)
                            .frame(width: 18)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(entry.name).lineLimit(1)
                            Text(detail(entry)).font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer()
                        Image(systemName: entry.isDir ? "chevron.right" : "arrow.down.circle")
                            .foregroundStyle(.secondary)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(model.loading)
            }
            HStack {
                Text(model.status).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                Spacer()
                Button("Open Downloads") { NSWorkspace.shared.open(PhoneFiles.downloads) }
            }
        }
        .padding(12)
        .frame(minWidth: 380, minHeight: 320)
    }

    private func detail(_ e: PhoneFiles.Entry) -> String {
        let date = e.modified.formatted(date: .abbreviated, time: .shortened)
        return e.isDir ? date : "\(PhoneFiles.humanSize(e.size)) · \(date)"
    }
}

/// The window. Files dropped on it go to the phone's Download folder.
final class PhoneFilesWindow: NSWindow {
    let model: PhoneFilesModel
    /// Called when the window closes, so a files-only session can end with it.
    var onClose: (() -> Void)?

    override func close() {
        let handler = onClose
        super.close()
        handler?()
    }

    init(port: Int) {
        model = PhoneFilesModel(port: port)
        super.init(contentRect: NSRect(x: 0, y: 0, width: 520, height: 600),
                   styleMask: [.titled, .closable, .resizable, .miniaturizable],
                   backing: .buffered, defer: false)
        title = "Phone Files"
        isReleasedWhenClosed = false
        let host = DropHostingView(rootView: PhoneFilesView(model: model))
        host.onDrop = { [weak self] urls in self?.model.send(urls) }
        contentView = host
        center()
    }
}

/// NSHostingView that accepts files dragged from Finder.
final class DropHostingView<Content: View>: NSHostingView<Content> {
    var onDrop: (([URL]) -> Void)?

    required init(rootView: Content) {
        super.init(rootView: rootView)
        registerForDraggedTypes([.fileURL])
    }

    @MainActor required dynamic init?(coder: NSCoder) { fatalError("not used") }

    override func draggingEntered(_ sender: NSDraggingInfo) -> NSDragOperation { .copy }

    override func performDragOperation(_ sender: NSDraggingInfo) -> Bool {
        let urls = sender.draggingPasteboard.readObjects(forClasses: [NSURL.self],
                                                        options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        let files = urls.filter { !$0.hasDirectoryPath }
        guard !files.isEmpty else { return false }
        onDrop?(files)
        return true
    }
}
