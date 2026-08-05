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

    let store: any CredentialStore

    func apiKey() throws -> String? {
        let value = try store.load(account: Self.dashScopeAccount)?.trimmingCharacters(in: .whitespacesAndNewlines)
        return value?.isEmpty == false ? value : nil
    }

    func isReady() throws -> Bool {
        try apiKey() != nil
    }

    func save(apiKey: String) throws {
        let value = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty {
            try store.delete(account: Self.dashScopeAccount)
        } else {
            try store.save(value, account: Self.dashScopeAccount)
        }
    }
}
