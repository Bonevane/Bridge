import AppKit
import SwiftUI

// Settings › Updates (this Mac and the phone app) and Settings › Phone ›
// Health. Same grouped Form and the same StatusLine rows as the other tabs.

/// The caption under a section, as every other tab writes it.
private func footer(_ text: String) -> some View {
    Text(text)
        .font(.caption)
        .foregroundStyle(.secondary)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity, alignment: .center)
}

private func megabytes(_ bytes: Int64) -> String {
    String(format: "%.1f MB", Double(bytes) / 1_048_576)
}

struct UpdatesTab: View {
    @ObservedObject var bridge: BridgeController
    @ObservedObject var updater: MacUpdater

    var body: some View {
        Form {
            Section {
                macUpdate
                Toggle("Check for updates automatically", isOn: Binding(
                    get: { updater.automaticChecks }, set: { updater.automaticChecks = $0 }))
                HStack {
                    Text(lastChecked).foregroundStyle(.secondary)
                    Spacer()
                    Button("Check Now") { updater.check(manual: true) }
                        .disabled(updater.state == .checking)
                }
            } header: {
                Text("This Mac")
            } footer: {
                footer("Updates come from Bridge's releases on GitHub. Each one is checked against its published checksum and Bridge's signature before it's installed.")
            }

            Section {
                phoneUpdate
                Button("Check the Phone for Updates") { bridge.phoneUpdateCommand("check") }
                    .disabled(!bridge.bluetoothLinked)
            } header: {
                Text("Phone app")
            } footer: {
                footer("The phone downloads its own update and installs it silently when its helper is running, or asks you to confirm on the phone. It reconnects by itself afterwards.")
            }
        }
        .formStyle(.grouped)
    }

    private var lastChecked: String {
        guard let date = updater.lastChecked else { return "Not checked yet" }
        let f = RelativeDateTimeFormatter()
        f.unitsStyle = .full
        return "Last checked \(f.localizedString(for: date, relativeTo: Date()))"
    }

    // MARK: This Mac

    @ViewBuilder private var macUpdate: some View {
        switch updater.state {
        case .idle, .upToDate:
            StatusLine(icon: "checkmark", title: "Bridge \(MacUpdater.currentVersion)", value: "Up to date", good: true)
        case .checking:
            HStack { Text("Checking for updates…"); Spacer(); ProgressView().controlSize(.small) }
        case .available(let r):
            versionLine(r)
            notes(r)
            HStack {
                Spacer()
                Button("Download Update") { updater.download() }.keyboardShortcut(.defaultAction)
            }
        case .downloading(let r, let done, let total, let rate):
            versionLine(r)
            ProgressView(value: Double(done), total: Double(max(total, 1)))
            HStack {
                Text("\(megabytes(done)) of \(megabytes(total))" + (rate > 0 ? " · \(megabytes(rate))/s" : ""))
                    .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                Spacer()
                Button("Pause") { updater.pause() }
                Button("Cancel") { updater.cancel() }
            }
        case .paused(let r, let done, let total, let reason):
            versionLine(r)
            ProgressView(value: Double(done), total: Double(max(total, 1)))
            HStack {
                Text(reason ?? "Paused at \(megabytes(done)) of \(megabytes(total))")
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
                Button("Resume") { updater.download() }
                Button("Cancel") { updater.cancel() }
            }
        case .verifying(let r):
            versionLine(r)
            HStack { Text("Checking the download…").foregroundStyle(.secondary); Spacer(); ProgressView().controlSize(.small) }
        case .ready(let r):
            versionLine(r)
            notes(r)
            Text(updater.installBlocker ?? "Downloaded and checked. Installing quits Bridge, replaces it, and opens the new version; it reconnects to the phone by itself.")
                .font(.caption).foregroundStyle(updater.installBlocker == nil ? Color.secondary : Color.orange)
            HStack {
                Spacer()
                Button("Install and Relaunch") { updater.install() }
                    .keyboardShortcut(.defaultAction)
                    .disabled(updater.installBlocker != nil)
            }
        case .installing(let r):
            versionLine(r)
            HStack { Text("Installing… Bridge will reopen by itself.").foregroundStyle(.secondary); Spacer(); ProgressView().controlSize(.small) }
        case .failed(let message, let r):
            if let r = r { versionLine(r) }
            Label(message, systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.orange)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Spacer()
                if r != nil {
                    Button("Get It from GitHub") { updater.revealDownload() }
                    Button("Try Again") { updater.download() }
                } else {
                    Button("Try Again") { updater.check(manual: true) }
                }
            }
        }
    }

    private func versionLine(_ r: MacUpdater.Release) -> some View {
        StatusLine(icon: "arrow.down", title: "Bridge \(r.version)",
                   value: "You have \(MacUpdater.currentVersion)", good: true)
    }

    /// The release notes, as GitHub's Markdown, folded away by default.
    @ViewBuilder private func notes(_ r: MacUpdater.Release) -> some View {
        if !r.notes.isEmpty {
            DisclosureGroup("What's new") {
                Text((try? AttributedString(markdown: r.notes,
                        options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(r.notes))
                    .font(.callout)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.top, 4)
            }
        }
    }

    // MARK: Phone app

    @ViewBuilder private var phoneUpdate: some View {
        let version = bridge.phoneVersion.isEmpty ? "Unknown until the phone links" : bridge.phoneVersion
        if let info = bridge.phoneUpdateInfo {
            StatusLine(icon: "iphone", title: "Bridge \(info.version) for the phone",
                       value: "It has \(version)", good: info.stage != "failed")
            switch info.stage {
            case "avail":
                HStack {
                    Spacer()
                    Button("Update Phone") { bridge.phoneUpdateCommand("install") }
                        .disabled(!bridge.bluetoothLinked)
                }
            case "dl", "paused":
                ProgressView(value: Double(info.percent ?? 0), total: 100)
                HStack {
                    Text(info.stage == "dl" ? "Downloading on the phone · \(info.percent ?? 0)%"
                                            : "Paused on the phone at \(info.percent ?? 0)%")
                        .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                    Spacer()
                    if info.stage == "dl" {
                        Button("Pause") { bridge.phoneUpdateCommand("pause") }
                    } else {
                        Button("Resume") { bridge.phoneUpdateCommand("install") }
                    }
                    Button("Cancel") { bridge.phoneUpdateCommand("cancel") }
                }
            case "verify":
                HStack { Text("The phone is checking the download…").foregroundStyle(.secondary); Spacer(); ProgressView().controlSize(.small) }
            case "perm":
                Text("The phone needs you to allow Bridge to install apps. Open Bridge on the phone: it shows the switch.")
                    .font(.caption).foregroundStyle(.orange)
            case "confirm":
                Text("Confirm the install on the phone.").font(.caption).foregroundStyle(.secondary)
            case "installing":
                HStack { Text("Installing on the phone. It reconnects in a moment.").foregroundStyle(.secondary); Spacer(); ProgressView().controlSize(.small) }
            default:   // failed
                HStack {
                    Text("The update didn't install. Open Bridge on the phone to see why.")
                        .font(.caption).foregroundStyle(.orange)
                    Spacer()
                    Button("Try Again") { bridge.phoneUpdateCommand("install") }
                        .disabled(!bridge.bluetoothLinked)
                }
            }
        } else {
            StatusLine(icon: "iphone", title: "Bridge on the phone", value: version, good: !bridge.phoneVersion.isEmpty)
        }
    }
}

/// Settings › Phone › Health: everything the phone reports as wrong, each
/// with its fix, and how Bridge's debugging permission on it is doing.
struct PhoneHealthSection: View {
    @ObservedObject var bridge: BridgeController

    var body: some View {
        Section {
            if !bridge.bluetoothLinked {
                StatusLine(icon: "antenna.radiowaves.left.and.right.slash", title: "Phone health",
                           value: "Shown while the phone is nearby", good: false)
            } else if bridge.phoneProblems.isEmpty {
                StatusLine(icon: "checkmark.seal", title: "Phone health", value: "Everything works", good: true)
            } else {
                ForEach(bridge.phoneProblems) { problem in row(problem) }
            }
            if bridge.bluetoothLinked {
                HStack {
                    StatusLine(icon: "key", title: "Debugging permission", value: keyStatus, good: keyHealthy)
                    Button("Renew Now") { bridge.renewPhoneKey() }
                        .help("The phone uses its permission once, which restarts Android's expiry timer. Needs Wi-Fi on the phone.")
                }
            }
        } header: {
            Text("Health")
        } footer: {
            footer("Android forgets debugging permissions that go unused for a while (7 days unless changed). The phone renews Bridge's by itself on Wi-Fi it has used before, and Bridge tells you here and in a notification if something needs you.")
        }
    }

    private func row(_ p: PhoneIssue) -> some View {
        HStack(alignment: .top, spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 6, style: .continuous)
                    .fill((p.isError ? Color.red : Color.orange).opacity(0.18))
                    .frame(width: 24, height: 24)
                Image(systemName: p.isError ? "exclamationmark.triangle.fill" : "exclamationmark.circle")
                    .font(.system(size: 12, weight: .medium))
                    .foregroundStyle(p.isError ? Color.red : Color.orange)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(p.title)
                Text(p.detail).font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            switch p.action {
            case .setUpOverUSB:
                Button("Set Up Over USB…") { bridge.setUpOverUSB() }
                    .disabled(bridge.isBusy || bridge.isConnected)
            case .renew:
                Button("Renew Now") { bridge.renewPhoneKey() }
            case .onPhone:
                EmptyView()
            }
        }
    }

    private var keyHealthy: Bool {
        !bridge.phoneIssues.contains { $0 == "keylost" || $0.hasPrefix("keyexp") }
    }

    private var keyStatus: String {
        if bridge.phoneIssues.contains("keylost") { return "Lost: set up over USB" }
        let expires = bridge.phoneKeyExpiresAt
        if expires <= 0 { return "Doesn't expire" }
        let days = Int((expires - Date().timeIntervalSince1970) / 86_400)
        if days < 0 { return "May have expired" }
        return days < 1 ? "Lapses today unless renewed" : "Lapses in \(days) day\(days == 1 ? "" : "s") unless renewed"
    }
}
