import Hummingbird

enum BridgeServer {
    static let host = "127.0.0.1"
    static let port = 38_471

    static func run() async throws {
        let discovery = try DiscoveryFile().loadOrCreate()
        let authorizer = BearerTokenAuthorizer(token: discovery.token)
        let credentials = ProviderCredentials(store: KeychainStore())
        let router = Router()
        router.get("v1/health") { request, _ in
            guard authorizer.allows(headerValue: request.headers[.authorization]) else {
                throw HTTPError(.unauthorized, message: "Invalid local bridge token")
            }
            return HealthResponse(
                version: "0.1.0",
                apiVersion: 1,
                platform: "macos-arm64",
                permission: .notDetermined,
                providerReady: (try? credentials.isReady()) == true
            )
        }
        let application = Hummingbird.Application(
            router: router,
            configuration: .init(address: .hostname(host, port: port))
        )
        try await application.runService()
    }
}
