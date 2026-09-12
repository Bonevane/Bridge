import AppKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Menu-bar only: no Dock icon.
        NSApp.setActivationPolicy(.accessory)
        // Resume the background helpers the user had switched on.
        NotificationBridge.requestPermission()
        BridgeController.shared.updateBluetooth()
        BridgeController.shared.updateNotificationBridge()
        BridgeController.shared.updateBackgroundClipboard()
    }

    func applicationWillTerminate(_ notification: Notification) {
        BridgeController.shared.stopProcessesNow()
    }
}

@main
struct BridgeApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var bridge = BridgeController.shared

    var body: some Scene {
        MenuBarExtra {
            MenuView(bridge: bridge)
        } label: {
            Image(systemName: bridge.menuIcon)
        }
        .menuBarExtraStyle(.window)

        // A real settings window, the way a Mac app is expected to have one,
        // instead of stacking every option inside the menu.
        Window("Bridge Settings", id: "settings") {
            SettingsView(bridge: bridge)
                .onDisappear {
                    // Back to menu-bar only, unless a phone window is still open.
                    if !bridge.isConnected { NSApp.setActivationPolicy(.accessory) }
                }
        }
        .windowResizability(.contentSize)
        .defaultPosition(.center)
    }
}
