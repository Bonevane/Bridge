import AppKit
import Combine

/// Tells the phone which apps are open on the Mac that have an Android twin,
/// so the phone can hold back their notifications (WhatsApp on the Mac shows
/// its own banner; a mirrored copy of the same message is just noise).
///
/// The table maps a Mac bundle identifier to the Android package. The phone
/// only ever receives package names, so the table lives here alone.
@MainActor
final class MacTwins {
    static let table: [String: String] = [
        "net.whatsapp.WhatsApp":            "com.whatsapp",
        "ru.keepcoder.Telegram":            "org.telegram.messenger",
        "com.tdesktop.Telegram":            "org.telegram.messenger",
        "org.whispersystems.signal-desktop":"org.thoughtcrime.securesms",
        "com.hnc.Discord":                  "com.discord",
        "com.tinyspeck.slackmacgap":        "com.Slack",
        "com.facebook.archon":              "com.facebook.orca",      // Messenger
        "com.microsoft.teams2":             "com.microsoft.teams",
        "us.zoom.xos":                      "us.zoom.videomeetings",
        "com.google.Chrome":                "com.android.chrome",
        "com.apple.mail":                   "com.google.android.gm",
        "com.spotify.client":               "com.spotify.music",
        "com.viber.osx":                    "com.viber.voip",
        "com.skype.skype":                  "com.skype.raider",
        "com.linkedin.LinkedIn":            "com.linkedin.android",
        "com.tencent.xinWeChat":            "com.tencent.mm",
    ]

    /// Android packages whose Mac twin is running right now.
    static func openPackages() -> Set<String> {
        Set(NSApp == nil ? [] : NSWorkspace.shared.runningApplications.compactMap { app in
            app.bundleIdentifier.flatMap { table[$0] }
        })
    }

    private var sink: [AnyCancellable] = []
    private var last: Set<String>?
    private let send: (Set<String>) -> Void

    /// Reports once now, then whenever one of the listed apps launches or quits.
    init(send: @escaping (Set<String>) -> Void) {
        self.send = send
        let center = NSWorkspace.shared.notificationCenter
        for name in [NSWorkspace.didLaunchApplicationNotification, NSWorkspace.didTerminateApplicationNotification] {
            center.publisher(for: name)
                .receive(on: DispatchQueue.main)
                .sink { [weak self] _ in self?.report() }
                .store(in: &sink)
        }
    }

    /// Sends the current set; `force` resends even if unchanged (on a new link).
    func report(force: Bool = false) {
        let now = Self.openPackages()
        if force || now != last {
            last = now
            send(now)
        }
    }
}
