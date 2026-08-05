import Foundation
import Testing
@testable import NumenBridge

@Suite("Bridge discovery file")
struct DiscoveryFileTests {
    @Test("creates an owner-only stable token")
    func createsOwnerOnlyStableToken() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("bridge.json")

        let first = try DiscoveryFile(url: url, randomBytes: {
            Data(repeating: 7, count: 32)
        }).loadOrCreate()
        let second = try DiscoveryFile(url: url, randomBytes: {
            Data(repeating: 9, count: 32)
        }).loadOrCreate()

        #expect(first == second)
        #expect(first.apiVersion == 1)
        #expect(first.baseURL == "http://127.0.0.1:38471")
        #expect(first.token == Data(repeating: 7, count: 32).base64URLEncodedString())
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        #expect((attributes[.posixPermissions] as? NSNumber)?.intValue == 0o600)
    }
}
