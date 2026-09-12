import AppKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Menu-bar only: no Dock icon.
        NSApp.setActivationPolicy(.accessory)
        statusItem = StatusItemController(bridge: BridgeController.shared)
        // Resume the background helpers the user had switched on.
        NotificationBridge.requestPermission()
        BridgeController.shared.updateBluetooth()
        BridgeController.shared.updateNotificationBridge()
        BridgeController.shared.updateBackgroundClipboard()
    }

    func applicationWillTerminate(_ notification: Notification) {
        BridgeController.shared.stopProcessesNow()
    }

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
                .onDisappear {
                    // Back to menu-bar only, unless a phone window is still open.
                    if !bridge.isConnected { NSApp.setActivationPolicy(.accessory) }
                }
        }
    }
}
