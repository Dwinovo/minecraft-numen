import Foundation
import Hummingbird
import HummingbirdWebSocket

enum BridgeServer {
    static let host = "127.0.0.1"
    static let port = 38_471

    static func run() async throws {
        let discovery = try DiscoveryFile().loadOrCreate()
        let tokenAuthorizer = BearerTokenAuthorizer(token: discovery.token)
        let credentials = ProviderCredentials(store: KeychainStore())
        let microphoneAuthorizer = AVFoundationMicrophoneAuthorizer()
        let microphoneCapture = MicrophoneCapture(
            authorizer: microphoneAuthorizer,
            engine: AVAudioEngineCaptureEngine()
        )
        let router = Router()
        router.get("v1/health") { request, _ in
            guard tokenAuthorizer.allows(headerValue: request.headers[.authorization]) else {
                throw HTTPError(.unauthorized, message: "Invalid local bridge token")
            }
            return HealthResponse(
                version: "0.1.0",
                apiVersion: 1,
                platform: "macos-arm64",
                permission: microphoneAuthorizer.status(),
                providerReady: (try? credentials.isReady()) == true
            )
        }
        router.post("v1/audio/speech") { request, context -> Response in
            guard tokenAuthorizer.allows(headerValue: request.headers[.authorization]) else {
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
        router.post("v1/audio/transcriptions") { request, context -> TranscriptionResponse in
            guard tokenAuthorizer.allows(headerValue: request.headers[.authorization]) else {
                throw HTTPError(.unauthorized, message: "Invalid local bridge token")
            }
            guard let apiKey = try credentials.apiKey() else {
                throw HTTPError(.serviceUnavailable, message: "DashScope API key is not configured")
            }
            let buffer = try await request.body.collect(upTo: context.maxUploadSize)
            let wave = Data(buffer.readableBytesView)
            let pcm = try WavePcmDecoder.decode(wave)
            let text = try await DashScopeSttClient(apiKey: apiKey).transcribe(pcm: pcm)
            return TranscriptionResponse(text: text)
        }
        let webSocketRouter = Router(context: BasicWebSocketRequestContext.self)
        webSocketRouter.ws("v1/audio/capture") { request, _ in
            guard tokenAuthorizer.allows(headerValue: request.headers[.authorization]),
                  (try? credentials.isReady()) == true else {
                return .dontUpgrade
            }
            return .upgrade()
        } onUpgrade: { inbound, outbound, _ in
            guard let apiKey = try credentials.apiKey() else { return }
            let provider = DashScopeSttClient(apiKey: apiKey).open()
            let controller = CaptureSocketController(
                capture: microphoneCapture,
                provider: provider,
                send: { event in
                    guard let text = try? event.wireText() else { return }
                    try? await outbound.write(.text(text))
                }
            )
            do {
                for try await message in inbound.messages(maxSize: 64 * 1_024) {
                    guard case .text(let text) = message else {
                        try? await outbound.write(.text(
                            try CaptureEvent.error(
                                code: "invalid_message",
                                message: "Only text commands are supported"
                            ).wireText()
                        ))
                        continue
                    }
                    do {
                        try await controller.receive(text)
                    } catch {
                        try? await outbound.write(.text(
                            try CaptureEvent.error(
                                code: "capture_failed",
                                message: String(describing: error)
                            ).wireText()
                        ))
                    }
                }
            } catch {
                // The cleanup below handles both normal and abnormal disconnects.
            }
            await controller.disconnect()
        }
        let application = Hummingbird.Application(
            router: router,
            server: .http1WebSocketUpgrade(webSocketRouter: webSocketRouter),
            configuration: .init(address: .hostname(host, port: port))
        )
        try await application.runService()
    }
}
