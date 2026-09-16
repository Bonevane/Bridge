import AppKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Menu-bar only: no Dock icon.
        NSApp.setActivationPolicy(.accessory)
        _ = sigterm
        statusItem = StatusItemController(bridge: BridgeController.shared)
        // Resume the background helpers the user had switched on.
        NotificationBridge.requestPermission()
        BridgeController.shared.updateBluetooth()
        BridgeController.shared.updateNotificationBridge()
        BridgeController.shared.updateBackgroundClipboard()

        // Settings and About are ordinary windows that need a Dock icon while
        // open. When the last one closes, go back to menu-bar only. Watching
        // the windows themselves is the only reliable hook: SwiftUI hides the
        // Settings window rather than destroying its view, so `onDisappear`
        // doesn't fire, and the About panel isn't SwiftUI at all.
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification,
                                               object: nil, queue: .main) { [weak self] note in
            let closing = note.object as? NSWindow
            // A beat later, not now: the menu that opened About closes first,
            // and checking at that instant found nothing open and hid the
            // About panel the moment it appeared.
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                self?.hideDockIconIfNothingOpen(except: closing)
            }
        }
    }

    private func hideDockIconIfNothingOpen(except closing: NSWindow?) {
        let stillOpen = NSApp.windows.contains { window in
            window !== closing && window.isVisible
                && !(window is NSPanel)                 // the menu-bar dropdown
                && window.styleMask.contains(.titled)   // not helper windows
        }
        if !stillOpen && NSApp.activationPolicy() == .regular { NSApp.setActivationPolicy(.accessory) }
    }

    func applicationWillTerminate(_ notification: Notification) {
        BridgeController.shared.stopProcessesNow()
    }

    /// A `kill`/`pkill` sends SIGTERM, which skips `applicationWillTerminate`,
    /// and the tunnel child then outlives us and keeps port 7555 for days.
    /// Route the signal through a normal quit so the children go too.
    private lazy var sigterm: DispatchSourceSignal = {
        signal(SIGTERM, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        source.setEventHandler {
            BridgeController.shared.stopProcessesNow()
            NSApp.terminate(nil)
        }
        source.resume()
        return source
    }()

    private var statusItem: StatusItemController?
}

@main
struct BridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var bridge = BridgeController.shared

    var body: some Scene {
        // The Settings *scene* (not a plain Window) is what makes macOS draw the
        // real preferences toolbar: big icons above labels, like System Settings.
        Settings {
            SettingsView(bridge: bridge)
        }
    }
}
