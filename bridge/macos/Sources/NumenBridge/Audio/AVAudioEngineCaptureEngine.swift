import AVFoundation
import Foundation

final class AVAudioEngineCaptureEngine: PcmCaptureEngine, @unchecked Sendable {
    private let engine = AVAudioEngine()
    private let lock = NSLock()
    private var running = false

    func start(onPCM: @escaping @Sendable (Data) -> Void) throws {
        let shouldStart = lock.withLock {
            guard !running else { return false }
            running = true
            return true
        }
        guard shouldStart else { throw CaptureError.alreadyCapturing }

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0, format.channelCount > 0 else {
            lock.withLock { running = false }
            throw PcmConversionError.unsupportedFormat
        }
        input.installTap(onBus: 0, bufferSize: 1_600, format: format) { buffer, _ in
            guard let data = try? Pcm16Converter.convert(buffer), !data.isEmpty else { return }
            onPCM(data)
        }
        do {
            engine.prepare()
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            lock.withLock { running = false }
            throw error
        }
    }

    func stop() {
        let shouldStop = lock.withLock {
            guard running else { return false }
            running = false
            return true
        }
        guard shouldStop else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        engine.reset()
    }
}
