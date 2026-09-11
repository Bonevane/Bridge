import Foundation

struct CommandResult: Sendable {
    let status: Int32
    let output: String
    let timedOut: Bool

    var ok: Bool { status == 0 && !timedOut }
}

/// Helpers for finding and running command-line tools (adb, dumbpipe, scrcpy).
enum Shell {
    /// Apps launched from Finder don't get your terminal's PATH, so look in the usual places.
    static let searchPaths = [
        "/opt/homebrew/bin",
        "/usr/local/bin",
        NSHomeDirectory() + "/Library/Android/sdk/platform-tools",  // Android SDK (e.g. from Flutter)
        "/usr/bin",
        "/bin",
    ]

    static func find(_ name: String) -> String? {
        var dirs = searchPaths
        if let path = ProcessInfo.processInfo.environment["PATH"] {
            dirs += path.split(separator: ":").map(String.init)
        }
        for dir in dirs {
            let candidate = (dir as NSString).appendingPathComponent(name)
            if FileManager.default.isExecutableFile(atPath: candidate) {
                return candidate
            }
        }
        return nil
    }

    static var environment: [String: String] {
        var env = ProcessInfo.processInfo.environment
        env["PATH"] = searchPaths.joined(separator: ":") + ":" + (env["PATH"] ?? "")
        return env
    }

    /// Runs a command until it exits (or times out) and returns its output.
    ///
    /// Output goes to a temporary file rather than a pipe: `adb` may start a
    /// background server that inherits the output handle, and with a pipe we
    /// would wait forever for it to close.
    static func run(_ executable: String, _ arguments: [String], timeout: TimeInterval = 20) -> CommandResult {
        let logURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("bridge-\(UUID().uuidString).log")
        FileManager.default.createFile(atPath: logURL.path, contents: nil)
        defer { try? FileManager.default.removeItem(at: logURL) }

        guard let handle = try? FileHandle(forWritingTo: logURL) else {
            return CommandResult(status: -1, output: "Couldn't create a temporary file", timedOut: false)
        }

        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        process.environment = environment
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = handle
        process.standardError = handle

        let finished = DispatchSemaphore(value: 0)
        process.terminationHandler = { _ in finished.signal() }

        do {
            try process.run()
        } catch {
            try? handle.close()
            return CommandResult(status: -1, output: error.localizedDescription, timedOut: false)
        }

        var timedOut = false
        if finished.wait(timeout: .now() + timeout) == .timedOut {
            timedOut = true
            process.terminate()
            _ = finished.wait(timeout: .now() + 2)
        }
        try? handle.close()

        let text = (try? String(contentsOf: logURL, encoding: .utf8)) ?? ""
        let status: Int32 = (timedOut || process.isRunning) ? -1 : process.terminationStatus
        return CommandResult(
            status: status,
            output: text.trimmingCharacters(in: .whitespacesAndNewlines),
            timedOut: timedOut
        )
    }
}
