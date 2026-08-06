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
            try await stop()
        default:
            throw CaptureSocketError.invalidCommand
        }
    }

    func disconnect() async {
        await cancelTimeout()
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
            // 注意：不能使用 Task.sleep(for:)。Xcode 26.x 工具链在 release 模式下
            // 对它的特化存在已确认的运行时缺陷，任务释放时会在 swift_task_dealloc
            // 触发 fatal error（swiftlang/swift#86204）。clock.sleep(until:) 不受影响。
            let clock = ContinuousClock()
            do {
                try await clock.sleep(until: clock.now.advanced(by: maximumDuration))
            } catch {
                // 被取消：清理工作由 cancelTimeout() 的调用方负责。
                return
            }
            guard !Task.isCancelled else { return }
            await self?.timedOut()
        }
    }

    private func stop() async throws {
        guard active else { throw CaptureSocketError.notStarted }
        await cancelTimeout()
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

    /// 唯一的超时任务清理入口：取出并置空属性，取消任务，并等待任务完全退出。
    /// 这保证调用返回时 timeout task 已经结束，避免在任务仍可能处于
    /// 取消/恢复中间状态时释放其引用（此前在 swift_task_dealloc 处触发 fatal error）。
    private func cancelTimeout() async {
        guard let task = timeoutTask else { return }
        timeoutTask = nil
        task.cancel()
        await task.value
    }

    private func providerEvent(_ event: TranscriptionEvent) async {
        switch event {
        case .partial(let text):
            await send(.delta(text))
        case .final(let text):
            await cancelTimeout()
            await send(.done(text))
        case .failure(let message):
            await cancelTimeout()
            if active {
                _ = try? capture.stop()
                active = false
            }
            await send(.error(code: "provider_error", message: message))
        }
    }

    private func timedOut() async {
        timeoutTask = nil
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
