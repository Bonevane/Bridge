import AppKit
import Combine
import SwiftUI

/// Bridge's menu-bar item.
///
/// SwiftUI's `MenuBarExtra` was doing this until it ran out of road: it gives no
/// way to handle a right-click, and its panel is a square-cornered window whose
/// backdrop can't be shaped. Owning an `NSStatusItem` and a borderless panel
/// costs about a hundred lines and gives both: a right-click menu, and a panel
/// whose corners are whatever the SwiftUI view draws.
@MainActor
final class StatusItemController: NSObject, NSWindowDelegate {
    private let bridge: BridgeController
    private let item: NSStatusItem
    private var panel: NSPanel?
    private var outsideClicks: Any?
    private var cancellable: AnyCancellable?

    init(bridge: BridgeController) {
        self.bridge = bridge
        self.item = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        super.init()

        if let button = item.button {
            button.image = NSImage(systemSymbolName: bridge.menuIcon, accessibilityDescription: "Bridge")
            button.image?.isTemplate = true
            button.target = self
            button.action = #selector(buttonClicked)
            // Ask for both buttons, so a right-click reaches us instead of being
            // swallowed as a plain activation.
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }

        // Keep the icon in step with the connection state.
        cancellable = bridge.objectWillChange.sink { [weak self] _ in
            DispatchQueue.main.async { self?.refreshIcon() }
        }
    }

    private func refreshIcon() {
        item.button?.image = NSImage(systemSymbolName: bridge.menuIcon, accessibilityDescription: "Bridge")
        item.button?.image?.isTemplate = true
    }

    // MARK: - Clicks

    @objc private func buttonClicked() {
        if NSApp.currentEvent?.type == .rightMouseUp {
            showContextMenu()
        } else {
            togglePanel()
        }
    }

    private func showContextMenu() {
        let menu = NSMenu()
        let settings = menu.addItem(withTitle: "Open Settings…", action: #selector(openSettings), keyEquivalent: ",")
        settings.target = self
        menu.addItem(.separator())
        let quit = menu.addItem(withTitle: "Quit Bridge", action: #selector(quitBridge), keyEquivalent: "q")
        quit.target = self

        // Showing a menu this way pops it under the item and clears it afterwards,
        // so the normal left-click action keeps working.
        item.menu = menu
        item.button?.performClick(nil)
        item.menu = nil
    }

    @objc private func openSettings() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        for name in ["showSettingsWindow:", "showPreferencesWindow:"] {
            if NSApp.sendAction(Selector((name)), to: nil, from: nil) { return }
        }
    }

    @objc private func quitBridge() {
        bridge.stopProcessesNow()
        NSApp.terminate(nil)
    }

    // MARK: - The panel

    func togglePanel() {
        if panel?.isVisible == true { closePanel() } else { openPanel() }
    }

    private func openPanel() {
        let hosting = NSHostingView(rootView: MenuView(bridge: bridge))
        hosting.setFrameSize(hosting.fittingSize)

        let panel = NSPanel(contentRect: NSRect(origin: .zero, size: hosting.fittingSize),
                            styleMask: [.borderless, .nonactivatingPanel],
                            backing: .buffered, defer: false)
        panel.contentView = hosting
        panel.isOpaque = false
        panel.backgroundColor = .clear          // the view draws its own shape
        panel.hasShadow = true
        panel.level = .statusBar
        panel.hidesOnDeactivate = false
        panel.isMovable = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        panel.delegate = self

        position(panel)
        panel.orderFrontRegardless()
        self.panel = panel

        // Dismiss on a click anywhere else, the way a menu does. Installed a
        // beat later: the click that opens the panel is itself a global event,
        // and would otherwise close it again immediately.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { [weak self] in
            guard let self = self, self.panel != nil else { return }
            self.outsideClicks = NSEvent.addGlobalMonitorForEvents(
                matching: [.leftMouseDown, .rightMouseDown]
            ) { [weak self] event in
                Task { @MainActor in
                    guard let self = self, let panel = self.panel else { return }
                    // A click on our own item is a toggle, handled elsewhere.
                    let point = NSEvent.mouseLocation
                    if !panel.frame.contains(point) { self.closePanel() }
                }
            }
        }
    }

    /// Hangs the panel under the status item, nudged inside the screen edge.
    private func position(_ panel: NSPanel) {
        guard let button = item.button, let itemWindow = button.window else { return }
        let buttonFrame = itemWindow.convertToScreen(button.convert(button.bounds, to: nil))
        let screen = itemWindow.screen ?? NSScreen.main
        var x = buttonFrame.midX - panel.frame.width / 2
        if let visible = screen?.visibleFrame {
            x = min(max(visible.minX + 8, x), visible.maxX - panel.frame.width - 8)
        }
        panel.setFrameTopLeftPoint(NSPoint(x: x, y: buttonFrame.minY - 6))
    }

    func closePanel() {
        if let monitor = outsideClicks { NSEvent.removeMonitor(monitor) }
        outsideClicks = nil
        panel?.orderOut(nil)
        panel = nil
    }
}
