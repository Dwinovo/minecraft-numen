import Foundation

protocol PcmCaptureEngine: Sendable {
    func start(onPCM: @escaping @Sendable (Data) -> Void) throws
    func stop()
}

final class MicrophoneCapture: @unchecked Sendable {
    private let authorizer: any MicrophoneAuthorizing
    private let engine: any PcmCaptureEngine
    private let lock = NSLock()
    private var active = false
    private var starting = false
    private var sampleBytes = 0

    init(authorizer: any MicrophoneAuthorizing, engine: any PcmCaptureEngine) {
        self.authorizer = authorizer
        self.engine = engine
    }

    func start(onPCM: @escaping @Sendable (Data) -> Void) async throws {
        let canStart = lock.withLock {
            guard !active && !starting else { return false }
            starting = true
            return true
        }
        guard canStart else { throw CaptureError.alreadyCapturing }

        do {
            try await requirePermission()
            lock.withLock {
                sampleBytes = 0
                active = true
            }
            try engine.start { [weak self] data in
                guard !data.isEmpty else { return }
                let shouldForward = self?.lock.withLock {
                    guard self?.active == true else { return false }
                    self?.sampleBytes += data.count
                    return true
                } ?? false
                if shouldForward {
                    onPCM(data)
                }
            }
            lock.withLock { starting = false }
        } catch {
            lock.withLock {
                active = false
                starting = false
                sampleBytes = 0
            }
            engine.stop()
            throw error
        }
    }

    func stop() throws {
        let bytes = try lock.withLock { () throws -> Int in
            guard active else { throw CaptureError.notCapturing }
            active = false
            let result = sampleBytes
            sampleBytes = 0
            return result
        }
        engine.stop()
        guard bytes > 0 else { throw CaptureError.noSamples }
    }

    private func requirePermission() async throws {
        switch authorizer.status() {
        case .authorized:
            return
        case .notDetermined:
            guard await authorizer.requestAccess() else {
                throw CaptureError.permissionDenied
            }
        case .denied:
            throw CaptureError.permissionDenied
        case .restricted:
            throw CaptureError.permissionRestricted
        }
    }
}

enum CaptureError: Error, Equatable {
    case alreadyCapturing
    case notCapturing
    case noSamples
    case permissionDenied
    case permissionRestricted
}
