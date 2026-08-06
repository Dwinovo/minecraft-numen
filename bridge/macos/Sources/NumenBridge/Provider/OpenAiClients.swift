import Foundation

/// OpenAI 兼容端点的 URL 拼接：补 scheme、去尾斜杠、按需补路径后半段。
/// 与 Java 侧 {@code WhisperHttpStt.composeUrl} 同规则，用户可填到域名、
/// /v1 或完整路径三档。
enum OpenAiEndpoints {
    static func compose(_ base: String, path: String) throws -> URL {
        var b = base.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !b.isEmpty else { throw OpenAiError.missingBaseURL }
        if !b.contains("://") { b = "https://" + b }
        if b.hasSuffix("/") { b.removeLast() }
        if b.hasSuffix(path) { return try require(URL(string: b)) }
        if b.hasSuffix("/v1") { return try require(URL(string: b + path)) }
        return try require(URL(string: b + "/v1" + path))
    }

    private static func require(_ url: URL?) throws -> URL {
        guard let url else { throw OpenAiError.invalidURL }
        return url
    }
}

enum OpenAiError: Error, Equatable {
    case missingBaseURL
    case invalidURL
    case http(Int, String)
    case invalidResponse
    case emptyTranscript
}

/// 可注入的传输层，测试用假实现替换 URLSession。
protocol HttpDataTransport: Sendable {
    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse)
}

struct URLSessionTransport: HttpDataTransport {
    private static let shared: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 60
        return URLSession(configuration: config)
    }()

    let session: URLSession

    init(session: URLSession = URLSessionTransport.shared) {
        self.session = session
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw OpenAiError.invalidResponse }
        return (data, http)
    }
}

/// OpenAI 兼容 TTS：POST {base}/v1/audio/speech，要求 WAV 返回。
struct OpenAiTtsClient: SpeechSynthesizing {
    let baseURL: String
    let apiKey: String
    let model: String
    let voice: String
    let transport: any HttpDataTransport

    init(
        baseURL: String,
        apiKey: String,
        model: String,
        voice: String,
        transport: any HttpDataTransport = URLSessionTransport()
    ) {
        self.baseURL = baseURL
        self.apiKey = apiKey
        self.model = model
        self.voice = voice
        self.transport = transport
    }

    func synthesize(_ text: String) async throws -> Data {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw TtsError.emptyText
        }
        let url = try OpenAiEndpoints.compose(baseURL, path: "/audio/speech")
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(SpeechPayload(
            model: model, input: text, voice: voice, responseFormat: "wav"
        ))
        let (data, response) = try await transport.data(for: request)
        guard (200..<300).contains(response.statusCode) else {
            throw OpenAiError.http(response.statusCode, Self.brief(data))
        }
        // 上游可能无视 response_format 回 MP3——非 WAV 没法交给游戏播放，当场拒掉。
        guard data.starts(with: [0x52, 0x49, 0x46, 0x46]) else {
            throw OpenAiError.invalidResponse
        }
        return data
    }

    private struct SpeechPayload: Encodable {
        let model: String
        let input: String
        let voice: String
        let responseFormat: String

        enum CodingKeys: String, CodingKey {
            case model, input, voice
            case responseFormat = "response_format"
        }
    }

    private static func brief(_ data: Data) -> String {
        let text = String(decoding: data.prefix(300), as: UTF8.self)
        return String(text.prefix(300))
    }
}

/// OpenAI 兼容 STT：POST {base}/v1/audio/transcriptions（multipart WAV 上传）。
struct OpenAiSttClient: Sendable {
    let baseURL: String
    let apiKey: String
    let model: String
    let transport: any HttpDataTransport

    init(
        baseURL: String,
        apiKey: String,
        model: String,
        transport: any HttpDataTransport = URLSessionTransport()
    ) {
        self.baseURL = baseURL
        self.apiKey = apiKey
        self.model = model
        self.transport = transport
    }

    func transcribe(wav: Data) async throws -> String {
        let url = try OpenAiEndpoints.compose(baseURL, path: "/audio/transcriptions")
        let boundary = "----numenbridge\(UInt64.random(in: 0..<UInt64.max))"
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.httpBody = multipartBody(boundary: boundary, wav: wav)
        let (data, response) = try await transport.data(for: request)
        guard (200..<300).contains(response.statusCode) else {
            throw OpenAiError.http(response.statusCode, String(decoding: data.prefix(300), as: UTF8.self))
        }
        let text = try parseText(data)
        guard !text.isEmpty else { throw OpenAiError.emptyTranscript }
        return text
    }

    /// 采集 WebSocket 用的是流式协议，这里把 REST 批量转写适配成一次性 .final 的会话：
    /// 缓冲 PCM，finish 时打 WAV 上传。没有 partial——批量后端天生没有。
    func open() -> any StreamingTranscriptionSession {
        OpenAiBatchSttSession(client: self)
    }

    private func multipartBody(boundary: String, wav: Data) -> Data {
        var body = Data()
        func field(_ name: String, _ value: String) {
            body.append(Data("--\(boundary)\r\n".utf8))
            body.append(Data("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n".utf8))
            body.append(Data("\(value)\r\n".utf8))
        }
        field("model", model)
        body.append(Data("--\(boundary)\r\n".utf8))
        body.append(Data("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".utf8))
        body.append(Data("Content-Type: audio/wav\r\n\r\n".utf8))
        body.append(wav)
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))
        return body
    }

    private func parseText(_ data: Data) throws -> String {
        struct Payload: Decodable { let text: String? }
        let decoded = try? JSONDecoder().decode(Payload.self, from: data)
        return decoded?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }
}

/// 批量转写的流式会话适配（见 {@link OpenAiSttClient.open()}）。
private final class OpenAiBatchSttSession: StreamingTranscriptionSession, @unchecked Sendable {
    private let client: OpenAiSttClient
    private let lock = NSLock()
    private var pcm = Data()
    private var handler: (@Sendable (TranscriptionEvent) async -> Void)?
    private var terminal = false

    init(client: OpenAiSttClient) {
        self.client = client
    }

    func setEventHandler(_ handler: (@Sendable (TranscriptionEvent) async -> Void)?) {
        lock.withLock { self.handler = handler }
    }

    func append(_ chunk: Data) {
        lock.withLock {
            guard !terminal else { return }
            pcm.append(chunk)
        }
    }

    func finish() {
        let (data, currentHandler) = lock.withLock { () -> (Data, (@Sendable (TranscriptionEvent) async -> Void)?) in
            guard !terminal else { return (Data(), nil) }
            terminal = true
            return (pcm, handler)
        }
        guard let handler = currentHandler else { return }
        let client = client
        Task {
            do {
                guard !data.isEmpty else { throw OpenAiError.emptyTranscript }
                let wav = try PcmWaveEncoder.encode(
                    pcm: data, sampleRate: Int(Pcm16Converter.sampleRate)
                )
                let text = try await client.transcribe(wav: wav)
                await handler(.final(text))
            } catch let error as OpenAiError {
                await handler(.failure(String(describing: error)))
            } catch {
                await handler(.failure(String(describing: OpenAiError.http(-1, String(describing: error)))))
            }
        }
    }

    func cancel() {
        lock.withLock {
            terminal = true
            pcm = Data()
            handler = nil
        }
    }
}
