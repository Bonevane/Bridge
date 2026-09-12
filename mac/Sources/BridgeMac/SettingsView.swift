import AppKit
import SwiftUI

/// Bridge's settings, in the shape macOS uses for System Settings: a grouped
/// `Form` of labelled sections, switches on the trailing edge, and an
/// explanatory line under anything whose consequences aren't obvious.
struct SettingsView: View {
    @ObservedObject var bridge: BridgeController

    var body: some View {
        TabView {
            generalTab
                .tabItem { Label("General", systemImage: "gearshape") }
            displayTab
                .tabItem { Label("Display", systemImage: "display") }
            phoneTab
                .tabItem { Label("Phone", systemImage: "iphone") }
            pairingTab
                .tabItem { Label("Pairing", systemImage: "link") }
        }
        .frame(width: 460, height: 340)
    }

    // MARK: - General

    private var generalTab: some View {
        Form {
            Section {
                Toggle("Open Bridge at login", isOn: $bridge.launchAtLogin)
            } footer: {
                Text("Bridge lives in the menu bar and shows a Dock icon only while a phone window is open.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                LabeledContent("Phone", value: bridge.ticket.isEmpty ? "Not paired" : "Paired")
                LabeledContent("Between sessions",
                               value: bridge.keepReady ? "Kept ready" : "USB debugging off")
            } header: {
                Text("Status")
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Display

    private var displayTab: some View {
        Form {
            Section {
                Picker("Resolution", selection: $bridge.maxSize) {
                    Text("720 px").tag(720)
                    Text("1024 px").tag(1024)
                    Text("1280 px").tag(1280)
                    Text("1600 px").tag(1600)
                    Text("Full size").tag(0)
                }
                Stepper(value: $bridge.bitrateMbps, in: 1...16) {
                    LabeledContent("Bitrate", value: "\(bridge.bitrateMbps) Mbps")
                }
            } header: {
                Text("Video")
            } footer: {
                Text("Bridge lowers the bitrate by itself when the connection can't keep up, and raises it again afterwards.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("While mirroring") {
                Toggle("Turn the phone's screen off", isOn: $bridge.turnScreenOff)
                Toggle("Mute the phone", isOn: $bridge.mutePhone)
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Phone

    private var phoneTab: some View {
        Form {
            Section {
                Toggle("Keep the phone ready", isOn: $bridge.keepReady)
            } header: {
                Text("Between sessions")
            } footer: {
                Text(bridge.keepReady
                     ? "USB debugging stays on, over the cable only, so nothing is reachable on the network. Connecting works from anywhere, including cellular. Pause it when an app refuses to run."
                     : "USB debugging is turned off after each session, so apps that check for it behave normally. Connecting then needs Wi-Fi for about a second, which means cellular only works if the last session ended on cellular.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                Toggle("Use Bluetooth when the phone is nearby", isOn: $bridge.useBluetooth)
                LabeledContent("Bluetooth", value: bridge.bluetoothLinked ? "Linked" : "Not in range")
            } header: {
                Text("Short-range link")
            } footer: {
                Text("Notifications and the clipboard travel over Bluetooth when the phone is within range, which costs almost nothing. Without it they need an open internet tunnel.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                Toggle("Show the phone's notifications", isOn: $bridge.mirrorNotifications)
                Toggle("Even when the phone is far away", isOn: $bridge.notificationsAnywhere)
                    .disabled(!bridge.mirrorNotifications)
            } header: {
                Text("Notifications")
            } footer: {
                Text("Works even while USB debugging is off, because it needs no privileged access on the phone. Grant Bridge notification access there first. Bridge keeps a connection open while this is on, which uses a little battery and data.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section {
                Toggle("Sync the clipboard", isOn: $bridge.syncClipboard)
                Toggle("Sync even with no window open", isOn: $bridge.backgroundClipboard)
                    .disabled(!bridge.syncClipboard)
            } header: {
                Text("Clipboard")
            } footer: {
                Text("Copying on either device makes the text available on the other. Syncing with no window open holds a connection to the phone all the time, which costs battery and data, and needs the phone kept ready.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
    }

    // MARK: - Pairing

    private var pairingTab: some View {
        Form {
            Section {
                LabeledContent("Ticket") {
                    HStack(spacing: 6) {
                        Text(bridge.ticket.isEmpty ? "Not paired" : shortTicket)
                            .font(.system(.body, design: .monospaced))
                            .foregroundStyle(bridge.ticket.isEmpty ? .secondary : .primary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        if !bridge.ticket.isEmpty {
                            Button {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(bridge.ticket, forType: .string)
                            } label: {
                                Image(systemName: "doc.on.doc")
                            }
                            .buttonStyle(.borderless)
                            .help("Copy the ticket")
                        }
                    }
                }
                Button("Set Up Over USB…") { bridge.setUpOverUSB() }
                    .disabled(bridge.isBusy || bridge.isConnected)
            } header: {
                Text("This phone")
            } footer: {
                Text("Needed once. Plug the phone in, unlock it, and click. Treat the ticket like a password: anyone who has it can reach the phone.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Paste a ticket") {
                TextField("endpoint…", text: $bridge.ticket)
                    .font(.system(.body, design: .monospaced))
                    .disabled(bridge.isBusy || bridge.isConnected)
            }
        }
        .formStyle(.grouped)
    }

    private var shortTicket: String {
        let t = bridge.ticket
        return t.count > 28 ? "\(t.prefix(14))…\(t.suffix(8))" : t
    }
}
