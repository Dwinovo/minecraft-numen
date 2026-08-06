@preconcurrency import Foundation

struct DashScopeSttClient: Sendable {
    static let defaultModel = "qwen-audio-3.0-realtime-flash"
    private static let endpoint = "wss://dashscope.aliyuncs.com/api-ws/v1/realtime"

    let apiKey: String
    let model: String
    let connector: any TextWebSocketConnecting

    init(
        apiKey: String,
        model: String = defaultModel,
        connector: any TextWebSocketConnecting = URLSessionTextWebSocketConnector()
    ) {
        self.apiKey = apiKey
        self.model = model.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? Self.defaultModel : model
        self.connector = connector
    }

    func open() -> any StreamingTranscriptionSession {
        let session = DashScopeSttSession(model: model)
        guard var components = URLComponents(string: Self.endpoint) else {
            session.fail(SttError.invalidURL)
            return session
        }
        components.queryItems = [URLQueryItem(name: "model", value: model)]
        guard let url = components.url else {
            session.fail(SttError.invalidURL)
            return session
        }
        session.connect(
            connector: connector,
            url: url,
            authorization: "Bearer \(apiKey)"
        )
        return session
    }

    func transcribe(pcm: Data, timeout: Duration = .seconds(65)) async throws -> String {
        let session = open()
        let result = SttResultWaiter()
        session.setEventHandler { event in
            switch event {
            case .partial:
                break
            case .final(let text):
                await result.complete(.success(text))
            case .failure(let message):
                await result.complete(.failure(SttError.provider(message)))
            }
        }
        session.append(pcm)
        session.finish()

        let timeoutTask = Task {
            // 使用 clock.sleep(until:) 而非 Task.sleep(for:)，
            // 规避 swiftlang/swift#86204 的 release 模式崩溃。
            let clock = ContinuousClock()
            try? await clock.sleep(until: clock.now.advanced(by: timeout))
            guard !Task.isCancelled else { return }
            await result.complete(.failure(SttError.timeout))
            session.cancel()
        }
        defer { timeoutTask.cancel() }
        return try await withTaskCancellationHandler {
            try await result.wait()
        } onCancel: {
            session.cancel()
            Task { await result.complete(.failure(CancellationError())) }
        }
    }

    static func sessionUpdate(model: String) throws -> String {
        try encode([
            "type": "session.update",
            "session": [
                "modalities": ["text"],
                "input_audio_transcription": ["model": model],
                "turn_detection": NSNull()
            ]
        ])
    }

    static func audioAppend(_ pcm: Data) throws -> String {
        try encode([
            "type": "input_audio_buffer.append",
            "audio": pcm.base64EncodedString()
        ])
    }

    static let commitMessage = #"{"type":"input_audio_buffer.commit"}"#
    static let responseCreateMessage = #"{"type":"response.create"}"#

    private static func encode(_ object: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }
}

struct SttOutboundBuffer: Sendable {
    private let model: String
    private var connected = false
    private var pendingAudio: [Data] = []
    private var finishRequested = false

    init(model: String) {
        self.model = model
    }

    @discardableResult
    mutating func append(_ pcm: Data) throws -> [String] {
        let copy = Data(pcm)
        if connected {
            return [try DashScopeSttClient.audioAppend(copy)]
        }
        pendingAudio.append(copy)
        return []
    }

    @discardableResult
    mutating func finish() -> [String] {
        if connected {
            return [DashScopeSttClient.commitMessage, DashScopeSttClient.responseCreateMessage]
        }
        finishRequested = true
        return []
    }

    mutating func connectedMessages() throws -> [String] {
        guard !connected else { return [] }
        connected = true
        var messages = [try DashScopeSttClient.sessionUpdate(model: model)]
        messages.append(contentsOf: try pendingAudio.map(DashScopeSttClient.audioAppend))
        pendingAudio.removeAll(keepingCapacity: false)
        if finishRequested {
            messages.append(DashScopeSttClient.commitMessage)
            messages.append(DashScopeSttClient.responseCreateMessage)
            finishRequested = false
        }
        return messages
    }
}

struct SttResponseAccumulator: Sendable {
    private var transcript = ""

    mutating func receive(_ text: String) throws -> TranscriptionEvent? {
        let event: SttProviderEvent
        do {
            event = try JSONDecoder().decode(SttProviderEvent.self, from: Data(text.utf8))
        } catch {
            throw SttError.invalidEvent
        }
        switch event.type {
        case "conversation.item.input_audio_transcription.delta":
            transcript += event.transcript ?? ""
            return .partial(transcript)
        case "conversation.item.input_audio_transcription.completed":
            let completed = (event.transcript ?? transcript).trimmingCharacters(in: .whitespacesAndNewlines)
            guard !completed.isEmpty else { throw SttError.emptyTranscript }
            return .final(completed)
        case "error":
            throw SttError.provider(event.error?.message ?? "DashScope STT error")
        default:
            return nil
        }
    }

    func connectionClosed() throws -> Never {
        throw SttError.connectionClosed
    }
}

enum SttError: Error, Equatable {
    case emptyTranscript
    case invalidEvent
    case invalidURL
    case connectionClosed
    case timeout
    case provider(String)
    case transport(String)
}

private actor SttResultWaiter {
    private var result: Result<String, Error>?
    private var continuation: CheckedContinuation<String, Error>?

    func wait() async throws -> String {
        if let result { return try result.get() }
        return try await withCheckedThrowingContinuation { continuation = $0 }
    }

    func complete(_ result: Result<String, Error>) {
        guard self.result == nil else { return }
        self.result = result
        if let continuation {
            self.continuation = nil
            continuation.resume(with: result)
        }
    }
}

private struct SttProviderEvent: Decodable {
    let type: String
    let transcript: String?
    let error: SttProviderError?
}

private struct SttProviderError: Decodable {
    let message: String?
}

private final class DashScopeSttSession: StreamingTranscriptionSession, @unchecked Sendable {
    private let lock = NSLock()
    private var outbound: SttOutboundBuffer
    private var continuation: AsyncStream<String>.Continuation?
    private var socket: (any TextWebSocket)?
    private var eventHandler: (@Sendable (TranscriptionEvent) async -> Void)?
    private var pendingFailure: String?
    private var terminal = false

    init(model: String) {
        self.outbound = SttOutboundBuffer(model: model)
    }

    func setEventHandler(_ handler: (@Sendable (TranscriptionEvent) async -> Void)?) {
        let failure = lock.withLock { () -> String? in
            eventHandler = handler
            guard handler != nil else { return nil }
            let value = pendingFailure
            pendingFailure = nil
            return value
        }
        if let handler, let failure {
            Task { await handler(.failure(failure)) }
        }
    }

    func append(_ pcm: Data) {
        do {
            let result = try lock.withLock { try outbound.append(pcm) }
            yield(result)
        } catch {
            fail(error)
        }
    }

    func finish() {
        let result = lock.withLock { outbound.finish() }
        yield(result)
    }

    func cancel() {
        let resources = lock.withLock { () -> (AsyncStream<String>.Continuation?, (any TextWebSocket)?) in
            guard !terminal else { return (nil, nil) }
            terminal = true
            let values = (continuation, socket)
            continuation = nil
            socket = nil
            eventHandler = nil
            pendingFailure = nil
            return values
        }
        resources.0?.finish()
        if let socket = resources.1 {
            Task { await socket.close() }
        }
    }

    func connect(connector: any TextWebSocketConnecting, url: URL, authorization: String) {
        let (stream, continuation) = AsyncStream<String>.makeStream()
        let shouldConnect = lock.withLock {
            guard !terminal else { return false }
            self.continuation = continuation
            return true
        }
        guard shouldConnect else {
            continuation.finish()
            return
        }

        Task { [weak self] in
            guard let self else { return }
            var connectedSocket: (any TextWebSocket)?
            do {
                let socket = try await connector.connect(url: url, authorization: authorization)
                connectedSocket = socket
                let initial = try self.didConnect(socket)
                for message in initial {
                    try await socket.send(text: message)
                }
                let receiver = Task { try await self.receive(from: socket) }
                for await message in stream {
                    try await socket.send(text: message)
                }
                _ = try await receiver.value
            } catch {
                if let connectedSocket { await connectedSocket.close() }
                self.fail(error)
            }
        }
    }

    func fail(_ error: Error) {
        let message: String
        if let sttError = error as? SttError {
            message = String(describing: sttError)
        } else {
            message = String(describing: SttError.transport(String(describing: error)))
        }
        let resources = lock.withLock { () -> ((@Sendable (TranscriptionEvent) async -> Void)?, (any TextWebSocket)?) in
            guard !terminal else { return (nil, nil) }
            terminal = true
            continuation?.finish()
            continuation = nil
            let currentSocket = socket
            socket = nil
            if eventHandler == nil { pendingFailure = message }
            return (eventHandler, currentSocket)
        }
        if let socket = resources.1 {
            Task { await socket.close() }
        }
        if let handler = resources.0 {
            Task { await handler(.failure(message)) }
        }
    }

    private func didConnect(_ socket: any TextWebSocket) throws -> [String] {
        try lock.withLock {
            guard !terminal else { throw SttError.connectionClosed }
            self.socket = socket
            return try outbound.connectedMessages()
        }
    }

    private func yield(_ messages: [String]) {
        let continuation = lock.withLock { terminal ? nil : self.continuation }
        for message in messages {
            continuation?.yield(message)
        }
    }

    private func receive(from socket: any TextWebSocket) async throws {
        var accumulator = SttResponseAccumulator()
        while !Task.isCancelled {
            let text = try await socket.receiveText()
            guard let event = try accumulator.receive(text) else { continue }
            let handler = lock.withLock { terminal ? nil : eventHandler }
            if let handler { await handler(event) }
            if case .final = event {
                complete()
                await socket.close()
                return
            }
        }
    }

    private func complete() {
        lock.withLock {
            terminal = true
            continuation?.finish()
            continuation = nil
            socket = nil
            eventHandler = nil
        }
    }
}
