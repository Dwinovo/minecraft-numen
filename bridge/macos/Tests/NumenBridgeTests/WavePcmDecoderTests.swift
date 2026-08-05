import Foundation
import Testing
@testable import NumenBridge

@Suite("PCM WAV input")
struct WavePcmDecoderTests {
    @Test("extracts 16 kHz mono Int16 PCM")
    func extractsPcm() throws {
        let pcm = Data([1, 2, 3, 4])
        let wave = try PcmWaveEncoder.encode(pcm: pcm, sampleRate: 16_000)
        #expect(try WavePcmDecoder.decode(wave) == pcm)
    }

    @Test("rejects malformed and unsupported WAV files")
    func rejectsInvalidWave() throws {
        #expect(throws: WavePcmError.invalidContainer) {
            try WavePcmDecoder.decode(Data("not-wave".utf8))
        }
        let wrongRate = try PcmWaveEncoder.encode(pcm: Data([1, 2]), sampleRate: 24_000)
        #expect(throws: WavePcmError.unsupportedFormat) {
            try WavePcmDecoder.decode(wrongRate)
        }
        let empty = try waveWithEmptyData()
        #expect(throws: WavePcmError.emptyAudio) {
            try WavePcmDecoder.decode(empty)
        }
    }

    private func waveWithEmptyData() throws -> Data {
        var wave = try PcmWaveEncoder.encode(pcm: Data([1, 2]), sampleRate: 16_000)
        wave.replaceSubrange(40..<44, with: [0, 0, 0, 0])
        wave.removeSubrange(44..<wave.count)
        return wave
    }
}
