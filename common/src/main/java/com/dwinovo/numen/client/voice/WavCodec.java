package com.dwinovo.numen.client.voice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 极简 WAV(RIFF) 解码：把 TTS 服务返回的 WAV 字节解成 {@link PcmAudio}
 * （16-bit LE 单声道）。不依赖 javax.sound 的 SPI 机制——WAV 头解析只有
 * 几十行,自己写反而没有平台差异。
 *
 * <h2>支持范围（javadoc 即文档）</h2>
 * <ul>
 *   <li>格式：PCM（audioFormat=1），8-bit 或 16-bit；</li>
 *   <li>声道：单声道直通；立体声取双声道平均降混为单声道
 *       （空间音源必须是 mono,立体声在 OpenAL 里不做距离衰减）；</li>
 *   <li>采样率：8000–48000 Hz 均接受,<b>不重采样</b>——OpenAL buffer 自带
 *       频率,常见 TTS 输出（16k/22.05k/24k/44.1k）都直接可用；</li>
 *   <li>不支持：IEEE float(3)、WAVE_FORMAT_EXTENSIBLE(0xFFFE)、ADPCM、mp3。
 *       遇到即抛 {@link IOException},由管线记日志并跳过该句。</li>
 * </ul>
 */
public final class WavCodec {

    private WavCodec() {}

    /** 采样率下限/上限（Hz），超出视为不可信数据。 */
    public static final int MIN_SAMPLE_RATE = 8_000;
    public static final int MAX_SAMPLE_RATE = 48_000;

    /**
     * 解码一段完整的 WAV 字节。
     *
     * @throws IOException 非 WAV、缺 fmt/data 块、或格式超出支持范围
     */
    public static PcmAudio decode(byte[] wav) throws IOException {
        try {
            return decodeUnsafe(wav);
        } catch (java.nio.BufferUnderflowException | IllegalArgumentException ex) {
            throw new IOException("WAV 头损坏: " + ex, ex);
        }
    }

    private static PcmAudio decodeUnsafe(byte[] wav) throws IOException {
        if (wav == null || wav.length < 44) throw new IOException("响应太短,不是有效的 WAV");
        ByteBuffer bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (bb.getInt() != 0x46464952) throw new IOException("缺少 RIFF 魔数,不是 WAV");   // "RIFF"
        bb.getInt();   // riff size,不校验(不少 TTS 流式落盘时这里写 0)
        if (bb.getInt() != 0x45564157) throw new IOException("缺少 WAVE 标记,不是 WAV");   // "WAVE"

        int audioFormat = -1, channels = -1, sampleRate = -1, bitsPerSample = -1;
        byte[] pcm = null;

        while (bb.remaining() >= 8) {
            int chunkId = bb.getInt();
            int chunkSize = bb.getInt();
            if (chunkSize < 0 || chunkSize > bb.remaining()) {
                // 尺寸损坏:data 块常见"写成剩余全部"的懒实现,兜底取到结尾。
                chunkSize = bb.remaining();
            }
            int next = bb.position() + chunkSize;
            if (chunkId == 0x20746d66) {                    // "fmt "
                audioFormat = bb.getShort() & 0xFFFF;
                channels = bb.getShort() & 0xFFFF;
                sampleRate = bb.getInt();
                bb.getInt();      // byte rate
                bb.getShort();    // block align
                bitsPerSample = bb.getShort() & 0xFFFF;
            } else if (chunkId == 0x61746164) {             // "data"
                pcm = new byte[chunkSize];
                bb.get(pcm);
            }
            bb.position(Math.min(next + (chunkSize & 1), bb.limit()));   // chunk 按 2 字节对齐
        }

        if (audioFormat < 0) throw new IOException("WAV 缺少 fmt 块");
        if (pcm == null) throw new IOException("WAV 缺少 data 块");
        if (audioFormat != 1) throw new IOException("仅支持 PCM 格式的 WAV(audioFormat=1),实际=" + audioFormat);
        if (bitsPerSample != 8 && bitsPerSample != 16) {
            throw new IOException("仅支持 8/16-bit PCM,实际=" + bitsPerSample + "bit");
        }
        if (channels != 1 && channels != 2) throw new IOException("仅支持 1/2 声道,实际=" + channels);
        if (sampleRate < MIN_SAMPLE_RATE || sampleRate > MAX_SAMPLE_RATE) {
            throw new IOException("采样率超出支持范围(8k–48k): " + sampleRate);
        }

        return new PcmAudio(sampleRate, toMono16(pcm, channels, bitsPerSample));
    }

    /** 归一化为 16-bit LE 单声道。 */
    private static byte[] toMono16(byte[] pcm, int channels, int bits) {
        if (channels == 1 && bits == 16) {
            if ((pcm.length & 1) == 0) return pcm;
            byte[] even = new byte[pcm.length - 1];   // 奇数字节尾巴丢掉半个采样
            System.arraycopy(pcm, 0, even, 0, even.length);
            return even;
        }
        int bytesPerSample = bits / 8;
        int frameSize = bytesPerSample * channels;
        int frames = pcm.length / frameSize;
        byte[] out = new byte[frames * 2];
        for (int f = 0; f < frames; f++) {
            int sum = 0;
            for (int ch = 0; ch < channels; ch++) {
                int off = f * frameSize + ch * bytesPerSample;
                int sample;
                if (bits == 16) {
                    sample = (short) ((pcm[off] & 0xFF) | (pcm[off + 1] << 8));
                } else {
                    sample = ((pcm[off] & 0xFF) - 128) << 8;   // 8-bit WAV 是无符号
                }
                sum += sample;
            }
            int mono = sum / channels;
            out[f * 2] = (byte) mono;
            out[f * 2 + 1] = (byte) (mono >> 8);
        }
        return out;
    }
}
