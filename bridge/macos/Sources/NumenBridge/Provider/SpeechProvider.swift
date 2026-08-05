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
