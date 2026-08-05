import Foundation
import Testing
@testable import NumenBridge

@Suite("Bridge provider credentials")
struct KeychainStoreTests {
    @Test("empty keys are not ready and saved keys can be removed")
    func managesReadiness() throws {
        let store = MemoryCredentialStore()
        let credentials = ProviderCredentials(store: store)

        #expect(try credentials.isReady() == false)
        try credentials.save(apiKey: "  sk-test  ")
        #expect(try credentials.apiKey() == "sk-test")
        #expect(try credentials.isReady() == true)
        try credentials.save(apiKey: "   ")
        #expect(try credentials.apiKey() == nil)
        #expect(try credentials.isReady() == false)
    }
}

private final class MemoryCredentialStore: CredentialStore, @unchecked Sendable {
    private var values: [String: String] = [:]
    private let lock = NSLock()

    func load(account: String) throws -> String? {
        lock.withLock { values[account] }
    }

    func save(_ value: String, account: String) throws {
        lock.withLock { values[account] = value }
    }

    func delete(account: String) throws {
        _ = lock.withLock { values.removeValue(forKey: account) }
    }
}
