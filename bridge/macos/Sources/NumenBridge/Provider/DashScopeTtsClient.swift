@preconcurrency import Foundation

struct DashScopeTtsClient: SpeechSynthesizing, Sendable {
    static let defaultModel = "qwen3-tts-flash-realtime"
    static let defaultVoice = "Cherry"
    static let sampleRate = 24_000

    let apiKey: String
    let model: String
    let voice: String
    let connector: any TextWebSocketConnecting

    init(
        apiKey: String,
        model: String = defaultModel,
        voice: String = defaultVoice,
        connector: any TextWebSocketConnecting = URLSessionTextWebSocketConnector()
    ) {
        self.apiKey = apiKey
        self.model = model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Self.defaultModel : model
        self.voice = voice.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Self.defaultVoice : voice
        self.connector = connector
    }

    func synthesize(_ text: String) async throws -> Data {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw TtsError.emptyText
        }
        guard var components = URLComponents(string: "wss://dashscope.aliyuncs.com/api-ws/v1/realtime") else {
            throw TtsError.invalidURL
        }
        components.queryItems = [URLQueryItem(name: "model", value: model)]
        guard let url = components.url else { throw TtsError.invalidURL }
        let socket = try await connector.connect(url: url, authorization: "Bearer \(apiKey)")
        do {
            for message in try Self.buildRequestMessages(text: text, voice: voice) {
                try await socket.send(text: message)
            }
            var accumulator = TtsResponseAccumulator(sampleRate: Self.sampleRate)
            while true {
                let event = try await socket.receiveText()
                if let wave = try accumulator.receive(event) {
                    await socket.close()
                    return wave
                }
            }
        } catch {
            await socket.close()
            if error is TtsError { throw error }
            throw TtsError.transport(String(describing: error))
        }
    }

    static func buildRequestMessages(text: String, voice: String) throws -> [String] {
        let encoder = JSONEncoder()
        let update = SessionUpdate(
            type: "session.update",
            session: .init(
                mode: "server_commit",
                voice: voice,
                responseFormat: "pcm",
                sampleRate: sampleRate
            )
        )
        return try [
            String(decoding: encoder.encode(update), as: UTF8.self),
            String(decoding: encoder.encode(TextAppend(type: "input_text_buffer.append", text: text)), as: UTF8.self),
            String(decoding: encoder.encode(EventType(type: "input_text_buffer.commit")), as: UTF8.self)
        ]
    }
}

struct TtsResponseAccumulator: Sendable {
    let sampleRate: Int
    private var pcm = Data()

    init(sampleRate: Int) {
        self.sampleRate = sampleRate
    }

    mutating func receive(_ text: String) throws -> Data? {
        let event: ProviderEvent
        do {
            event = try JSONDecoder().decode(ProviderEvent.self, from: Data(text.utf8))
        } catch {
            throw TtsError.invalidEvent
        }
        switch event.type {
        case "response.audio.delta":
            guard let delta = event.delta, let bytes = Data(base64Encoded: delta) else {
                throw TtsError.invalidBase64
            }
            pcm.append(bytes)
            return nil
        case "response.done":
            return try PcmWaveEncoder.encode(pcm: pcm, sampleRate: sampleRate)
        case "error":
            throw TtsError.provider(event.error?.message ?? "DashScope TTS error")
        default:
            return nil
        }
    }

    func connectionClosed() throws -> Never {
        throw TtsError.connectionClosed
    }
}

enum TtsError: Error, Equatable {
    case emptyText
    case emptyAudio
    case invalidPCM
    case invalidBase64
    case invalidEvent
    case invalidURL
    case connectionClosed
    case provider(String)
    case transport(String)
}

private struct SessionUpdate: Encodable {
    let type: String
    let session: Session

    struct Session: Encodable {
        let mode: String
        let voice: String
        let responseFormat: String
        let sampleRate: Int

        enum CodingKeys: String, CodingKey {
            case mode, voice
            case responseFormat = "response_format"
            case sampleRate = "sample_rate"
        }
    }
}

private struct TextAppend: Encodable {
    let type: String
    let text: String
}

private struct EventType: Encodable {
    let type: String
}

private struct ProviderEvent: Decodable {
    let type: String
    let delta: String?
    let error: ProviderError?
}

private struct ProviderError: Decodable {
    let message: String?
}

struct URLSessionTextWebSocketConnector: TextWebSocketConnecting {
    func connect(url: URL, authorization: String) async throws -> any TextWebSocket {
        var request = URLRequest(url: url)
        request.setValue(authorization, forHTTPHeaderField: "Authorization")
        let task = URLSession.shared.webSocketTask(with: request)
        task.resume()
        return URLSessionTextWebSocket(task: task)
    }
}

private final class URLSessionTextWebSocket: TextWebSocket, @unchecked Sendable {
    private let task: URLSessionWebSocketTask

    init(task: URLSessionWebSocketTask) {
        self.task = task
    }

    func send(text: String) async throws {
        try await task.send(.string(text))
    }

    func receiveText() async throws -> String {
        switch try await task.receive() {
        case .string(let text): return text
        case .data: throw TtsError.invalidEvent
        @unknown default: throw TtsError.invalidEvent
        }
    }

    func close() async {
        task.cancel(with: .normalClosure, reason: nil)
    }
}
