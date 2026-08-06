import Foundation
import Testing
@testable import NumenBridge

private final class FakeHttpTransport: HttpDataTransport, @unchecked Sendable {
    var lastRequest: URLRequest?
    var responseBody: Data
    var statusCode: Int
    var error: Error?

    init(body: Data = Data(), status: Int = 200, error: Error? = nil) {
        self.responseBody = body
        self.statusCode = status
        self.error = error
    }

    func data(for request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        lastRequest = request
        if let error { throw error }
        let response = HTTPURLResponse(
            url: request.url ?? URL(string: "https://example.invalid")!,
            statusCode: statusCode,
            httpVersion: "HTTP/1.1",
            headerFields: nil
        )!
        return (responseBody, response)
    }
}

private func minimalWav() -> Data {
    Data([0x52, 0x49, 0x46, 0x46]) + Data(repeating: 0, count: 40)
}

@Suite("OpenAI-compatible endpoints")
struct OpenAiEndpointsTests {
    @Test("composes URLs from domain, /v1, or full-path bases")
    func composesURLs() throws {
        let path = "/audio/speech"
        #expect(try OpenAiEndpoints.compose("https://api.example.com", path: path).absoluteString
            == "https://api.example.com/v1/audio/speech")
        #expect(try OpenAiEndpoints.compose("https://api.example.com/v1/", path: path).absoluteString
            == "https://api.example.com/v1/audio/speech")
        #expect(try OpenAiEndpoints.compose("https://api.example.com/v1/audio/speech", path: path).absoluteString
            == "https://api.example.com/v1/audio/speech")
        // 无 scheme 时默认 https
        #expect(try OpenAiEndpoints.compose("api.example.com", path: path).absoluteString
            == "https://api.example.com/v1/audio/speech")
    }

    @Test("rejects empty base URL")
    func rejectsEmptyBase() {
        #expect(throws: OpenAiError.missingBaseURL) {
            try OpenAiEndpoints.compose("   ", path: "/audio/speech")
        }
    }
}

@Suite("OpenAI-compatible TTS client")
struct OpenAiTtsClientTests {
    @Test("posts JSON with wav format and returns the WAV body")
    func synthesizes() async throws {
        let wav = minimalWav()
        let transport = FakeHttpTransport(body: wav)
        let client = OpenAiTtsClient(
            baseURL: "https://api.example.com/v1",
            apiKey: "sk-test",
            model: "tts-1",
            voice: "alloy",
            transport: transport
        )
        let result = try await client.synthesize("你好")
        #expect(result == wav)

        let request = try #require(transport.lastRequest)
        #expect(request.url?.absoluteString == "https://api.example.com/v1/audio/speech")
        #expect(request.value(forHTTPHeaderField: "Authorization") == "Bearer sk-test")
        let body = try #require(
            try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: Any]
        )
        #expect(body["model"] as? String == "tts-1")
        #expect(body["input"] as? String == "你好")
        #expect(body["voice"] as? String == "alloy")
        #expect(body["response_format"] as? String == "wav")
    }

    @Test("rejects non-WAV responses and surfaces HTTP errors")
    func rejectsInvalid() async {
        let mp3Transport = FakeHttpTransport(body: Data([0x49, 0x44, 0x33, 0x04]))
        let mp3Client = OpenAiTtsClient(
            baseURL: "https://api.example.com", apiKey: "k", model: "m", voice: "v",
            transport: mp3Transport
        )
        await #expect(throws: OpenAiError.invalidResponse) { try await mp3Client.synthesize("hi") }

        let errorTransport = FakeHttpTransport(body: Data(#"{"error":"bad key"}"#.utf8), status: 401)
        let errorClient = OpenAiTtsClient(
            baseURL: "https://api.example.com", apiKey: "k", model: "m", voice: "v",
            transport: errorTransport
        )
        await #expect(throws: OpenAiError.http(401, #"{"error":"bad key"}"#)) {
            try await errorClient.synthesize("hi")
        }
    }

    @Test("rejects empty text")
    func rejectsEmptyText() async {
        let client = OpenAiTtsClient(
            baseURL: "https://api.example.com", apiKey: "k", model: "m", voice: "v",
            transport: FakeHttpTransport()
        )
        await #expect(throws: TtsError.emptyText) { try await client.synthesize("  ") }
    }
}

@Suite("OpenAI-compatible STT client")
struct OpenAiSttClientTests {
    @Test("uploads multipart WAV and parses the transcript")
    func transcribes() async throws {
        let transport = FakeHttpTransport(body: Data(#"{"text":"  你好世界  "}"#.utf8))
        let client = OpenAiSttClient(
            baseURL: "api.example.com", apiKey: "sk-test", model: "whisper-1",
            transport: transport
        )
        let text = try await client.transcribe(wav: minimalWav())
        #expect(text == "你好世界")

        let request = try #require(transport.lastRequest)
        #expect(request.url?.absoluteString == "https://api.example.com/v1/audio/transcriptions")
        let body = String(decoding: request.httpBody ?? Data(), as: UTF8.self)
        #expect(body.contains("name=\"model\""))
        #expect(body.contains("whisper-1"))
        #expect(body.contains("filename=\"audio.wav\""))
    }

    @Test("rejects empty transcripts and HTTP errors")
    func rejectsInvalid() async {
        let emptyTransport = FakeHttpTransport(body: Data(#"{"text":"  "}"#.utf8))
        let emptyClient = OpenAiSttClient(
            baseURL: "https://api.example.com", apiKey: "k", model: "m", transport: emptyTransport
        )
        await #expect(throws: OpenAiError.emptyTranscript) {
            try await emptyClient.transcribe(wav: minimalWav())
        }

        let errorTransport = FakeHttpTransport(body: Data(), status: 500)
        let errorClient = OpenAiSttClient(
            baseURL: "https://api.example.com", apiKey: "k", model: "m", transport: errorTransport
        )
        do {
            _ = try await errorClient.transcribe(wav: minimalWav())
            Issue.record("expected HTTP error")
        } catch let error as OpenAiError {
            guard case .http(let status, _) = error else {
                Issue.record("unexpected error \(error)")
                return
            }
            #expect(status == 500)
        } catch {
            Issue.record("unexpected error \(error)")
        }
    }

    @Test("batch session buffers PCM and emits one final event")
    func batchSessionEmitsFinal() async throws {
        let transport = FakeHttpTransport(body: Data(#"{"text":"结果"}"#.utf8))
        let client = OpenAiSttClient(
            baseURL: "https://api.example.com", apiKey: "k", model: "m", transport: transport
        )
        let session = client.open()
        let events = TranscriptionEventRecorder()
        session.setEventHandler { event in await events.record(event) }

        var pcm = Data()
        for sample: Int16 in [100, -200, 300, -400] {
            var little = sample.littleEndian
            Swift.withUnsafeBytes(of: &little) { pcm.append(contentsOf: $0) }
        }
        session.append(pcm)
        session.finish()

        let final = try await events.waitFinal(timeout: .seconds(5))
        #expect(final == "结果")

        // 上传的应是 16kHz 单声道 WAV
        let request = try #require(transport.lastRequest)
        let body = String(decoding: request.httpBody ?? Data(), as: UTF8.self)
        #expect(body.contains("RIFF"))
    }

    @Test("batch session fails on empty audio")
    func batchSessionRejectsEmpty() async throws {
        let client = OpenAiSttClient(
            baseURL: "https://api.example.com", apiKey: "k", model: "m",
            transport: FakeHttpTransport()
        )
        let session = client.open()
        let events = TranscriptionEventRecorder()
        session.setEventHandler { event in await events.record(event) }
        session.finish()
        let failure = try await events.waitFailure(timeout: .seconds(5))
        #expect(failure.contains("emptyTranscript"))
    }
}

private struct RecorderTimeout: Error {}

private actor TranscriptionEventRecorder {
    private var finals: [String] = []
    private var failures: [String] = []
    private var continuation: CheckedContinuation<Void, Never>?

    func record(_ event: TranscriptionEvent) {
        switch event {
        case .final(let text): finals.append(text)
        case .failure(let message): failures.append(message)
        case .partial: break
        }
        continuation?.resume()
        continuation = nil
    }

    func waitFinal(timeout: Duration) async throws -> String {
        try await waitOnce(timeout: timeout)
        guard let final = finals.first else { throw RecorderTimeout() }
        return final
    }

    func waitFailure(timeout: Duration) async throws -> String {
        try await waitOnce(timeout: timeout)
        guard let failure = failures.first else { throw RecorderTimeout() }
        return failure
    }

    private func waitOnce(timeout: Duration) async throws {
        if !finals.isEmpty || !failures.isEmpty { return }
        let waiter = Task {
            let clock = ContinuousClock()
            try? await clock.sleep(until: clock.now.advanced(by: timeout))
            guard !Task.isCancelled else { return }
            await self.timeoutFired()
        }
        defer { waiter.cancel() }
        await withCheckedContinuation { (c: CheckedContinuation<Void, Never>) in
            if !finals.isEmpty || !failures.isEmpty {
                c.resume()
            } else {
                continuation = c
            }
        }
    }

    private func timeoutFired() {
        continuation?.resume()
        continuation = nil
    }
}
