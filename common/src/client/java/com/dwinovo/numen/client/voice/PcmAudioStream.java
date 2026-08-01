package com.dwinovo.numen.client.voice;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 把一段解码好的 {@link PcmAudio} 适配成声音引擎的 {@link AudioStream}。
 * 引擎在它的 "Sound engine" 线程上按需 {@code read(size)} 拉数据、切成
 * OpenAL streaming buffer；我们手里是完整字节,永不阻塞。
 *
 * <p>关键约束：返回的 ByteBuffer 会被直接交给 {@code AL10.alBufferData}
 * （LWJGL 要求 direct buffer）,所以这里必须 {@code allocateDirect}。
 * 数据耗尽后返回空 direct buffer——引擎排空已入队的 buffer 后源进入
 * STOPPED,声音自然结束。
 */
final class PcmAudioStream implements AudioStream {

    private final AudioFormat format;
    private final byte[] data;
    private int pos;

    PcmAudioStream(PcmAudio audio) {
        // 16-bit LE 单声道——WavCodec 已经归一化,这里如实上报格式。
        this.format = new AudioFormat(audio.sampleRate(), 16, 1, true, false);
        this.data = audio.data();
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) {
        int n = Math.min(size, data.length - pos);
        ByteBuffer out = ByteBuffer.allocateDirect(Math.max(n, 0)).order(ByteOrder.nativeOrder());
        if (n > 0) {
            out.put(data, pos, n);
            pos += n;
        }
        out.flip();
        return out;
    }

    @Override
    public void close() {
        pos = data.length;
    }
}
