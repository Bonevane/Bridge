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
            button.image = Self.markImage
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

    /// The bridge mark as a template image, so the menu bar recolours it.
    /// Falls back to a symbol when run outside the bundle (e.g. `swift run`).
    ///
    /// `image(forResource:)` rather than `NSImage(contentsOf:)`: only the former
    /// pairs `MenuBarIcon@2x.png` with the 1× file. Loading the file directly
    /// gave Retina screens the 1× bitmap scaled up, which is why it was blurry.
    private static let markImage: NSImage = {
        let image = Bundle.main.image(forResource: "MenuBarIcon")
            ?? NSImage(systemSymbolName: "iphone", accessibilityDescription: "Bridge")!
        image.isTemplate = true
        image.accessibilityDescription = "Bridge"
        return image
    }()

    /// The mark is constant; state shows as dimming, the way Wi-Fi's icon does
    /// when there's nothing to connect to.
    private func refreshIcon() {
        item.button?.image = Self.markImage
        item.button?.alphaValue = bridge.isConnected || bridge.bluetoothLinked ? 1 : 0.5
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
        let about = menu.addItem(withTitle: "About Bridge", action: #selector(showAbout), keyEquivalent: "")
        about.target = self
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

    /// The standard About panel, with the author's links live in the credits.
    @objc private func showAbout() {
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)

        let body = NSFont.systemFont(ofSize: 11)
        let credits = NSMutableAttributedString(
            string: "Your Android phone on your Mac, over any network.\n\nMade by ",
            attributes: [.font: body, .foregroundColor: NSColor.secondaryLabelColor]
        )
        credits.append(link("Bonevane", to: "https://github.com/Bonevane", font: body))
        credits.append(NSAttributedString(string: " · ", attributes: [.font: body,
                                                                     .foregroundColor: NSColor.secondaryLabelColor]))
        credits.append(link("bonevane.vercel.app", to: "https://bonevane.vercel.app", font: body))
        credits.append(NSAttributedString(
            string: "\n\nBuilt on scrcpy (Apache-2.0), dumbpipe and iroh (MIT/Apache-2.0), and code from Shizuku (Apache-2.0). See NOTICE.",
            attributes: [.font: NSFont.systemFont(ofSize: 10), .foregroundColor: NSColor.tertiaryLabelColor]))
        credits.setAlignment(.center, range: NSRange(location: 0, length: credits.length))

        NSApp.orderFrontStandardAboutPanel(options: [.credits: credits])
    }

    private func link(_ text: String, to url: String, font: NSFont) -> NSAttributedString {
        NSAttributedString(string: text, attributes: [
            .font: font,
            .link: URL(string: url) as Any,
            .foregroundColor: NSColor.linkColor,
        ])
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
