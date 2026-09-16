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
enum Keychain {
    private static let service = "com.bonevane.bridge.mac"

    static func get(_ key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    static func set(_ key: String, _ value: String) {
        let base: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
        if value.isEmpty {
            SecItemDelete(base as CFDictionary)
            return
        }
        let data = Data(value.utf8)
        let update: [String: Any] = [kSecValueData as String: data]
        let status = SecItemUpdate(base as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            var add = base
            add[kSecValueData as String] = data
            // Available once the Mac has been unlocked since boot, which is the
            // earliest a launch-at-login menu-bar app can want it.
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(add as CFDictionary, nil)
        }
    }
}
