import Foundation

/// One problem the phone reports ("issues=keylost,battery" in its status),
/// worded for this Mac. The phone sends codes, not sentences: Bluetooth is
/// slow, and the wording belongs to whoever shows it.
struct PhoneIssue: Identifiable {
    enum Action { case setUpOverUSB, renew, onPhone }

    /// The code without its argument ("keyexp" from "keyexp:2").
    let kind: String
    let title: String
    let detail: String
    let isError: Bool
    let action: Action
    var id: String { kind }

    /// Worth a notification, not just a line in the menu: it stops something
    /// working, or is about to.
    var worthNotifying: Bool { isError || kind == "keyexp" || kind == "keycheck" }

    init?(code: String) {
        let parts = code.split(separator: ":").map(String.init)
        kind = parts.first ?? ""
        let arg = parts.count > 1 ? Int(parts[1]) : nil
        switch kind {
        case "keylost":
            title = "The phone lost Bridge's debugging permission"
            detail = "Mirroring and Phone Files can't start. Plug the phone in and use Set Up Over USB, then tap Always allow on the phone."
            isError = true; action = .setUpOverUSB
        case "nogrant":
            title = "The phone can't switch USB debugging"
            detail = "Bridge's permission on the phone is gone, usually after reinstalling it. Plug the phone in and use Set Up Over USB."
            isError = true; action = .setUpOverUSB
        case "keyexp":
            // -1: probably lapsed already; 0: today; N: in N+1 days.
            if let days = arg, days > 0 {
                title = "Bridge's permission on the phone expires in \(days + 1) days"
            } else if let days = arg, days < 0 {
                title = "Bridge's permission on the phone may have expired"
            } else {
                title = "Bridge's permission on the phone expires today"
            }
            detail = "Android forgets permissions that go unused. The phone renews it by itself on Wi-Fi it knows; Renew Now does it straight away."
            isError = false; action = .renew
        case "keycheck":
            title = "The phone needs to check Bridge's permission"
            detail = "Android forgets debugging permissions that go unused. With the phone on its usual Wi-Fi, click Renew Now (or tap Check now on the phone); after that it keeps it fresh by itself."
            isError = false; action = .renew
        case "nolistener":
            title = "The phone isn't sending notifications"
            detail = "Notification access is on, but Android isn't running Bridge's listener. Open Bridge on the phone and tap Fix (on Xiaomi, turn on Autostart)."
            isError = true; action = .onPhone
        case "notifoff":
            title = "Notification access is off on the phone"
            detail = "Allow it in Bridge on the phone for notifications to appear here."
            isError = false; action = .onPhone
        case "autostart":
            title = "Autostart is off on the phone"
            detail = "Xiaomi phones stop Bridge in the background without it. Turn it on from Bridge on the phone."
            isError = false; action = .onPhone
        case "battery":
            title = "Battery use is restricted on the phone"
            detail = "Android may pause Bridge while the screen is off, which drops the link. Allow it from Bridge on the phone."
            isError = false; action = .onPhone
        default:
            return nil       // a newer phone app's problem this Mac can't word yet
        }
    }
}
