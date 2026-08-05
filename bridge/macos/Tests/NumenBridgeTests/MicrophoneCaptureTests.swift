import Foundation
import Testing
@testable import NumenBridge

@Suite("Microphone capture lifecycle")
struct MicrophoneCaptureTests {
    @Test("allows one capture and stops forwarding after stop")
    func enforcesSingleCapture() async throws {
        let engine = FakeCaptureEngine()
        let capture = MicrophoneCapture(
            authorizer: FakeAuthorizer(permission: .authorized),
            engine: engine
        )
        let received = LockedValues()

        try await capture.start { received.append($0) }
        await #expect(throws: CaptureError.alreadyCapturing) {
            try await capture.start { _ in }
        }
        engine.emit(Data([1, 2]))
        try capture.stop()
        engine.emit(Data([3, 4]))

        #expect(received.values == [Data([1, 2])])
        #expect(engine.stopCount == 1)
    }

    @Test("reports an empty capture")
    func reportsNoSamples() async throws {
        let capture = MicrophoneCapture(
            authorizer: FakeAuthorizer(permission: .authorized),
            engine: FakeCaptureEngine()
        )
        try await capture.start { _ in }
        #expect(throws: CaptureError.noSamples) {
            try capture.stop()
        }
    }
}

private struct FakeAuthorizer: MicrophoneAuthorizing {
    let permission: MicrophonePermission

    func status() -> MicrophonePermission { permission }
    func requestAccess() async -> Bool { permission == .authorized }
}

private final class FakeCaptureEngine: PcmCaptureEngine, @unchecked Sendable {
    private var handler: (@Sendable (Data) -> Void)?
    private(set) var stopCount = 0

    func start(onPCM: @escaping @Sendable (Data) -> Void) throws {
        handler = onPCM
    }

    func stop() {
        stopCount += 1
        handler = nil
    }

    func emit(_ data: Data) {
        handler?(data)
    }
}

private final class LockedValues: @unchecked Sendable {
    private let lock = NSLock()
    private var storage: [Data] = []

    var values: [Data] { lock.withLock { storage } }
    func append(_ value: Data) { lock.withLock { storage.append(value) } }
}
