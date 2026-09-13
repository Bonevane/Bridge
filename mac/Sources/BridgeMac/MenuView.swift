import AppKit
import SwiftUI

/// The panel that drops from the menu-bar icon.
///
/// It answers three questions in order, because that's the order you ask them:
/// is my phone reachable, what works right now, and what can I do about it.
/// Everything else is in Settings.
struct MenuView: View {
    @ObservedObject var bridge: BridgeController
    // `openSettings` needs macOS 14; the selector works everywhere Bridge runs.

    var body: some View {
        GlassGroup(spacing: 14) {
            VStack(spacing: 10) {
                header
                connection
                primaryButton
                capabilities
                actions
                logSection
            }
            .padding(12)
        }
        .frame(width: 300)
        // The panel's own backdrop. SwiftUI's menu-bar window is square, so the
        // shape is drawn here and the window left transparent behind it. This is
        // the one glass layer: `.regularMaterial` read as a plain blur and looked
        // nothing like the system's own menu-bar panels.
        .glassPanel(cornerRadius: 13)
        .onAppear { bridge.refreshPhoneStatus() }
    }

    // MARK: - Header

    private var header: some View {
        ZStack {
            Text("Bridge")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button(action: bridge.refreshBluetooth) {
                    Image(systemName: "arrow.clockwise")
                        .font(.system(size: 11, weight: .medium))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help("Look for the phone again")
            }
        }
        .frame(maxWidth: .infinity)
    }

    /// How the phone is reachable at the moment: the one line worth reading first.
    private var connection: some View {
        HStack(spacing: 11) {
            ZStack {
                Circle()
                    .fill(reach.tint.opacity(0.18))
                    .frame(width: 34, height: 34)
                Image(systemName: reach.symbol)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(reach.tint)
            }
            VStack(alignment: .leading, spacing: 1) {
                Text(reach.title)
                    .font(.system(size: 13, weight: .semibold))
                Text(reach.detail)
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 0)
            if case .working = bridge.phase {
                ProgressView().controlSize(.small)
            } else if bridge.bluetoothState == .searching && !bridge.isConnected {
                // Hunting for the phone: say so, rather than looking idle.
                ProgressView().controlSize(.small)
            }
        }
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .frame(maxWidth: .infinity, alignment: .leading)
        .innerCard(tint: reach.tint == .secondary ? nil : reach.tint)
    }

    private var primaryButton: some View {
        Button {
            if bridge.isConnected || bridge.isBusy {
                bridge.disconnect()
            } else {
                bridge.connect()
            }
        } label: {
            Text(bridge.isConnected || bridge.isBusy ? "Disconnect" : "Mirror Phone")
                .frame(maxWidth: .infinity)
                .padding(.vertical, 2)
        }
        .glassProminentButton()
        .controlSize(.large)
        .keyboardShortcut(.return, modifiers: [])
    }

    // MARK: - What works

    private var capabilities: some View {
        VStack(spacing: 0) {
            CapabilityRow(icon: "iphone.badge.play", name: "Screen", state: bridge.screenCapability)
            Divider().padding(.leading, 34)
            CapabilityRow(icon: "bell.badge", name: "Notifications", state: bridge.notificationCapability)
            Divider().padding(.leading, 34)
            CapabilityRow(icon: "doc.on.clipboard", name: "Clipboard", state: bridge.clipboardCapability)
        }
        .padding(.vertical, 3)
        .innerCard()
    }

    private var actions: some View {
        VStack(spacing: 1) {
            MenuRow("Pause Phone for Banking", systemImage: "pause.circle",
                    help: "Turns the phone's USB debugging off for 15 minutes") {
                bridge.sendCommand("PAUSE 15", label: "Pausing the phone…")
            }
            .disabled(bridge.isBusy)

            if bridge.bluetoothLinked {
                MenuRow(bridge.phoneTunnelOn ? "Turn Phone Tunnel Off" : "Turn Phone Tunnel On",
                        systemImage: bridge.phoneTunnelOn ? "globe.badge.chevron.backward" : "globe",
                        help: "Switches the phone's internet tunnel over Bluetooth") {
                    bridge.togglePhoneTunnel()
                }
            }

            MenuRow("Set Up Over USB…", systemImage: "cable.connector",
                    help: "Pair a phone that's plugged in") {
                bridge.setUpOverUSB()
            }
            .disabled(bridge.isBusy || bridge.isConnected)

            MenuRow("Settings…", systemImage: "gearshape", shortcut: "⌘,") {
                NSApp.setActivationPolicy(.regular)
                NSApp.activate(ignoringOtherApps: true)
                openSettingsWindow()
            }

            Divider().padding(.horizontal, 9).padding(.vertical, 2)

            MenuRow("Quit Bridge", systemImage: "power", shortcut: "⌘Q") {
                bridge.stopProcessesNow()
                NSApp.terminate(nil)
            }
        }
        .padding(.vertical, 4)
        .innerCard()
    }

    private var logSection: some View {
        DisclosureGroup(isExpanded: $bridge.showLog) {
            VStack(alignment: .leading, spacing: 8) {
                ScrollView {
                    Text(bridge.log.suffix(150).joined(separator: "\n"))
                        .font(.system(size: 10, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(height: 140)
                HStack {
                    Button("Copy") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(bridge.log.joined(separator: "\n"), forType: .string)
                    }
                    .glassButton()
                    .controlSize(.small)
                    // The panel is too small to read a long log in; hand the
                    // whole thing to TextEdit instead.
                    Button("Open Full Log") {
                        let url = FileManager.default.temporaryDirectory.appendingPathComponent("Bridge.log")
                        try? bridge.log.joined(separator: "\n").write(to: url, atomically: true, encoding: .utf8)
                        NSWorkspace.shared.open(url)
                    }
                    .glassButton()
                    .controlSize(.small)
                }
            }
            .padding(.top, 6)
        } label: {
            Label("Activity Log", systemImage: "text.alignleft")
                .font(.system(size: 12))
        }
        .padding(.horizontal, 4)
    }

    // MARK: - How the phone is reachable

    private struct Reach {
        let title: String
        let detail: String
        let symbol: String
        let tint: Color
    }

    private var reach: Reach {
        if case .failed(let message) = bridge.phase {
            return Reach(title: "Couldn't connect", detail: message,
                         symbol: "exclamationmark.triangle.fill", tint: .red)
        }
        if case .working(let step) = bridge.phase {
            return Reach(title: "Connecting", detail: step,
                         symbol: "iphone.radiowaves.left.and.right", tint: .orange)
        }
        if bridge.isConnected {
            return Reach(title: "Mirroring", detail: "Screen, audio and clipboard are live",
                         symbol: "checkmark.circle.fill", tint: .green)
        }
        if bridge.phonePausedForBanking {
            return Reach(title: "Phone paused", detail: "USB debugging off for banking apps",
                         symbol: "pause.circle.fill", tint: .orange)
        }
        if bridge.bluetoothLinked {
            return Reach(title: "Phone nearby",
                         detail: bridge.phoneTunnelOn
                            ? "Bluetooth linked, and reachable from anywhere"
                            : "Bluetooth linked · tunnel off, so no remote mirroring",
                         symbol: "dot.radiowaves.left.and.right", tint: .green)
        }
        switch bridge.bluetoothState {
        case .searching:
            return Reach(title: "Looking for your phone",
                         detail: "Searching over Bluetooth. Mirroring still works if its tunnel is on.",
                         symbol: "dot.radiowaves.forward", tint: .secondary)
        case .unauthorized:
            return Reach(title: "Bluetooth not allowed",
                         detail: "Allow Bridge to use Bluetooth in System Settings › Privacy & Security.",
                         symbol: "exclamationmark.triangle.fill", tint: .orange)
        case .off:
            return Reach(title: "Phone not nearby",
                         detail: bridge.useBluetooth
                            ? "Bluetooth is off on this Mac."
                            : "Bluetooth is switched off in Bridge's settings.",
                         symbol: "iphone.slash", tint: .secondary)
        case .linked:
            break   // handled above
        }
        return Reach(title: "Phone not nearby",
                     detail: "Out of Bluetooth range. Mirroring still works if its tunnel is on.",
                     symbol: "iphone.slash", tint: .secondary)
    }
}

private extension View {
    /// A card sitting *on* the panel's glass. Deliberately not glass itself:
    /// stacking glass on glass turns both to mud.
    func innerCard(tint: Color? = nil) -> some View {
        background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(tint?.opacity(0.16) ?? Color.primary.opacity(0.06))
        )
    }
}

/// One line of "what works right now", with the reason underneath.
private struct CapabilityRow: View {
    let icon: String
    let name: String
    let state: BridgeController.Capability

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: icon)
                .font(.system(size: 12))
                .foregroundStyle(.secondary)
                .frame(width: 17)
            VStack(alignment: .leading, spacing: 0) {
                Text(name).font(.system(size: 12, weight: .medium))
                Text(state.detail)
                    .font(.system(size: 10))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            Image(systemName: state.symbol)
                .font(.system(size: 12))
                .foregroundStyle(tint)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
    }

    private var tint: Color {
        switch state {
        case .working: return .green
        case .limited: return .orange
        case .off: return .secondary.opacity(0.6)
        }
    }
}

/// One action in the panel, highlighted under the pointer like a system menu.
private struct MenuRow: View {
    let title: String
    let systemImage: String
    var shortcut: String?
    var help: String?
    let action: () -> Void

    // `@State` is a compiler macro the Command Line Tools can't expand (no Xcode),
    // so hover tracking uses a small observable object instead.
    @StateObject private var hover = HoverState()
    @Environment(\.isEnabled) private var isEnabled

    init(_ title: String, systemImage: String, shortcut: String? = nil,
         help: String? = nil, action: @escaping () -> Void) {
        self.title = title
        self.systemImage = systemImage
        self.shortcut = shortcut
        self.help = help
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            HStack(spacing: 9) {
                Image(systemName: systemImage)
                    .font(.system(size: 12))
                    .frame(width: 17)
                Text(title).font(.system(size: 12))
                Spacer(minLength: 6)
                if let shortcut = shortcut {
                    Text(shortcut).foregroundStyle(.tertiary).font(.system(size: 11))
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .opacity(isEnabled ? 1 : 0.4)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.accentColor.opacity(hover.inside && isEnabled ? 0.85 : 0))
                .padding(.horizontal, 5)
        )
        .foregroundStyle(hover.inside && isEnabled ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
        .onHover { hover.inside = $0 }
        .help(help ?? "")
    }
}

private final class HoverState: ObservableObject {
    @Published var inside = false
}

/// Opens the Settings scene. The SwiftUI environment action for this is macOS 14
/// and later, so this goes through the app's own menu action, which AppKit has
/// always had (and whose selector was renamed in macOS 13).
private func openSettingsWindow() {
    let selectors = ["showSettingsWindow:", "showPreferencesWindow:"]
    for name in selectors {
        let selector = Selector((name))
        if NSApp.sendAction(selector, to: nil, from: nil) { return }
    }
}
