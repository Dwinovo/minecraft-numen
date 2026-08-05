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
        router.post("v1/audio/speech") { request, context -> Response in
            guard authorizer.allows(headerValue: request.headers[.authorization]) else {
                throw HTTPError(.unauthorized, message: "Invalid local bridge token")
            }
            guard let apiKey = try credentials.apiKey() else {
                throw HTTPError(.serviceUnavailable, message: "DashScope API key is not configured")
            }
            let payload = try await request.decode(as: SpeechRequest.self, context: context)
            guard payload.responseFormat == nil || payload.responseFormat == "wav" else {
                throw HTTPError(.badRequest, message: "Only WAV speech output is supported")
            }
            let client = DashScopeTtsClient(
                apiKey: apiKey,
                model: payload.model ?? DashScopeTtsClient.defaultModel,
                voice: payload.voice ?? DashScopeTtsClient.defaultVoice
            )
            let wave = try await client.synthesize(payload.input)
            return Response(
                status: .ok,
                headers: [.contentType: "audio/wav"],
                body: .init(byteBuffer: .init(bytes: wave))
            )
        }
        let application = Hummingbird.Application(
            router: router,
            configuration: .init(address: .hostname(host, port: port))
        )
        try await application.runService()
    }
}
