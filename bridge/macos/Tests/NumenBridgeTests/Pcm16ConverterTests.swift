import AVFoundation
import Testing
@testable import NumenBridge

@Suite("Microphone PCM conversion")
struct Pcm16ConverterTests {
    @Test("converts 48 kHz stereo float audio to 16 kHz mono Int16 LE")
    func convertsToSttFormat() throws {
        let format = try #require(AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: 48_000,
            channels: 2,
            interleaved: false
        ))
        let buffer = try #require(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 480))
        buffer.frameLength = 480
        let channels = try #require(buffer.floatChannelData)
        for channel in 0..<2 {
            for frame in 0..<480 {
                channels[channel][frame] = 0.25
            }
        }

        let pcm = try Pcm16Converter.convert(buffer)

        #expect(pcm.count == 160 * MemoryLayout<Int16>.size)
        let samples = pcm.withUnsafeBytes { bytes in
            Array(bytes.bindMemory(to: Int16.self))
        }
        #expect(samples.count == 160)
        #expect(samples.dropFirst(16).allSatisfy { $0 > 7_000 && $0 < 9_000 })
    }
}
