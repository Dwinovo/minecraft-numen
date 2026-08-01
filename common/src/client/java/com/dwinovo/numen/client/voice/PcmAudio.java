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

    /**
     * 峰值归一化 + 用户增益,直接改写采样。两个"永远小声"的根因都在播放层之外
     * 解决不了:TTS 返回的音频电平普遍偏低,而 MC 声音引擎把实例音量钳在 1.0,
     * 传大于 1 的 volume 毫无作用——所以增益必须烙进 PCM。先把峰值拉到满刻度
     * 的 95%,再乘用户增益(音量档 1~10 → 0.2~2.0,5 档=归一化原声),超出
     * 16-bit 范围硬截(听感即"再响一点",可接受)。近静音片段原样返回,不放大底噪。
     */
    public PcmAudio amplified(float userGain) {
        int peak = 1;
        for (int i = 0; i + 1 < data.length; i += 2) {
            int s = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            peak = Math.max(peak, Math.abs(s));
        }
        if (peak < 64) return this;
        float scale = (32767.0f * 0.95f / peak) * userGain;
        if (Math.abs(scale - 1.0f) < 0.01f) return this;
        byte[] out = new byte[data.length];
        for (int i = 0; i + 1 < data.length; i += 2) {
            int s = (short) ((data[i] & 0xFF) | (data[i + 1] << 8));
            int v = Math.clamp(Math.round(s * scale), -32768, 32767);
            out[i] = (byte) v;
            out[i + 1] = (byte) (v >> 8);
        }
        return new PcmAudio(sampleRate, out);
    }
}
