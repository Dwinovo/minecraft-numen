import Foundation
import Testing
@testable import NumenBridge

@Suite("Bridge health response")
struct HealthResponseTests {
    @Test("uses stable wire keys")
    func usesStableWireKeys() throws {
        let value = HealthResponse(
            version: "0.1.0",
            apiVersion: 1,
            platform: "macos-arm64",
            permission: .notDetermined,
            providerReady: false
        )

        let data = try JSONEncoder().encode(value)
        let object = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
        #expect(object["version"] as? String == "0.1.0")
        #expect(object["api_version"] as? Int == 1)
        #expect(object["platform"] as? String == "macos-arm64")
        #expect(object["permission"] as? String == "not_determined")
        #expect(object["provider_ready"] as? Bool == false)
    }
}
