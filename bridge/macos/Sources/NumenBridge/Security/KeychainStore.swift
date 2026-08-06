import Foundation
import Security

protocol CredentialStore: Sendable {
    func load(account: String) throws -> String?
    func save(_ value: String, account: String) throws
    func delete(account: String) throws
}

struct KeychainStore: CredentialStore {
    let service: String

    init(service: String = "com.dwinovo.numen.bridge") {
        self.service = service
    }

    func load(account: String) throws -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound {
            return nil
        }
        guard status == errSecSuccess, let data = result as? Data,
              let value = String(data: data, encoding: .utf8) else {
            throw KeychainError.status(status)
        }
        return value
    }

    func save(_ value: String, account: String) throws {
        try delete(account: account)
        var query = baseQuery(account: account)
        query[kSecValueData as String] = Data(value.utf8)
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KeychainError.status(status)
        }
    }

    func delete(account: String) throws {
        let status = SecItemDelete(baseQuery(account: account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.status(status)
        }
    }

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}

enum KeychainError: Error, Equatable {
    case status(OSStatus)
}

struct ProviderCredentials: Sendable {
    static let dashScopeAccount = "dashscope-api-key"
    static let customAccount = "custom-api-key"

    let store: any CredentialStore

    func apiKey() throws -> String? {
        let value = try store.load(account: Self.dashScopeAccount)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return value?.isEmpty == false ? value : nil
    }

    func customApiKey() throws -> String? {
        let value = try store.load(account: Self.customAccount)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return value?.isEmpty == false ? value : nil
    }

    /// 就绪与否取决于当前选中的 provider：DashScope 要它的 key；
    /// Custom 要 key + Base URL 都有——缺 Base URL 时请求注定打错地方，不如当场拦下。
    func isReady() throws -> Bool {
        switch BridgePreferences.provider() {
        case .dashscope:
            return try apiKey() != nil
        case .custom:
            return try customApiKey() != nil && !BridgePreferences.customBaseURL().isEmpty
        }
    }

    func save(apiKey: String) throws {
        try save(apiKey, account: Self.dashScopeAccount)
    }

    func save(customApiKey: String) throws {
        try save(customApiKey, account: Self.customAccount)
    }

    private func save(_ apiKey: String, account: String) throws {
        let value = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty {
            try store.delete(account: account)
        } else {
            try store.save(value, account: account)
        }
    }
}
