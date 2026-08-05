import Foundation
import Testing
@testable import NumenBridge

@Suite("DashScope realtime TTS")
struct DashScopeTtsClientTests {
    @Test("builds the official server-commit message sequence")
    func buildsRequestMessages() throws {
        #expect(DashScopeTtsClient.defaultModel == "qwen3-tts-flash-realtime")
        let messages = try DashScopeTtsClient.buildRequestMessages(text: "你好", voice: "Cherry")
        #expect(messages.count == 3)

        let update = try json(messages[0])
        #expect(update["type"] as? String == "session.update")
        let session = try #require(update["session"] as? [String: Any])
        #expect(session["mode"] as? String == "server_commit")
        #expect(session["voice"] as? String == "Cherry")
        #expect(session["response_format"] as? String == "pcm")
        #expect(session["sample_rate"] as? Int == 24_000)

        let append = try json(messages[1])
        #expect(append["type"] as? String == "input_text_buffer.append")
        #expect(append["text"] as? String == "你好")
        #expect(try json(messages[2])["type"] as? String == "input_text_buffer.commit")
    }

    @Test("joins audio deltas and emits a valid PCM WAV on response.done")
    func createsWave() throws {
        var accumulator = TtsResponseAccumulator(sampleRate: 24_000)
        #expect(try accumulator.receive(#"{"type":"response.audio.delta","delta":"AQI="}"#) == nil)
        #expect(try accumulator.receive(#"{"type":"response.audio.delta","delta":"AwQ="}"#) == nil)
        let completed = try accumulator.receive(#"{"type":"response.done"}"#)
        let wav = try #require(completed)

        #expect(String(data: wav.prefix(4), encoding: .ascii) == "RIFF")
        #expect(String(data: wav[8..<12], encoding: .ascii) == "WAVE")
        #expect(readUInt32LE(wav, at: 24) == 24_000)
        #expect(readUInt32LE(wav, at: 40) == 4)
        #expect(Array(wav.suffix(4)) == [1, 2, 3, 4])
    }

    @Test("rejects empty, malformed, provider-error, and closed responses")
    func rejectsInvalidResponses() throws {
        var empty = TtsResponseAccumulator(sampleRate: 24_000)
        #expect(throws: TtsError.emptyAudio) {
            try empty.receive(#"{"type":"response.done"}"#)
        }

        var malformed = TtsResponseAccumulator(sampleRate: 24_000)
        #expect(throws: TtsError.invalidBase64) {
            try malformed.receive(#"{"type":"response.audio.delta","delta":"%%%"}"#)
        }

        var provider = TtsResponseAccumulator(sampleRate: 24_000)
        #expect(throws: TtsError.provider("bad key")) {
            try provider.receive(#"{"type":"error","error":{"message":"bad key"}}"#)
        }

        let closed = TtsResponseAccumulator(sampleRate: 24_000)
        #expect(throws: TtsError.connectionClosed) {
            try closed.connectionClosed()
        }
    }

    private func json(_ text: String) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
    }

    private func readUInt32LE(_ data: Data, at offset: Int) -> UInt32 {
        data[offset..<(offset + 4)].enumerated().reduce(0) { value, item in
            value | (UInt32(item.element) << UInt32(item.offset * 8))
        }
    }
}
