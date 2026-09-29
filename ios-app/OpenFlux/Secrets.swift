import Foundation
import Security

/// Per-profile encryption secrets, stored in the Keychain group shared by the
/// app and the tunnel extension.
///
/// The secret deliberately does NOT travel in the VPN `providerConfiguration`:
/// that dictionary is persisted with the system VPN profile, so a secret placed
/// there would sit on disk in the clear. The app writes the secret here, and the
/// extension reads it back by profile id — only the id crosses the process
/// boundary through the provider configuration.
///
/// Both targets carry `keychain-access-groups` with the group below; without it
/// each process would silently get its own keychain partition and the extension
/// would find nothing.
enum Secrets {
    /// Team-prefixed access group shared by app and extension. The team id is
    /// fixed for this app, so it is spelled out rather than read back from the
    /// entitlement at runtime.
    static let accessGroup = "8GQH8GQ252.com.p1neapplexpress-saharev.openflux"

    private static let service = "openflux.encryption"
    private static let captchaService = "openflux.captcha"
    private static let captchaAccount = "cookies"

    // MARK: - Captcha cookies
    //
    // Хранятся, а не передаются в живой транспорт, потому что капчу приходится
    // проходить с ВЫКЛЮЧЕННЫМ туннелем: пакеты в мёртвый туннель — чёрная дыра,
    // и страница капчи просто не грузится. Значит к моменту получения кук ядра
    // может не быть вовсе, и они должны дожить до следующего старта.
    // Keychain, а не UserDefaults: это, по сути, токен доступа.

    static func captchaCookies() -> String? {
        var q = captchaQuery()
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data, let s = String(data: data, encoding: .utf8), !s.isEmpty
        else { return nil }
        return s
    }

    @discardableResult
    static func setCaptchaCookies(_ header: String) -> Bool {
        let trimmed = header.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return SecItemDelete(captchaQuery() as CFDictionary) == errSecSuccess
        }
        guard let data = trimmed.data(using: .utf8) else { return false }
        let q = captchaQuery()
        if SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary) == errSecSuccess {
            return true
        }
        var add = q
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    // MARK: - Ключ прямого канала
    //
    // Отдельный слот: у докового профиля поле шифрования — это ключ САМИХ доков
    // (для узла classic он должен быть пустым), а прямой канал до узла требует
    // своего ключа. Один слот на оба назначения привёл бы к тому, что ключ
    // direct уехал бы в доковый транспорт и тот начал бы молча ронять пакеты.

    static func directKey(for profileID: UUID) -> String? {
        var q = slotQuery(directKeyService, profileID)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data, let s = String(data: data, encoding: .utf8), !s.isEmpty
        else { return nil }
        return s
    }

    @discardableResult
    static func setDirectKey(_ secret: String, for profileID: UUID) -> Bool {
        let trimmed = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        guard setTunnelKeys("", for: profileID, keySlot: "direct") else { return false }
        let q = slotQuery(directKeyService, profileID)
        guard !trimmed.isEmpty else {
            let st = SecItemDelete(q as CFDictionary)
            return st == errSecSuccess || st == errSecItemNotFound
        }
        guard let data = trimmed.data(using: .utf8) else { return false }
        if SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary) == errSecSuccess {
            return true
        }
        var add = q
        add[kSecValueData as String] = data
        add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    /// Distinguish an intentionally absent key from inaccessible Keychain data.
    /// A locked/failed read must not create an unencrypted VPN configuration.
    static func tunnelSecret(for profileID: UUID, keySlot: String) throws -> String {
        var q = keySlot == "direct" ? slotQuery(directKeyService, profileID) : baseQuery(profileID)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecItemNotFound { return "" }
        guard status == errSecSuccess,
              let data = out as? Data, let secret = String(data: data, encoding: .utf8) else {
            throw NSError(domain: "OpenFlux", code: 6, userInfo: [
                NSLocalizedDescriptionKey: "Не удалось прочитать ключ VPN. Разблокируй iPhone и повтори подключение."])
        }
        return secret.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Prepared scrypt output is as sensitive as the original secret. Keep the
    /// document and direct-key slots separate and available for locked reconnects.
    static func tunnelKeys(for profileID: UUID, keySlot: String) -> String? {
        let service = keySlot == "direct" ? "openflux.tunnelkeys.direct.v1" : "openflux.tunnelkeys.v1"
        var q = slotQuery(service, profileID)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess,
              let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    static func setTunnelKeys(_ keys: String, for profileID: UUID, keySlot: String) -> Bool {
        let service = keySlot == "direct" ? "openflux.tunnelkeys.direct.v1" : "openflux.tunnelkeys.v1"
        let q = slotQuery(service, profileID)
        if keys.isEmpty {
            let status = SecItemDelete(q as CFDictionary)
            return status == errSecSuccess || status == errSecItemNotFound
        }
        let attrs: [String: Any] = [
            kSecValueData as String: Data(keys.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(q as CFDictionary, attrs as CFDictionary)
        if status == errSecSuccess { return true }
        guard status == errSecItemNotFound else { return false }
        var add = q
        for (key, value) in attrs { add[key] = value }
        return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
    }

    private static let directKeyService = "openflux.directkey"

    private static func slotQuery(_ service: String, _ profileID: UUID) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: profileID.uuidString,
            kSecAttrAccessGroup as String: accessGroup,
        ]
    }

    private static func captchaQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: captchaService,
            kSecAttrAccount as String: captchaAccount,
            kSecAttrAccessGroup as String: accessGroup,
        ]
    }

    /// Reads the secret for a profile, or nil when none is stored.
    static func encryptionKey(for profileID: UUID) -> String? {
        var q = baseQuery(profileID)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne

        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        guard status == errSecSuccess,
              let data = out as? Data,
              let s = String(data: data, encoding: .utf8)
        else { return nil }
        return s
    }

    static func hasEncryptionKey(for profileID: UUID) -> Bool {
        encryptionKey(for: profileID) != nil
    }

    /// Stores (or, for an empty string, removes) a profile's secret.
    @discardableResult
    static func setEncryptionKey(_ secret: String, for profileID: UUID) -> Bool {
        let trimmed = secret.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return removeEncryptionKey(for: profileID) }
        guard setTunnelKeys("", for: profileID, keySlot: "") else { return false }
        guard let data = trimmed.data(using: .utf8) else { return false }

        let q = baseQuery(profileID)
        let attrs: [String: Any] = [kSecValueData as String: data]

        let status = SecItemUpdate(q as CFDictionary, attrs as CFDictionary)
        if status == errSecSuccess { return true }
        if status == errSecItemNotFound {
            var add = q
            add[kSecValueData as String] = data
            // The extension may need the secret while the device is locked (the
            // tunnel reconnects on its own after a network change), so this is
            // the after-first-unlock class rather than WhenUnlocked.
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            return SecItemAdd(add as CFDictionary, nil) == errSecSuccess
        }
        return false
    }

    @discardableResult
    static func removeEncryptionKey(for profileID: UUID) -> Bool {
        guard setTunnelKeys("", for: profileID, keySlot: "") else { return false }
        let status = SecItemDelete(baseQuery(profileID) as CFDictionary)
        return status == errSecSuccess || status == errSecItemNotFound
    }

    private static func baseQuery(_ profileID: UUID) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: profileID.uuidString,
            kSecAttrAccessGroup as String: accessGroup,
        ]
    }
}
