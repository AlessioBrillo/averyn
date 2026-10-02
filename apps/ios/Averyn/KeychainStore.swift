@preconcurrency import AppAuth
import Foundation
import Security

/// The OIDC session (refresh token included) as one Keychain item. `AfterFirstUnlock` so the background sync
/// task can still read it while the phone is locked; the item never leaves this device (no iCloud sync).
enum KeychainStore {
    private static let service = "dev.averyn.app.auth"
    private static let account = "oidc-session"

    private static var query: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    static func saveAuthState(_ state: OIDAuthState) {
        guard let data = try? NSKeyedArchiver.archivedData(withRootObject: state, requiringSecureCoding: true) else { return }
        SecItemDelete(query as CFDictionary)
        var item = query
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(item as CFDictionary, nil)
    }

    /// Nil if nothing is stored or it cannot be read: the user simply signs in again.
    static func loadAuthState() -> OIDAuthState? {
        var request = query
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: OIDAuthState.self, from: data)
    }

    static func deleteAuthState() {
        SecItemDelete(query as CFDictionary)
    }
}
