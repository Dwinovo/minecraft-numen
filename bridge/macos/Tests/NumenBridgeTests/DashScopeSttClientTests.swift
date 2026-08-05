import Foundation
import Testing
@testable import NumenBridge

@Suite("DashScope realtime STT")
struct DashScopeSttClientTests {
    @Test("builds session, audio append, commit, and response messages")
    func buildsMessages() throws {
        #expect(DashScopeSttClient.defaultModel == "qwen-audio-3.0-realtime-flash")

        let update = try json(DashScopeSttClient.sessionUpdate(model: DashScopeSttClient.defaultModel))
        #expect(update["type"] as? String == "session.update")
        let session = try #require(update["session"] as? [String: Any])
        #expect(session["modalities"] as? [String] == ["text"])
        #expect(session["turn_detection"] is NSNull)
        let transcription = try #require(session["input_audio_transcription"] as? [String: Any])
        #expect(transcription["model"] as? String == DashScopeSttClient.defaultModel)

        let append = try json(DashScopeSttClient.audioAppend(Data([1, 2, 3, 4])))
        #expect(append["type"] as? String == "input_audio_buffer.append")
        #expect(append["audio"] as? String == "AQIDBA==")
        #expect(try json(DashScopeSttClient.commitMessage)["type"] as? String == "input_audio_buffer.commit")
        #expect(try json(DashScopeSttClient.responseCreateMessage)["type"] as? String == "response.create")
    }

    @Test("queues copied PCM until the provider handshake completes")
    func queuesBeforeHandshake() throws {
        var buffer = SttOutboundBuffer(model: "test-model")
        var source = Data([1, 2])
        try buffer.append(source)
        source[0] = 9
        buffer.finish()

        let messages = try buffer.connectedMessages()
        #expect(messages.count == 4)
        #expect(try json(messages[1])["audio"] as? String == "AQI=")
        #expect(try json(messages[2])["type"] as? String == "input_audio_buffer.commit")
        #expect(try json(messages[3])["type"] as? String == "response.create")
    }

    @Test("accumulates deltas and requires a non-empty completed transcript")
    func accumulatesTranscript() throws {
        var accumulator = SttResponseAccumulator()
        #expect(try accumulator.receive(#"{"type":"conversation.item.input_audio_transcription.delta","transcript":"你"}"#) == .partial("你"))
        #expect(try accumulator.receive(#"{"type":"conversation.item.input_audio_transcription.delta","transcript":"好"}"#) == .partial("你好"))
        #expect(try accumulator.receive(#"{"type":"conversation.item.input_audio_transcription.completed","transcript":"你好。"}"#) == .final("你好。"))

        var empty = SttResponseAccumulator()
        #expect(throws: SttError.emptyTranscript) {
            try empty.receive(#"{"type":"conversation.item.input_audio_transcription.completed","transcript":"  "}"#)
        }

        var provider = SttResponseAccumulator()
        #expect(throws: SttError.provider("bad key")) {
            try provider.receive(#"{"type":"error","error":{"message":"bad key"}}"#)
        }

        let closed = SttResponseAccumulator()
        #expect(throws: SttError.connectionClosed) {
            try closed.connectionClosed()
        }
    }

    private func json(_ text: String) throws -> [String: Any] {
        try #require(JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any])
    }
}
