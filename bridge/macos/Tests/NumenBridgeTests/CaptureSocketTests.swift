import Foundation
import Testing
@testable import NumenBridge

@Suite("Local capture socket")
struct CaptureSocketTests {
    @Test("reports started only after the microphone is running, then finishes transcription")
    func startsAndStopsInOrder() async throws {
        let engine = CaptureSocketEngine()
        let provider = CaptureSocketProvider()
        let events = CaptureSocketEvents()
        let controller = CaptureSocketController(
            capture: MicrophoneCapture(authorizer: CaptureSocketAuthorizer(), engine: engine),
            provider: provider,
            send: { await events.append($0) }
        )

        try await controller.receive(#"{"type":"capture.start"}"#)
        #expect(engine.isRunning)
        #expect(await events.values == [.started])

        engine.emit(Data([1, 2, 3, 4]))
        await provider.emit(.partial("你好"))
        try await controller.receive(#"{"type":"capture.stop"}"#)
        await provider.emit(.final("你好。"))

        #expect(!engine.isRunning)
        #expect(provider.audio == [Data([1, 2, 3, 4])])
        #expect(provider.finishCount == 1)
        #expect(await events.values == [.started, .delta("你好"), .done("你好。")])
    }

    @Test("permission failure never reports started and disconnect cancels an active capture")
    func handlesFailureAndDisconnect() async throws {
        let deniedEvents = CaptureSocketEvents()
        let denied = CaptureSocketController(
            capture: MicrophoneCapture(authorizer: CaptureSocketAuthorizer(permission: .denied), engine: CaptureSocketEngine()),
            provider: CaptureSocketProvider(),
            send: { await deniedEvents.append($0) }
        )
        await #expect(throws: CaptureError.permissionDenied) {
            try await denied.receive(#"{"type":"capture.start"}"#)
        }
        #expect(await deniedEvents.values.isEmpty)

        let engine = CaptureSocketEngine()
        let provider = CaptureSocketProvider()
        let active = CaptureSocketController(
            capture: MicrophoneCapture(authorizer: CaptureSocketAuthorizer(), engine: engine),
            provider: provider,
            send: { _ in }
        )
        try await active.receive(#"{"type":"capture.start"}"#)
        engine.emit(Data([1, 2]))
        await active.disconnect()

        #expect(!engine.isRunning)
        #expect(provider.cancelCount == 1)
    }

    // 压力回归测试，对应当场崩溃 NumenBridge-2026-08-05-223811.ips / -223855.ips：
    // swift_task_dealloc fatal error，崩溃在 CaptureSocketController.start() 的超时 Task。
    // maximumDuration 故意设得极短，让取消发生在 Task.sleep 挂起点附近。

    @Test("stress: 100 start-stop cycles do not crash the timeout task")
    func stressStartStop() async throws {
        for i in 0 ..< 100 {
            let engine = CaptureSocketEngine()
            let provider = CaptureSocketProvider()
            let controller = CaptureSocketController(
                capture: MicrophoneCapture(authorizer: CaptureSocketAuthorizer(), engine: engine),
                provider: provider,
                maximumDuration: .milliseconds(10),
                send: { _ in }
            )
            try await controller.receive(#"{"type":"capture.start"}"#)
            engine.emit(Data([UInt8(i & 0xFF)]))
            try await controller.receive(#"{"type":"capture.stop"}"#)
            // 让超时任务彻底退出后再进入下一轮，避免任务堆积。
            try await Task.sleep(for: .milliseconds(2))
        }
    }

    @Test("stress: 100 start-disconnect cycles do not crash the timeout task")
    func stressStartDisconnect() async throws {
        for i in 0 ..< 100 {
            let engine = CaptureSocketEngine()
            let provider = CaptureSocketProvider()
            let controller = CaptureSocketController(
                capture: MicrophoneCapture(authorizer: CaptureSocketAuthorizer(), engine: engine),
                provider: provider,
                maximumDuration: .milliseconds(10),
                send: { _ in }
            )
            try await controller.receive(#"{"type":"capture.start"}"#)
            engine.emit(Data([UInt8(i & 0xFF)]))
            await controller.disconnect()
            try await Task.sleep(for: .milliseconds(2))
        }
    }

    @Test("stress: 100 start-provider-failure cycles do not crash the timeout task")
    func stressProviderFailure() async throws {
        for _ in 0 ..< 100 {
            let engine = CaptureSocketEngine()
            let provider = CaptureSocketProvider()
            let controller = CaptureSocketController(
                capture: MicrophoneCapture(authorizer: CaptureSocketAuthorizer(), engine: engine),
                provider: provider,
                maximumDuration: .milliseconds(10),
                send: { _ in }
            )
            try await controller.receive(#"{"type":"capture.start"}"#)
            engine.emit(Data([0x01]))
            await provider.emit(.failure("test error"))
            try await Task.sleep(for: .milliseconds(2))
        }
    }

    @Test("stress: 100 natural-timeout cycles do not crash")
    func stressNaturalTimeout() async throws {
        for i in 0 ..< 100 {
            let engine = CaptureSocketEngine()
            let provider = CaptureSocketProvider()
            let controller = CaptureSocketController(
                capture: MicrophoneCapture(authorizer: CaptureSocketAuthorizer(), engine: engine),
                provider: provider,
                maximumDuration: .milliseconds(10),
                send: { _ in }
            )
            try await controller.receive(#"{"type":"capture.start"}"#)
            engine.emit(Data([UInt8(i & 0xFF)]))
            // 不手动停止，让 timedOut() 自然触发。
            try await Task.sleep(for: .milliseconds(25))
        }
    }
}

private struct CaptureSocketAuthorizer: MicrophoneAuthorizing {
    let permission: MicrophonePermission

    init(permission: MicrophonePermission = .authorized) {
        self.permission = permission
    }

    func status() -> MicrophonePermission { permission }
    func requestAccess() async -> Bool { permission == .authorized }
}

private final class CaptureSocketEngine: PcmCaptureEngine, @unchecked Sendable {
    private let lock = NSLock()
    private var handler: (@Sendable (Data) -> Void)?

    var isRunning: Bool { lock.withLock { handler != nil } }

    func start(onPCM: @escaping @Sendable (Data) -> Void) throws {
        lock.withLock { handler = onPCM }
    }

    func stop() {
        lock.withLock { handler = nil }
    }

    func emit(_ data: Data) {
        lock.withLock { handler }?(data)
    }
}

private final class CaptureSocketProvider: StreamingTranscriptionSession, @unchecked Sendable {
    private let lock = NSLock()
    private var audioStorage: [Data] = []
    private var finishStorage = 0
    private var cancelStorage = 0
    private var eventHandler: (@Sendable (TranscriptionEvent) async -> Void)?

    var audio: [Data] { lock.withLock { audioStorage } }
    var finishCount: Int { lock.withLock { finishStorage } }
    var cancelCount: Int { lock.withLock { cancelStorage } }

    func setEventHandler(_ handler: (@Sendable (TranscriptionEvent) async -> Void)?) {
        lock.withLock { eventHandler = handler }
    }

    func append(_ pcm: Data) { lock.withLock { audioStorage.append(pcm) } }
    func finish() { lock.withLock { finishStorage += 1 } }
    func cancel() { lock.withLock { cancelStorage += 1 } }

    func emit(_ event: TranscriptionEvent) async {
        let handler = lock.withLock { eventHandler }
        if let handler {
            await handler(event)
        }
    }
}

private actor CaptureSocketEvents {
    private(set) var values: [CaptureEvent] = []
    func append(_ event: CaptureEvent) { values.append(event) }
}
