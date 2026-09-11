import AppKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        // Menu-bar only: no Dock icon.
        NSApp.setActivationPolicy(.accessory)
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
    }
}
