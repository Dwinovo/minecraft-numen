import Foundation

protocol SpeechSynthesizing: Sendable {
    func synthesize(_ text: String) async throws -> Data
}

protocol TextWebSocket: Sendable {
    func send(text: String) async throws
    func receiveText() async throws -> String
    func close() async
}

protocol TextWebSocketConnecting: Sendable {
    func connect(url: URL, authorization: String) async throws -> any TextWebSocket
}

enum TranscriptionEvent: Equatable, Sendable {
    case partial(String)
    case final(String)
    case failure(String)
}

protocol StreamingTranscriptionSession: AnyObject, Sendable {
    func setEventHandler(_ handler: (@Sendable (TranscriptionEvent) async -> Void)?)
    func append(_ pcm: Data)
    func finish()
    func cancel()
}
