package com.dwinovo.numen.client.stt;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 采集/上传共用的音频格式与编码。16kHz/16-bit/单声道 PCM——Whisper 等 ASR 的标准输入。 */
public final class SttAudio {

    private SttAudio() {}

    /** 16000 Hz, 16-bit, mono, signed, little-endian。 */
    public static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

    /** 把裸 PCM 裹成 WAV 容器(批量上传用)。 */
    public static byte[] pcmToWav(byte[] pcm) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int frames = pcm.length / FORMAT.getFrameSize();
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), FORMAT, frames)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, out);
        }
        return out.toByteArray();
    }
}
