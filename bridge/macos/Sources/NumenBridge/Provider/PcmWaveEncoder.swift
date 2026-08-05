import Foundation

enum PcmWaveEncoder {
    static func encode(pcm: Data, sampleRate: Int) throws -> Data {
        guard !pcm.isEmpty else { throw TtsError.emptyAudio }
        guard pcm.count.isMultiple(of: MemoryLayout<Int16>.size), sampleRate > 0 else {
            throw TtsError.invalidPCM
        }

        var output = Data()
        output.append(Data("RIFF".utf8))
        append(UInt32(36 + pcm.count), to: &output)
        output.append(Data("WAVEfmt ".utf8))
        append(UInt32(16), to: &output)
        append(UInt16(1), to: &output)
        append(UInt16(1), to: &output)
        append(UInt32(sampleRate), to: &output)
        append(UInt32(sampleRate * 2), to: &output)
        append(UInt16(2), to: &output)
        append(UInt16(16), to: &output)
        output.append(Data("data".utf8))
        append(UInt32(pcm.count), to: &output)
        output.append(pcm)
        return output
    }

    private static func append<T: FixedWidthInteger>(_ value: T, to data: inout Data) {
        var littleEndian = value.littleEndian
        Swift.withUnsafeBytes(of: &littleEndian) { data.append(contentsOf: $0) }
    }
}
