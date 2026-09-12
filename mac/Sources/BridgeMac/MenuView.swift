import AppKit
import SwiftUI

/// The panel that drops from the menu-bar icon.
///
/// Built the way macOS 26 builds these: content sits on the system's own
/// vibrant backdrop, and the things you can touch — the status pill, the main
/// button, each action — are Liquid Glass shapes floating above it. Everything
/// that isn't a quick action lives in the Settings window.
struct MenuView: View {
    @ObservedObject var bridge: BridgeController
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        GlassGroup(spacing: 14) {
            VStack(alignment: .leading, spacing: 12) {
                statusPill
                primaryButton

                VStack(spacing: 2) {
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

                    Divider().padding(.horizontal, 10).padding(.vertical, 2)

                    MenuRow("Quit Bridge", systemImage: "power", shortcut: "⌘Q") {
                        bridge.stopProcessesNow()
                        NSApp.terminate(nil)
                    }
                }
                .padding(.vertical, 5)
                .glassPanel(cornerRadius: 14)

                logSection
            }
            .padding(14)
        }
        .frame(width: 312)
        .onAppear { bridge.refreshPhoneStatus() }
    }

    // MARK: - Status

    /// A glass pill carrying the one thing you opened the menu to find out.
    private var statusPill: some View {
        HStack(spacing: 10) {
            Image(systemName: statusSymbol)
                .font(.system(size: 17, weight: .medium))
                .foregroundStyle(statusColor)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 1) {
                Text(statusTitle)
                    .font(.headline)
                if let detail = statusDetail {
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(bridge.isFailed ? AnyShapeStyle(.red) : AnyShapeStyle(.secondary))
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 0)
            if case .working = bridge.phase {
                ProgressView().controlSize(.small)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .glassPanel(cornerRadius: 14, tint: statusTint)
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
                .padding(.vertical, 3)
        }
        .glassProminentButton()
        .controlSize(.large)
        .keyboardShortcut(.return, modifiers: [])
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
                .frame(height: 150)
                Button("Copy") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(bridge.log.joined(separator: "\n"), forType: .string)
                }
                .glassButton()
                .controlSize(.small)
            }
            .padding(.top, 6)
        } label: {
            Label("Activity Log", systemImage: "text.alignleft")
                .font(.callout)
        }
        .padding(.horizontal, 6)
        .padding(.top, 2)
    }

    // MARK: - Status wording

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

    /// Glass takes a colour from what it's reporting, but only when that's
    /// worth noticing: idle shouldn't shout.
    private var statusTint: Color? {
        switch bridge.phase {
        case .connected: return .green
        case .failed: return .red
        case .working: return .orange
        case .idle: return nil
        }
    }
}

/// One action in the panel: an interactive glass row that lights up under the
/// pointer, which is how menu items behave on macOS 26.
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
                    .font(.system(size: 13))
                    .frame(width: 17)
                Text(title)
                Spacer(minLength: 6)
                if let shortcut = shortcut {
                    Text(shortcut).foregroundStyle(.tertiary).font(.callout)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .opacity(isEnabled ? 1 : 0.4)
        .glassRowBackground(active: hover.inside && isEnabled)
        .onHover { hover.inside = $0 }
        .help(help ?? "")
    }
}

private extension View {
    /// The rows already sit on glass, so stacking more glass on hover muddies
    /// it: a soft accent fill reads much better, and matches system menus.
    func glassRowBackground(active: Bool) -> some View {
        self.background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.accentColor.opacity(active ? 0.85 : 0))
                .padding(.horizontal, 5)
        )
        .foregroundStyle(active ? AnyShapeStyle(.white) : AnyShapeStyle(.primary))
    }
}

private final class HoverState: ObservableObject {
    @Published var inside = false
}
