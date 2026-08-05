import Foundation

enum WavePcmDecoder {
    static func decode(_ wave: Data) throws -> Data {
        guard wave.count >= 12,
              ascii(wave, 0) == "RIFF",
              ascii(wave, 8) == "WAVE" else {
            throw WavePcmError.invalidContainer
        }

        var format: WaveFormat?
        var pcm: Data?
        var offset = 12
        while offset + 8 <= wave.count {
            let id = ascii(wave, offset)
            let size = Int(readUInt32(wave, offset + 4))
            let start = offset + 8
            let end = start + size
            guard size >= 0, end <= wave.count else { throw WavePcmError.invalidContainer }

            if id == "fmt " {
                guard size >= 16 else { throw WavePcmError.invalidContainer }
                format = WaveFormat(
                    encoding: readUInt16(wave, start),
                    channels: readUInt16(wave, start + 2),
                    sampleRate: readUInt32(wave, start + 4),
                    bitsPerSample: readUInt16(wave, start + 14)
                )
            } else if id == "data" {
                pcm = wave.subdata(in: start..<end)
            }
            offset = end + (size.isMultiple(of: 2) ? 0 : 1)
        }

        guard let format, let pcm else { throw WavePcmError.invalidContainer }
        guard format.encoding == 1,
              format.channels == 1,
              format.sampleRate == 16_000,
              format.bitsPerSample == 16,
              pcm.count.isMultiple(of: 2) else {
            throw WavePcmError.unsupportedFormat
        }
        guard !pcm.isEmpty else { throw WavePcmError.emptyAudio }
        return pcm
    }

    private static func ascii(_ data: Data, _ offset: Int) -> String {
        guard offset + 4 <= data.count else { return "" }
        return String(decoding: data[offset..<(offset + 4)], as: UTF8.self)
    }

    private static func readUInt16(_ data: Data, _ offset: Int) -> UInt16 {
        UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
    }

    private static func readUInt32(_ data: Data, _ offset: Int) -> UInt32 {
        UInt32(data[offset])
            | (UInt32(data[offset + 1]) << 8)
            | (UInt32(data[offset + 2]) << 16)
            | (UInt32(data[offset + 3]) << 24)
    }
}

enum WavePcmError: Error, Equatable {
    case invalidContainer
    case unsupportedFormat
    case emptyAudio
}

private struct WaveFormat {
    let encoding: UInt16
    let channels: UInt16
    let sampleRate: UInt32
    let bitsPerSample: UInt16
}
