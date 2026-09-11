import AppKit
import SwiftUI

/// The panel that opens from the menu-bar icon.
struct MenuView: View {
    @ObservedObject var bridge: BridgeController

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            header
            primaryButton

            Divider()

            VStack(alignment: .leading, spacing: 6) {
                Text("Phone ticket")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TextField("endpoint...", text: $bridge.ticket)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(size: 11, design: .monospaced))
                    .disabled(bridge.isBusy || bridge.isConnected)
                Button("Pause phone for banking (15 min)") {
                    bridge.sendCommand("PAUSE 15", label: "Pausing the phone...")
                }
                    .disabled(bridge.isBusy)
                Button("Set up over USB") { bridge.setUpOverUSB() }
                    .disabled(bridge.isBusy || bridge.isConnected)
                Text("Only needed once; after a restart the phone gets ready on Wi-Fi.")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }

            DisclosureGroup("Options", isExpanded: $bridge.showOptions) {
                VStack(alignment: .leading, spacing: 8) {
                    Stepper("Bitrate: \(bridge.bitrateMbps) Mbps", value: $bridge.bitrateMbps, in: 1...16)
                    Picker("Max size", selection: $bridge.maxSize) {
                        Text("720 px").tag(720)
                        Text("1024 px").tag(1024)
                        Text("1280 px").tag(1280)
                        Text("1600 px").tag(1600)
                        Text("Full").tag(0)
                    }
                    Toggle("Turn phone screen off while mirroring", isOn: $bridge.turnScreenOff)
                    Toggle("Keep phone ready after disconnect", isOn: $bridge.keepReady)
                    Text(bridge.keepReady
                         ? "USB debugging stays on (USB only, nothing on the network) so Connect works on cellular. Use \"Pause\" for banking apps."
                         : "USB debugging turns off after each session (safest). From cellular you can only connect if the last disconnect was on cellular.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Text("Changes apply on the next Connect.")
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                .padding(.top, 6)
            }

            DisclosureGroup("Log", isExpanded: $bridge.showLog) {
                VStack(alignment: .leading, spacing: 6) {
                    ScrollView {
                        Text(bridge.log.suffix(150).joined(separator: "\n"))
                            .font(.system(size: 10, design: .monospaced))
                            .textSelection(.enabled)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(height: 160)
                    Button("Copy log") {
                        NSPasteboard.general.clearContents()
                        NSPasteboard.general.setString(bridge.log.joined(separator: "\n"), forType: .string)
                    }
                }
                .padding(.top, 6)
            }

            Divider()

            Button("Quit Bridge") {
                bridge.stopProcessesNow()
                NSApp.terminate(nil)
            }
        }
        .padding(14)
        .frame(width: 340)
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Circle()
                    .fill(statusColor)
                    .frame(width: 9, height: 9)
                Text(statusTitle)
                    .font(.headline)
            }
            switch bridge.phase {
            case .working(let step):
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text(step).font(.callout).foregroundStyle(.secondary)
                }
            case .failed(let message):
                Text(message)
                    .font(.callout)
                    .foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            default:
                EmptyView()
            }
            if let notice = bridge.notice {
                Text(notice)
                    .font(.callout)
                    .foregroundStyle(.secondary)
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
    }

    private var statusTitle: String {
        switch bridge.phase {
        case .idle: return "Not connected"
        case .working: return "Connecting"
        case .connected: return "Connected"
        case .failed: return "Problem"
        }
    }

    private var statusColor: Color {
        switch bridge.phase {
        case .idle: return .gray
        case .working: return .orange
        case .connected: return .green
        case .failed: return .red
        }
    }
}
