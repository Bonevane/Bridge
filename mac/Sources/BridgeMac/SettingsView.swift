import AppKit
import SwiftUI

/// Bridge's settings, in the shape macOS uses: icon tabs across the top, and
/// inside each one a grouped `Form` where anything with a consequence says what
/// it is. The General tab opens with what's true right now, so the window is
/// useful before you change a thing.
struct SettingsView: View {
    @ObservedObject var bridge: BridgeController

    var body: some View {
        TabView {
            generalTab
                .tabItem { Label("General", systemImage: "gearshape") }
            phoneTab
                .tabItem { Label("Phone", systemImage: "iphone") }
            displayTab
                .tabItem { Label("Mirroring", systemImage: "display") }
            pairingTab
                .tabItem { Label("Pairing", systemImage: "link") }
        }
        .frame(width: 500, height: 430)
    }

    // MARK: - General

    private var generalTab: some View {
        Form {
            Section {
                StatusLine(icon: "dot.radiowaves.left.and.right", title: "Bluetooth",
                           value: bridge.bluetoothLinked ? "Linked to your phone" : "Phone not in range",
                           good: bridge.bluetoothLinked)
                StatusLine(icon: "globe", title: "Phone's tunnel",
                           value: bridge.phoneTunnelOn ? "On, reachable anywhere" : "Off, nearby only",
                           good: bridge.phoneTunnelOn)
                StatusLine(icon: "bolt.horizontal", title: "Between sessions",
                           value: bridge.keepReady ? "Kept ready" : "USB debugging off",
                           good: bridge.keepReady)
            } header: {
                Text("Right now")
            } footer: {
                Text(bridge.bluetoothLinked && !bridge.phoneTunnelOn
                     ? "Mirroring needs the tunnel. Bridge turns it on over Bluetooth when you click Mirror Phone."
                     : "Notifications and the clipboard use Bluetooth; mirroring uses the tunnel.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
            }

            Section {
                Toggle("Open Bridge at login", isOn: $bridge.launchAtLogin)
            } footer: {
                Text("Bridge lives in the menu bar, and shows a Dock icon only while a phone window is open.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
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
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
            }

            Section("While mirroring") {
                Toggle("Turn the phone's screen off", isOn: $bridge.turnScreenOff)
                Toggle("Mute the phone", isOn: $bridge.mutePhone)
            }
            Section {
                VStack(spacing: 3) {
                    Text("Bridge \(Bundle.main.shortVersion)")
                        .font(.caption)
                    HStack(spacing: 5) {
                        Text("Made by").font(.caption).foregroundStyle(.secondary)
                        Link("Bonevane", destination: URL(string: "https://github.com/Bonevane")!)
                            .font(.caption)
                        Text("·").font(.caption).foregroundStyle(.secondary)
                        Link("bonevane.vercel.app", destination: URL(string: "https://bonevane.vercel.app")!)
                            .font(.caption)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .center)
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
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
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
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
            }

            Section {
                Toggle("Show the phone's notifications", isOn: $bridge.mirrorNotifications)
                Toggle("Also over the internet, when the phone is far away", isOn: $bridge.notificationsAnywhere)
                    .disabled(!bridge.mirrorNotifications)
            } header: {
                Text("Notifications")
            } footer: {
                Text("The first option is free: notifications come over Bluetooth while the phone is nearby, with no internet connection and no USB debugging. The second keeps a tunnel open so they also arrive when the phone is elsewhere; that costs the phone a little battery and data all the time.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
            }

            Section {
                Toggle("Sync the clipboard", isOn: $bridge.syncClipboard)
                Toggle("Also with no phone window open", isOn: $bridge.backgroundClipboard)
                    .disabled(!bridge.syncClipboard)
            } header: {
                Text("Clipboard")
            } footer: {
                Text("The first option is free: while a phone window is open the clipboard rides the mirroring session, and while the phone is nearby it goes over Bluetooth. The second keeps the phone's helper running and a channel open at all times, so it also works with no window and no Bluetooth; that costs battery and data, and turns on \"keep ready\".")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
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
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
            }

            Section {
                HStack {
                    TextField("endpoint…", text: $bridge.ticket)
                        .font(.system(.body, design: .monospaced))
                        .disabled(bridge.isBusy || bridge.isConnected)
                    Button("Paste") {
                        if let s = NSPasteboard.general.string(forType: .string)?
                            .trimmingCharacters(in: .whitespacesAndNewlines) {
                            bridge.ticket = s
                        }
                    }
                    .disabled(bridge.isBusy || bridge.isConnected)
                }
                if !bridge.ticket.isEmpty {
                    let ok = bridge.ticket.hasPrefix("endpoint")
                    StatusLine(icon: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill",
                               title: "Ticket",
                               value: ok ? "Looks right" : "Should start with \"endpoint\"",
                               good: ok)
                }
            } header: {
                Text("Pair without a cable")
            } footer: {
                Text("On the phone, tap Copy under Ticket and get it to this Mac privately (AirDrop, Notes, a message to yourself). Then click Paste. Never post the ticket anywhere: it's a key, not an address.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
        .formStyle(.grouped)
    }

    private var shortTicket: String {
        let t = bridge.ticket
        return t.count > 28 ? "\(t.prefix(14))…\(t.suffix(8))" : t
    }
}

/// A read-only line with a tinted icon: state, not a control.
private struct StatusLine: View {
    let icon: String
    let title: String
    let value: String
    let good: Bool

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill((good ? Color.green : Color.secondary).opacity(0.18))
                    .frame(width: 24, height: 24)
                Image(systemName: icon)
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(good ? Color.green : Color.secondary)
            }
            Text(title)
            Spacer()
            Text(value).foregroundStyle(.secondary)
        }
    }
}
