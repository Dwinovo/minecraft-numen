package com.dwinovo.numen.client.voice;

/**
 * 一段解码完成的 PCM 音频：16-bit 有符号、小端、单声道。
 * TTS 返回的 WAV 经 {@link WavCodec#decode} 统一成这个形态后进播放层。
 *
 * <p>采样率不做重采样——OpenAL 的 buffer 自带频率字段，混音时由驱动
 * 重采样，任意常见采样率（16k/22.05k/24k/32k/44.1k/48k）都可以直接喂。
 *
 * @param sampleRate 采样率（Hz）
 * @param data       PCM 数据（16-bit LE mono）
 */
public record PcmAudio(int sampleRate, byte[] data) {

    /** 这段音频的时长（毫秒）。 */
    public long durationMs() {
        return (long) (data.length / 2) * 1000L / sampleRate;
    }
}
