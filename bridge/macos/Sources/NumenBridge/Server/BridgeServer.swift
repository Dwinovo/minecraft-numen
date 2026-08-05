import Hummingbird

enum BridgeServer {
    static let host = "127.0.0.1"
    static let port = 38_471

    static func run() async throws {
        let router = Router()
        router.get("v1/health") { _, _ in
            HealthResponse(
                version: "0.1.0",
                apiVersion: 1,
                platform: "macos-arm64",
                permission: .notDetermined,
                providerReady: false
            )
        }
        let application = Hummingbird.Application(
            router: router,
            configuration: .init(address: .hostname(host, port: port))
        )
        try await application.runService()
    }
}
