import Foundation

enum CaptureEvent: Equatable, Sendable {
    case started
    case delta(String)
    case done(String)
    case error(code: String, message: String)

    func wireText() throws -> String {
        let object: [String: String]
        switch self {
        case .started:
            object = ["type": "capture.started"]
        case .delta(let transcript):
            object = ["type": "transcript.delta", "transcript": transcript]
        case .done(let transcript):
            object = ["type": "transcript.done", "transcript": transcript]
        case .error(let code, let message):
            object = ["type": "error", "code": code, "message": message]
        }
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }
}

actor CaptureSocketController {
    private let capture: MicrophoneCapture
    private let provider: any StreamingTranscriptionSession
    private let send: @Sendable (CaptureEvent) async -> Void
    private let maximumDuration: Duration
    private var active = false
    private var timeoutTask: Task<Void, Never>?

    init(
        capture: MicrophoneCapture,
        provider: any StreamingTranscriptionSession,
        maximumDuration: Duration = .seconds(60),
        send: @escaping @Sendable (CaptureEvent) async -> Void
    ) {
        self.capture = capture
        self.provider = provider
        self.maximumDuration = maximumDuration
        self.send = send
    }

    func receive(_ text: String) async throws {
        let command: CaptureCommand
        do {
            command = try JSONDecoder().decode(CaptureCommand.self, from: Data(text.utf8))
        } catch {
            throw CaptureSocketError.invalidCommand
        }
        switch command.type {
        case "capture.start":
            try await start()
        case "capture.stop":
            try stop()
        default:
            throw CaptureSocketError.invalidCommand
        }
    }

    func disconnect() async {
        timeoutTask?.cancel()
        timeoutTask = nil
        if active {
            _ = try? capture.stop()
            active = false
        }
        provider.setEventHandler(nil)
        provider.cancel()
    }

    private func start() async throws {
        guard !active else { throw CaptureSocketError.alreadyStarted }
        provider.setEventHandler { [weak self] event in
            await self?.providerEvent(event)
        }
        do {
            try await capture.start { [provider] pcm in
                provider.append(Data(pcm))
            }
        } catch {
            provider.setEventHandler(nil)
            provider.cancel()
            throw error
        }
        active = true
        await send(.started)
        timeoutTask = Task { [weak self, maximumDuration] in
            try? await Task.sleep(for: maximumDuration)
            guard !Task.isCancelled else { return }
            await self?.timedOut()
        }
    }

    private func stop() throws {
        guard active else { throw CaptureSocketError.notStarted }
        timeoutTask?.cancel()
        timeoutTask = nil
        do {
            try capture.stop()
            active = false
            provider.finish()
        } catch {
            active = false
            provider.cancel()
            throw error
        }
    }

    private func providerEvent(_ event: TranscriptionEvent) async {
        switch event {
        case .partial(let text):
            await send(.delta(text))
        case .final(let text):
            timeoutTask?.cancel()
            timeoutTask = nil
            await send(.done(text))
        case .failure(let message):
            timeoutTask?.cancel()
            timeoutTask = nil
            if active {
                _ = try? capture.stop()
                active = false
            }
            await send(.error(code: "provider_error", message: message))
        }
    }

    private func timedOut() async {
        if active {
            _ = try? capture.stop()
            active = false
        }
        provider.cancel()
        await send(.error(code: "capture_timeout", message: "Capture exceeded 60 seconds"))
    }
}

enum CaptureSocketError: Error, Equatable {
    case invalidCommand
    case alreadyStarted
    case notStarted
}

private struct CaptureCommand: Decodable {
    let type: String
}
