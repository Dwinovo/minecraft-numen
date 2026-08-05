@preconcurrency import AVFoundation
import Foundation

enum Pcm16Converter {
    static let sampleRate = 16_000.0

    static func convert(_ input: AVAudioPCMBuffer) throws -> Data {
        guard input.frameLength > 0 else { return Data() }
        guard let outputFormat = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: sampleRate,
            channels: 1,
            interleaved: false
        ), let converter = AVAudioConverter(from: input.format, to: outputFormat) else {
            throw PcmConversionError.unsupportedFormat
        }
        converter.primeMethod = .none
        let ratio = sampleRate / input.format.sampleRate
        let targetFrames = AVAudioFrameCount(round(Double(input.frameLength) * ratio))
        let capacity = targetFrames + 8
        guard let output = AVAudioPCMBuffer(pcmFormat: outputFormat, frameCapacity: capacity) else {
            throw PcmConversionError.cannotAllocate
        }

        let feeder = ConverterInputFeeder(buffer: input)
        var conversionError: NSError?
        let status = converter.convert(to: output, error: &conversionError) { _, inputStatus in
            feeder.next(status: inputStatus)
        }
        if let conversionError {
            throw conversionError
        }
        guard status == .haveData || status == .endOfStream,
              let samples = output.int16ChannelData?[0] else {
            throw PcmConversionError.conversionFailed
        }
        let validFrames = min(output.frameLength, targetFrames)
        return Data(bytes: samples, count: Int(validFrames) * MemoryLayout<Int16>.size)
    }
}

private final class ConverterInputFeeder: @unchecked Sendable {
    private let lock = NSLock()
    private var buffer: AVAudioPCMBuffer?

    init(buffer: AVAudioPCMBuffer) {
        self.buffer = buffer
    }

    func next(status: UnsafeMutablePointer<AVAudioConverterInputStatus>) -> AVAudioBuffer? {
        lock.withLock {
            guard let buffer else {
                status.pointee = .endOfStream
                return nil
            }
            self.buffer = nil
            status.pointee = .haveData
            return buffer
        }
    }
}

enum PcmConversionError: Error, Equatable {
    case unsupportedFormat
    case cannotAllocate
    case conversionFailed
}
