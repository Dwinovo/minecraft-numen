import Testing
@testable import NumenBridge

@Suite("Loopback bearer authorization")
struct BearerTokenAuthorizerTests {
    @Test("requires an exact bearer token")
    func requiresExactToken() {
        let authorizer = BearerTokenAuthorizer(token: "secret-token")

        #expect(authorizer.allows(headerValue: "Bearer secret-token"))
        #expect(!authorizer.allows(headerValue: nil))
        #expect(!authorizer.allows(headerValue: "Bearer wrong"))
        #expect(!authorizer.allows(headerValue: "bearer secret-token"))
        #expect(!authorizer.allows(headerValue: "Bearer secret-token "))
    }
}
