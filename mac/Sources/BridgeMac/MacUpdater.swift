import AppKit
import CryptoKit
import Foundation
import Security

/// Keeps Bridge for Mac up to date from the project's GitHub releases.
///
/// - Checks once a day (unless switched off) and whenever asked. Nothing is
///   downloaded until someone clicks Update, nothing installed until the
///   download has been checked.
/// - Downloads resume: URLSession's resume data is kept on disk, so a dropped
///   connection, a pause, or quitting Bridge picks up where it stopped.
/// - The DMG must match the release's SHA256SUMS, and the app inside it must
///   be Bridge, newer, intact (codesign), and signed by the same certificate
///   as this copy whenever this copy has one.
/// - Installing quits Bridge cleanly (session, tunnel, Bluetooth, adb), then
///   a small script swaps the app in place, relaunches it, and puts the old
///   one back if the new one doesn't start.
@MainActor
final class MacUpdater: NSObject, ObservableObject {
    static let shared = MacUpdater()

    struct Release: Equatable {
        let version: String
        let notes: String
        let dmgURL: URL
        let dmgName: String
        let size: Int64
        let sumsURL: URL?
    }

    enum State: Equatable {
        case idle
        case checking
        case upToDate(Date)
        case available(Release)
        case downloading(Release, done: Int64, total: Int64, perSecond: Int64)
        case paused(Release, done: Int64, total: Int64, reason: String?)
        case verifying(Release)
        case ready(Release)
        case installing(Release)
        case failed(String, Release?)
    }

    @Published private(set) var state: State = .idle

    private static let api = URL(string: "https://api.github.com/repos/Bonevane/Bridge/releases/latest")!
    private let defaults = UserDefaults.standard

    var automaticChecks: Bool {
        get { defaults.object(forKey: "updateChecks") as? Bool ?? true }
        set { defaults.set(newValue, forKey: "updateChecks"); objectWillChange.send() }
    }
    var lastChecked: Date? { defaults.object(forKey: "updateCheckedAt") as? Date }

    nonisolated static var currentVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0"
    }

    private var timer: Timer?
    private var session: URLSession!
    private var task: URLSessionDownloadTask?
    private var pausing = false
    private var retries = 0

    override init() {
        super.init()
        session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
    }

    /// Starts the daily check (the first one shortly after launch), and says
    /// so if this launch is the first after an update.
    func start() {
        if let from = defaults.string(forKey: "updatedFrom") {
            defaults.removeObject(forKey: "updatedFrom")
            NotificationBridge.post(app: "Bridge", title: "Bridge updated",
                                    body: "From \(from) to \(Self.currentVersion).")
        }
        cleanUp()
        timer = Timer.scheduledTimer(withTimeInterval: 60 * 60, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.check(manual: false) }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { [weak self] in self?.check(manual: false) }
    }

    // MARK: - Checking

    var release: Release? {
        switch state {
        case .available(let r), .downloading(let r, _, _, _), .paused(let r, _, _, _), .verifying(let r),
             .ready(let r), .installing(let r): return r
        case .failed(_, let r): return r
        default: return nil
        }
    }

    func check(manual: Bool) {
        let now = Date()
        if !manual {
            guard automaticChecks else { return }
            if let last = lastChecked, now.timeIntervalSince(last) < 24 * 3600 { return }
            if let wait = defaults.object(forKey: "updateRetryAfter") as? Date, now < wait { return }
        }
        switch state {
        case .checking, .downloading, .verifying, .installing, .ready: return
        default: break
        }
        let before = state
        if manual { state = .checking }
        var request = URLRequest(url: Self.api, timeoutInterval: 15)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("Bridge-Mac/\(Self.currentVersion)", forHTTPHeaderField: "User-Agent")
        URLSession.shared.dataTask(with: request) { data, response, error in
            Task { @MainActor in self.finishCheck(data: data, response: response, error: error, manual: manual, before: before) }
        }.resume()
    }

    private func finishCheck(data: Data?, response: URLResponse?, error: Error?, manual: Bool, before: State) {
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        if code == 403 || code == 429 {
            let reset = (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "X-RateLimit-Reset")
                .flatMap(Double.init).map { Date(timeIntervalSince1970: $0) } ?? Date().addingTimeInterval(3600)
            defaults.set(reset, forKey: "updateRetryAfter")
            let minutes = max(1, Int(reset.timeIntervalSinceNow / 60))
            state = manual ? .failed("GitHub is limiting checks right now. Try again in \(minutes) min.", nil) : before
            return
        }
        guard error == nil, code == 200, let data = data,
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            if error != nil || code != 404 {
                BridgeController.shared.appendLog("Update check failed: \(error?.localizedDescription ?? "HTTP \(code)")", source: "update")
            }
            state = manual ? .failed("Couldn't reach GitHub. Check the connection and try again.", nil) : before
            return
        }
        defaults.set(Date(), forKey: "updateCheckedAt")
        let version = (json["tag_name"] as? String ?? "").replacingOccurrences(of: "v", with: "")
        let assets = json["assets"] as? [[String: Any]] ?? []
        let dmg = assets.first { ($0["name"] as? String ?? "").hasSuffix(".dmg") }
        let sums = assets.first { ($0["name"] as? String ?? "").hasPrefix("SHA256SUMS") }
        guard let dmg = dmg, let url = (dmg["browser_download_url"] as? String).flatMap(URL.init(string:)),
              Self.isNewer(version, than: Self.currentVersion) else {
            state = .upToDate(Date())
            return
        }
        let r = Release(version: version, notes: (json["body"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines),
                        dmgURL: url, dmgName: dmg["name"] as? String ?? "Bridge.dmg",
                        size: (dmg["size"] as? NSNumber)?.int64Value ?? 0,
                        sumsURL: (sums?["browser_download_url"] as? String).flatMap(URL.init(string:)))
        if FileManager.default.fileExists(atPath: dmgFile(r).path) {
            state = .paused(r, done: r.size, total: r.size, reason: nil)
        } else if let resume = try? Data(contentsOf: resumeFile(r)), !resume.isEmpty {
            state = .paused(r, done: 0, total: r.size, reason: nil)
        } else {
            state = .available(r)
        }
        if !manual, defaults.string(forKey: "updateAnnounced") != r.version {
            defaults.set(r.version, forKey: "updateAnnounced")
            NotificationBridge.post(app: "Bridge", title: "Bridge \(r.version) is available",
                                    body: "Open Bridge's settings to see what's new and update.")
        }
    }

    /// "0.3.10" is newer than "0.3.9"; unparsable parts count as 0.
    nonisolated static func isNewer(_ a: String, than b: String) -> Bool {
        let x = a.split(whereSeparator: { $0 == "." || $0 == "-" }).map { Int($0) ?? 0 }
        let y = b.split(whereSeparator: { $0 == "." || $0 == "-" }).map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : 0, q = i < y.count ? y[i] : 0
            if p != q { return p > q }
        }
        return false
    }

    // MARK: - Files

    private var folder: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("Bridge/Updates", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }
    private func dmgFile(_ r: Release) -> URL { folder.appendingPathComponent("Bridge-\(r.version).dmg") }
    private func resumeFile(_ r: Release) -> URL { folder.appendingPathComponent("Bridge-\(r.version).resume") }
    private var stagedApp: URL { folder.appendingPathComponent("staged/Bridge.app") }

    /// Old downloads, and a staged app from an update that already happened.
    private func cleanUp() {
        let files = (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)) ?? []
        for f in files where f.lastPathComponent.hasPrefix("Bridge-") {
            let v = f.deletingPathExtension().lastPathComponent.replacingOccurrences(of: "Bridge-", with: "")
            if !Self.isNewer(v, than: Self.currentVersion) { try? FileManager.default.removeItem(at: f) }
        }
        try? FileManager.default.removeItem(at: folder.appendingPathComponent("staged"))
    }

    // MARK: - Downloading

    /// Update: download (or resume), then check. Installing is a second click,
    /// because it quits Bridge.
    func download() {
        guard let r = release else { check(manual: true); return }
        switch state {
        case .downloading, .verifying, .installing: return
        case .ready: return
        default: break
        }
        if FileManager.default.fileExists(atPath: dmgFile(r).path) { verify(r); return }
        let free = (try? folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?
            .volumeAvailableCapacityForImportantUsage ?? Int64.max
        if free < r.size * 3 + 50_000_000 {
            state = .failed("Not enough free disk space: the update needs about \((r.size * 3) >> 20) MB while installing.", r)
            return
        }
        pausing = false
        lastDone = -1; rate = 0
        if let resume = try? Data(contentsOf: resumeFile(r)), !resume.isEmpty {
            task = session.downloadTask(withResumeData: resume)
        } else {
            var request = URLRequest(url: r.dmgURL)
            request.setValue("Bridge-Mac/\(Self.currentVersion)", forHTTPHeaderField: "User-Agent")
            task = session.downloadTask(with: request)
        }
        state = .downloading(r, done: 0, total: r.size, perSecond: 0)
        task?.resume()
    }

    func pause() {
        guard case .downloading(let r, let done, let total, _) = state else { return }
        pausing = true
        task?.cancel { [weak self] data in
            Task { @MainActor in
                guard let self = self else { return }
                if let data = data { try? data.write(to: self.resumeFile(r)) }
                self.state = .paused(r, done: done, total: total, reason: nil)
            }
        }
    }

    func cancel() {
        guard let r = release else { return }
        pausing = true
        task?.cancel()
        task = nil
        try? FileManager.default.removeItem(at: resumeFile(r))
        try? FileManager.default.removeItem(at: dmgFile(r))
        state = .available(r)
    }

    /// Download speed over the last second or so. [lastDone] starts at -1 so
    /// a resumed download doesn't count the bytes it already had as speed.
    fileprivate func progress(done: Int64, total: Int64) {
        guard let r = release else { return }
        let now = Date()
        if lastDone < 0 {
            lastDone = done; rateStart = now
        } else if now.timeIntervalSince(rateStart) >= 1 {
            rate = Int64(Double(done - lastDone) / now.timeIntervalSince(rateStart))
            lastDone = done; rateStart = now
        }
        state = .downloading(r, done: done, total: total > 0 ? total : r.size, perSecond: rate)
    }
    private var lastDone: Int64 = -1
    private var rateStart = Date()
    private var rate: Int64 = 0

    fileprivate func finished(at location: URL) {
        guard let r = release else { return }
        let target = dmgFile(r)
        try? FileManager.default.removeItem(at: target)
        do {
            try FileManager.default.moveItem(at: location, to: target)
        } catch {
            state = .failed("Couldn't save the download: \(error.localizedDescription)", r)
            return
        }
        try? FileManager.default.removeItem(at: resumeFile(r))
        retries = 0
        verify(r)
    }

    fileprivate func failed(_ error: Error) {
        guard let r = release else { return }
        if pausing { return }   // pause/cancel handled it
        let nsError = error as NSError
        if let data = nsError.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
            try? data.write(to: resumeFile(r))
        }
        // A dropped connection retries by itself a few times, carrying on from
        // the bytes already fetched; after that it waits for a click.
        let waits: [Double] = [2, 5, 10, 20, 30]
        if retries < waits.count {
            let wait = waits[retries]
            retries += 1
            state = .paused(r, done: currentDone, total: r.size, reason: "Connection lost. Retrying…")
            DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in
                guard let self = self, case .paused = self.state else { return }
                self.download()
            }
        } else {
            retries = 0
            state = .paused(r, done: currentDone, total: r.size,
                            reason: "The connection kept dropping. Click Resume to carry on from where it stopped.")
        }
    }

    private var currentDone: Int64 {
        if case .downloading(_, let done, _, _) = state { return done }
        if case .paused(_, let done, _, _) = state { return done }
        return 0
    }

    // MARK: - Checking the download

    private func verify(_ r: Release) {
        state = .verifying(r)
        let dmg = dmgFile(r)
        let staged = stagedApp
        let sumsURL = r.sumsURL
        let mine = Bundle.main.bundleURL
        Task.detached {
            let problem = await Self.checkDownload(dmg: dmg, release: r, sumsURL: sumsURL, mine: mine, staged: staged)
            await MainActor.run {
                if let problem = problem {
                    if problem.deleteDownload { try? FileManager.default.removeItem(at: dmg) }
                    self.state = .failed(problem.message, r)
                } else {
                    self.state = .ready(r)
                }
            }
        }
    }

    private struct Problem { let message: String; let deleteDownload: Bool }

    private nonisolated static func checkDownload(dmg: URL, release r: Release, sumsURL: URL?, mine: URL, staged: URL) async -> Problem? {
        // 1. The checksum the release lists for this file.
        if let sumsURL = sumsURL {
            guard let (data, _) = try? await URLSession.shared.data(from: sumsURL),
                  let text = String(data: data, encoding: .utf8),
                  let line = text.split(separator: "\n").first(where: { $0.hasSuffix(r.dmgName) }) else {
                return Problem(message: "Couldn't fetch the release's checksum to check the download. Try again.", deleteDownload: false)
            }
            let expected = line.split(separator: " ").first.map(String.init)?.lowercased() ?? ""
            guard let handle = try? FileHandle(forReadingFrom: dmg) else {
                return Problem(message: "Couldn't read the download.", deleteDownload: true)
            }
            var hasher = SHA256()
            while let chunk = try? handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
            try? handle.close()
            let actual = hasher.finalize().map { String(format: "%02x", $0) }.joined()
            if actual != expected {
                return Problem(message: "The download was damaged on the way (its checksum doesn't match). Try again.", deleteDownload: true)
            }
        }
        // 2. Open the image and look at the app inside.
        let mount = FileManager.default.temporaryDirectory.appendingPathComponent("bridge-update-\(UUID().uuidString)")
        let attach = Shell.run("/usr/bin/hdiutil", ["attach", "-nobrowse", "-readonly", "-noautoopen",
                                                    "-mountpoint", mount.path, dmg.path], timeout: 60)
        guard attach.ok else {
            return Problem(message: "The downloaded disk image wouldn't open. Try again.", deleteDownload: true)
        }
        defer { _ = Shell.run("/usr/bin/hdiutil", ["detach", "-force", mount.path], timeout: 30) }
        let app = mount.appendingPathComponent("Bridge.app")
        guard let info = NSDictionary(contentsOf: app.appendingPathComponent("Contents/Info.plist")) else {
            return Problem(message: "The disk image doesn't contain Bridge.", deleteDownload: true)
        }
        let id = info["CFBundleIdentifier"] as? String ?? ""
        let version = info["CFBundleShortVersionString"] as? String ?? ""
        if id != Bundle.main.bundleIdentifier { return Problem(message: "The download isn't Bridge.", deleteDownload: true) }
        if !isNewer(version, than: currentVersion) {
            return Problem(message: "The download isn't newer than this copy.", deleteDownload: true)
        }
        if !Shell.run("/usr/bin/codesign", ["--verify", "--deep", "--strict", app.path], timeout: 60).ok {
            return Problem(message: "The new app's signature is broken, so it won't be installed.", deleteDownload: true)
        }
        // 3. Same signer as this copy, whenever this copy has a real certificate.
        if let ours = leafCertificate(of: mine), let theirs = leafCertificate(of: app), ours != theirs {
            return Problem(message: "The new version is signed by a different certificate than this one, so it won't be installed. Download it from GitHub yourself if you trust it.", deleteDownload: true)
        }
        // 4. Copy it out, so the image can be closed before the swap.
        try? FileManager.default.removeItem(at: staged)
        try? FileManager.default.createDirectory(at: staged.deletingLastPathComponent(), withIntermediateDirectories: true)
        if !Shell.run("/usr/bin/ditto", [app.path, staged.path], timeout: 120).ok {
            return Problem(message: "Couldn't copy the new version out of the disk image.", deleteDownload: false)
        }
        _ = Shell.run("/usr/bin/xattr", ["-dr", "com.apple.quarantine", staged.path], timeout: 30)
        return nil
    }

    /// The signing certificate's bytes, or nil for an ad-hoc signature.
    private nonisolated static func leafCertificate(of app: URL) -> Data? {
        var code: SecStaticCode?
        guard SecStaticCodeCreateWithPath(app as CFURL, [], &code) == errSecSuccess, let code = code else { return nil }
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(code, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let dict = info as? [String: Any],
              let certs = dict[kSecCodeInfoCertificates as String] as? [SecCertificate], let leaf = certs.first else { return nil }
        return SecCertificateCopyData(leaf) as Data
    }

    // MARK: - Installing

    /// Why Install can't run right now, or nil.
    var installBlocker: String? {
        let bridge = BridgeController.shared
        if bridge.isConnected || bridge.filesSession { return "Close the phone window and Phone Files first: installing restarts Bridge." }
        let parent = Bundle.main.bundleURL.deletingLastPathComponent().path
        if !FileManager.default.isWritableFile(atPath: parent) {
            return "Bridge can't write to \(parent). Open the downloaded disk image and drag Bridge there yourself."
        }
        return nil
    }

    /// Quits cleanly and lets a script swap the app and relaunch it. If the
    /// new one doesn't start, the script puts the old one back and opens it.
    func install() {
        guard case .ready(let r) = state else { return }
        if let blocker = installBlocker { state = .failed(blocker, r); return }
        state = .installing(r)
        let target = Bundle.main.bundleURL
        let backup = target.deletingLastPathComponent().appendingPathComponent(".Bridge-previous.app")
        let log = folder.appendingPathComponent("install.log")
        let script = """
        #!/bin/bash
        # Bridge's updater: waits for Bridge to quit, swaps in the new app,
        # starts it, and restores the previous one if it doesn't come up.
        exec >>"\(log.path)" 2>&1
        echo "$(date) installing \(r.version)"
        for i in $(seq 1 100); do kill -0 \(ProcessInfo.processInfo.processIdentifier) 2>/dev/null || break; sleep 0.2; done
        rm -rf "\(backup.path)"
        mv "\(target.path)" "\(backup.path)" || { echo "couldn't move the old app"; open "\(target.path)"; exit 1; }
        if ! mv "\(stagedApp.path)" "\(target.path)"; then
            echo "couldn't move the new app in; restoring"
            mv "\(backup.path)" "\(target.path)"; open "\(target.path)"; exit 1
        fi
        open "\(target.path)"
        for i in $(seq 1 60); do
            pgrep -f "\(target.path)/Contents/MacOS/Bridge" >/dev/null && { sleep 3; pgrep -f "\(target.path)/Contents/MacOS/Bridge" >/dev/null && break; }
            sleep 0.5
        done
        if pgrep -f "\(target.path)/Contents/MacOS/Bridge" >/dev/null; then
            echo "new version running"; rm -rf "\(backup.path)"
        else
            echo "new version didn't start; restoring the previous one"
            rm -rf "\(target.path)"; mv "\(backup.path)" "\(target.path)"; open "\(target.path)"
        fi
        """
        let scriptURL = folder.appendingPathComponent("install.sh")
        do {
            try script.write(to: scriptURL, atomically: true, encoding: .utf8)
        } catch {
            state = .failed("Couldn't prepare the install: \(error.localizedDescription)", r)
            return
        }
        defaults.set(Self.currentVersion, forKey: "updatedFrom")
        BridgeController.shared.appendLog("Installing \(r.version): quitting, swapping, relaunching", source: "update")
        Task {
            await BridgeController.shared.prepareForUpdate()
            let p = Process()
            p.executableURL = URL(fileURLWithPath: "/bin/bash")
            p.arguments = [scriptURL.path]
            // Its own session, so quitting Bridge doesn't take it down too.
            p.standardInput = FileHandle.nullDevice
            p.standardOutput = FileHandle.nullDevice
            p.standardError = FileHandle.nullDevice
            do {
                try p.run()
            } catch {
                self.defaults.removeObject(forKey: "updatedFrom")
                self.state = .failed("Couldn't start the installer: \(error.localizedDescription)", r)
                BridgeController.shared.resumeAfterFailedUpdate()
                return
            }
            NSApp.terminate(nil)
        }
    }

    /// Opens the downloaded disk image in Finder, for a manual install.
    func revealDownload() {
        guard let r = release else { return }
        let dmg = dmgFile(r)
        if FileManager.default.fileExists(atPath: dmg.path) { NSWorkspace.shared.open(dmg) }
        else { NSWorkspace.shared.open(URL(string: "https://github.com/Bonevane/Bridge/releases/latest")!) }
    }
}

extension MacUpdater: URLSessionDownloadDelegate {
    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didWriteData bytesWritten: Int64, totalBytesWritten: Int64,
                                totalBytesExpectedToWrite: Int64) {
        MainActor.assumeIsolated { self.progress(done: totalBytesWritten, total: totalBytesExpectedToWrite) }
    }

    nonisolated func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                                didFinishDownloadingTo location: URL) {
        // The file at `location` is deleted when this returns: move it now.
        let keep = FileManager.default.temporaryDirectory.appendingPathComponent("bridge-update-\(UUID().uuidString).dmg")
        try? FileManager.default.moveItem(at: location, to: keep)
        let status = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
        MainActor.assumeIsolated {
            if status >= 400 { self.failed(URLError(.badServerResponse)); return }
            self.finished(at: keep)
        }
    }

    nonisolated func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let error = error else { return }
        MainActor.assumeIsolated { self.failed(error) }
    }
}
