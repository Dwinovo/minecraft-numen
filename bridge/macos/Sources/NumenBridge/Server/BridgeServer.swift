import Foundation
import Hummingbird
import HummingbirdWebSocket

enum BridgeServer {
    static let host = "127.0.0.1"
    static let port = 38_471

    static func run(state: BridgeState? = nil) async throws {
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
            let payload = try await request.decode(as: SpeechRequest.self, context: context)
            guard payload.responseFormat == nil || payload.responseFormat == "wav" else {
                throw HTTPError(.badRequest, message: "Only WAV speech output is supported")
            }
            let wave: Data
            switch BridgePreferences.provider() {
            case .dashscope:
                guard let apiKey = try credentials.apiKey() else {
                    throw HTTPError(.serviceUnavailable, message: "DashScope API key is not configured")
                }
                let client = DashScopeTtsClient(
                    apiKey: apiKey,
                    model: payload.model ?? BridgePreferences.ttsModel(),
                    voice: payload.voice ?? BridgePreferences.voice()
                )
                wave = try await client.synthesize(payload.input)
            case .custom:
                guard let apiKey = try credentials.customApiKey() else {
                    throw HTTPError(.serviceUnavailable, message: "Custom API key is not configured")
                }
                let baseURL = BridgePreferences.customBaseURL()
                guard !baseURL.isEmpty else {
                    throw HTTPError(.serviceUnavailable, message: "Custom base URL is not configured")
                }
                let model = payload.model ?? BridgePreferences.customTtsModel()
                guard !model.isEmpty else {
                    throw HTTPError(.badRequest, message: "Custom TTS model is not configured")
                }
                let client = OpenAiTtsClient(
                    baseURL: baseURL,
                    apiKey: apiKey,
                    model: model,
                    voice: payload.voice ?? BridgePreferences.customVoice()
                )
                wave = try await client.synthesize(payload.input)
            }
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
            let buffer = try await request.body.collect(upTo: context.maxUploadSize)
            let wave = Data(buffer.readableBytesView)
            let text: String
            switch BridgePreferences.provider() {
            case .dashscope:
                guard let apiKey = try credentials.apiKey() else {
                    throw HTTPError(.serviceUnavailable, message: "DashScope API key is not configured")
                }
                let pcm = try WavePcmDecoder.decode(wave)
                text = try await DashScopeSttClient(
                    apiKey: apiKey,
                    model: BridgePreferences.sttModel()
                ).transcribe(pcm: pcm)
            case .custom:
                guard let apiKey = try credentials.customApiKey() else {
                    throw HTTPError(.serviceUnavailable, message: "Custom API key is not configured")
                }
                let baseURL = BridgePreferences.customBaseURL()
                let model = BridgePreferences.customSttModel()
                guard !baseURL.isEmpty, !model.isEmpty else {
                    throw HTTPError(.serviceUnavailable,
                                     message: "Custom base URL / STT model is not configured")
                }
                text = try await OpenAiSttClient(
                    baseURL: baseURL, apiKey: apiKey, model: model
                ).transcribe(wav: wave)
            }
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
            let provider: any StreamingTranscriptionSession
            switch BridgePreferences.provider() {
            case .dashscope:
                guard let apiKey = try credentials.apiKey() else { return }
                provider = DashScopeSttClient(
                    apiKey: apiKey,
                    model: BridgePreferences.sttModel()
                ).open()
            case .custom:
                guard let apiKey = try credentials.customApiKey() else { return }
                let baseURL = BridgePreferences.customBaseURL()
                let model = BridgePreferences.customSttModel()
                guard !baseURL.isEmpty, !model.isEmpty else { return }
                provider = OpenAiSttClient(
                    baseURL: baseURL, apiKey: apiKey, model: model
                ).open()
            }
            let controller = CaptureSocketController(
                capture: microphoneCapture,
                provider: provider,
                send: { event in
                    if let state {
                        switch event {
                        case .started:
                            await state.captureStarted()
                        case .done, .error:
                            await state.captureEnded()
                        case .delta:
                            break
                        }
                    }
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
            if let state { await state.captureEnded() }
        }
        let application = Hummingbird.Application(
            router: router,
            server: .http1WebSocketUpgrade(webSocketRouter: webSocketRouter),
            configuration: .init(address: .hostname(host, port: port))
        )
        if let state { await state.serverStarted() }
        try await application.runService()
    }
}
