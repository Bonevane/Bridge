import AppKit
import SwiftUI

/// The panel that drops from the menu-bar icon.
///
/// Follows how macOS itself builds these: a short status line at the top, one
/// obvious primary action, a few secondary actions as a plain list, and
/// everything else behind Settings. Details live in `SettingsView`.
struct MenuView: View {
    @ObservedObject var bridge: BridgeController
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            status
            primaryButton

            Divider()

            MenuRow("Pause Phone for Banking", systemImage: "pause.circle",
                    help: "Turns the phone's USB debugging off for 15 minutes") {
                bridge.sendCommand("PAUSE 15", label: "Pausing the phone…")
            }
            .disabled(bridge.isBusy)

            MenuRow("Set Up Over USB…", systemImage: "cable.connector",
                    help: "Pair a phone that's plugged in") {
                bridge.setUpOverUSB()
            }
            .disabled(bridge.isBusy || bridge.isConnected)

            MenuRow("Settings…", systemImage: "gearshape", shortcut: "⌘,") {
                NSApp.setActivationPolicy(.regular)
                NSApp.activate(ignoringOtherApps: true)
                openWindow(id: "settings")
            }

            DisclosureGroup(isExpanded: $bridge.showLog) {
                VStack(alignment: .leading, spacing: 6) {
                    ScrollView {
                        Text(bridge.log.suffix(150).joined(separator: "\n"))
                            .font(.system(size: 10, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(height: 150)
                    Button("Copy") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(bridge.log.joined(separator: "\n"), forType: .string)
                    }
                    .controlSize(.small)
                }
                .padding(.top, 4)
            } label: {
                Label("Activity Log", systemImage: "text.alignleft")
                    .font(.body)
            }

            Divider()

            MenuRow("Quit Bridge", systemImage: "power", shortcut: "⌘Q") {
                bridge.stopProcessesNow()
                NSApp.terminate(nil)
            }
        }
        .padding(12)
        .frame(width: 300)
        .onAppear { bridge.refreshPhoneStatus() }
    }

    // MARK: - Status

    private var status: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 7) {
                Image(systemName: statusSymbol)
                    .foregroundStyle(statusColor)
                    .font(.system(size: 13, weight: .semibold))
                Text(statusTitle)
                    .font(.headline)
                Spacer()
                if case .working = bridge.phase {
                    ProgressView().controlSize(.small)
                }
            }
            if let detail = statusDetail {
                Text(detail)
                    .font(.callout)
                    .foregroundStyle(bridge.isFailed ? .red : .secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var primaryButton: some View {
        Button {
            if bridge.isConnected || bridge.isBusy {
                bridge.disconnect()
            } else {
                bridge.connect()
            }
        } label: {
            Text(bridge.isConnected || bridge.isBusy ? "Disconnect" : "Connect")
                .frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .keyboardShortcut(.return, modifiers: [])
    }

    private var statusTitle: String {
        switch bridge.phase {
        case .idle: return bridge.phonePausedForBanking ? "Phone paused" : "Not connected"
        case .working: return "Connecting"
        case .connected: return "Connected"
        case .failed: return "Couldn't connect"
        }
    }

    private var statusDetail: String? {
        switch bridge.phase {
        case .working(let step): return step
        case .failed(let message): return message
        default: return bridge.notice
        }
    }

    private var statusSymbol: String {
        switch bridge.phase {
        case .idle: return bridge.phonePausedForBanking ? "pause.circle.fill" : "iphone.slash"
        case .working: return "iphone.radiowaves.left.and.right"
        case .connected: return "checkmark.circle.fill"
        case .failed: return "exclamationmark.triangle.fill"
        }
    }

    private var statusColor: Color {
        switch bridge.phase {
        case .idle: return .secondary
        case .working: return .orange
        case .connected: return .green
        case .failed: return .red
        }
    }
}

/// A menu item: icon, title, optional shortcut hint, highlighting on hover.
/// SwiftUI has no menu-row style for `MenuBarExtra(.window)`, so this matches
/// the system's look by hand.
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
            HStack(spacing: 8) {
                Image(systemName: systemImage)
                    .frame(width: 16)
                Text(title)
                Spacer()
                if let shortcut = shortcut {
                    Text(shortcut)
                        .foregroundStyle(.tertiary)
                        .font(.callout)
                }
            }
            .padding(.horizontal, 6)
            .padding(.vertical, 5)
            .background(
                RoundedRectangle(cornerRadius: 5)
                    .fill(hover.inside && isEnabled ? Color.accentColor.opacity(0.85) : .clear)
            )
            .foregroundStyle(hover.inside && isEnabled ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover.inside = $0 }
        .help(help ?? "")
    }
}

private final class HoverState: ObservableObject {
    @Published var inside = false
}
