import Foundation
import Security

/// The two secrets that reach the phone, kept in the login keychain rather
/// than UserDefaults: a plist is readable by any process running as you,
/// while the keychain hands an item only to the app that stored it (and asks
/// you first if another one tries).
///
///  - `ticket`: the phone's iroh address. Anyone with it can knock on the
///    phone's tunnel; with the pairing secret as well they can walk in.
///  - `pairSecret`: proves this Mac is yours. Sent as the first line of every
///    tunnel stream and used in the Bluetooth handshake.
///
/// Both live in **one** keychain item (a small JSON blob), because macOS asks
/// for permission per item: two items meant two "Bridge wants to use your
/// keychain" dialogs after every rebuild.
enum Keychain {
    private static let service = "com.bonevane.bridge.mac"
    private static let account = "credentials"

    /// Read once per launch; writes go straight back to the keychain.
    private static var cache: [String: String] = load()

    static func get(_ key: String) -> String? {
        let value = cache[key] ?? ""
        return value.isEmpty ? nil : value
    }

    static func set(_ key: String, _ value: String) {
        if (cache[key] ?? "") == value { return }
        cache[key] = value
        store(cache)
    }

    // MARK: - Storage

    private static func load() -> [String: String] {
        if let data = read(account: account),
           let dict = try? JSONSerialization.jsonObject(with: data) as? [String: String] {
            return dict
        }
        // Earlier builds kept one item per key; fold them in and remove them.
        var migrated: [String: String] = [:]
        for key in ["ticket", "pairSecret"] {
            if let data = read(account: key), !data.isEmpty {
                migrated[key] = String(decoding: data, as: UTF8.self)
                SecItemDelete(query(account: key) as CFDictionary)
            }
        }
        if !migrated.isEmpty { store(migrated) }
        return migrated
    }

    private static func store(_ dict: [String: String]) {
        let base = query(account: account)
        let live = dict.filter { !$0.value.isEmpty }
        if live.isEmpty {
            SecItemDelete(base as CFDictionary)
            return
        }
        guard let data = try? JSONSerialization.data(withJSONObject: live) else { return }
        let status = SecItemUpdate(base as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = base
            add[kSecValueData as String] = data
            // Available once the Mac has been unlocked since boot, which is the
            // earliest a launch-at-login menu-bar app can want it.
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    private static func read(account: String) -> Data? {
        var q = query(account: account)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &item) == errSecSuccess else { return nil }
        return item as? Data
    }

    private static func query(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }
}
