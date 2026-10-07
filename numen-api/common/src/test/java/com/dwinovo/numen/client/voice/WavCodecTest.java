package com.dwinovo.numen.client.voice;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link WavCodec} 的 WAV 头解析与归一化（16-bit mono）规则。 */
class WavCodecTest {

    /** 手搓一个标准 RIFF/WAVE 文件。 */
    private static byte[] wav(int audioFormat, int channels, int sampleRate, int bits, byte[] pcm) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes());
        header.putInt(36 + pcm.length);
        header.put("WAVE".getBytes());
        header.put("fmt ".getBytes());
        header.putInt(16);
        header.putShort((short) audioFormat);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(sampleRate * channels * bits / 8);
        header.putShort((short) (channels * bits / 8));
        header.putShort((short) bits);
        header.put("data".getBytes());
        header.putInt(pcm.length);
        out.writeBytes(header.array());
        out.writeBytes(pcm);
        return out.toByteArray();
    }

    private static byte[] samples16(short... values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : values) bb.putShort(v);
        return bb.array();
    }

    @Test
    void mono16BitPassesThroughUntouched() throws IOException {
        byte[] pcm = samples16((short) 100, (short) -200, (short) 32000);
        PcmAudio audio = WavCodec.decode(wav(1, 1, 24_000, 16, pcm));
        assertEquals(24_000, audio.sampleRate());
        assertArrayEquals(pcm, audio.data());
    }

    @Test
    void commonTtsSampleRatesAreAccepted() throws IOException {
        for (int rate : new int[]{16_000, 22_050, 24_000, 32_000, 44_100, 48_000}) {
            assertEquals(rate, WavCodec.decode(wav(1, 1, rate, 16, samples16((short) 1))).sampleRate());
        }
    }

    @Test
    void stereoIsDownmixedByAveraging() throws IOException {
        // 两帧立体声:L/R 取平均。
        byte[] pcm = samples16((short) 1000, (short) 3000, (short) -500, (short) 500);
        PcmAudio audio = WavCodec.decode(wav(1, 2, 22_050, 16, pcm));
        assertArrayEquals(samples16((short) 2000, (short) 0), audio.data());
    }

    @Test
    void eightBitIsWidenedToSixteen() throws IOException {
        // 8-bit WAV 是无符号:128=静音中点→0,255→+32512 附近,0→-32768。
        byte[] pcm = {(byte) 128, (byte) 255, 0};
        PcmAudio audio = WavCodec.decode(wav(1, 1, 16_000, 8, pcm));
        assertArrayEquals(samples16((short) 0, (short) (127 << 8), (short) (-128 << 8)), audio.data());
    }

    @Test
    void durationIsComputedFromRate() throws IOException {
        byte[] pcm = new byte[24_000 * 2];   // 1 秒 @ 24kHz 16-bit mono
        assertEquals(1000, WavCodec.decode(wav(1, 1, 24_000, 16, pcm)).durationMs());
    }

    @Test
    void rejectsNonRiffBytes() {
        assertThrows(IOException.class, () -> WavCodec.decode("not a wav at all".repeat(4).getBytes()));
        assertThrows(IOException.class, () -> WavCodec.decode(new byte[10]));
        assertThrows(IOException.class, () -> WavCodec.decode(null));
    }

    @Test
    void rejectsFloatFormat() {
        assertThrows(IOException.class,
                () -> WavCodec.decode(wav(3, 1, 24_000, 32, new byte[8])));
    }

    @Test
    void rejectsUnsupportedBitDepth() {
        assertThrows(IOException.class,
                () -> WavCodec.decode(wav(1, 1, 24_000, 24, new byte[6])));
    }

    @Test
    void rejectsAbsurdSampleRates() {
        assertThrows(IOException.class, () -> WavCodec.decode(wav(1, 1, 4_000, 16, samples16((short) 1))));
        assertThrows(IOException.class, () -> WavCodec.decode(wav(1, 1, 96_000, 16, samples16((short) 1))));
    }

    @Test
    void rejectsMoreThanTwoChannels() {
        assertThrows(IOException.class,
                () -> WavCodec.decode(wav(1, 6, 24_000, 16, new byte[24])));
    }

    @Test
    void toleratesExtraChunksBeforeData() throws IOException {
        // 某些编码器会塞 LIST/INFO 块:解析要跳过未知块找到 data。
        byte[] pcm = samples16((short) 42);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer bb = ByteBuffer.allocate(200).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()).putInt(0).put("WAVE".getBytes());
        bb.put("fmt ".getBytes()).putInt(16);
        bb.putShort((short) 1).putShort((short) 1).putInt(24_000).putInt(48_000).putShort((short) 2).putShort((short) 16);
        bb.put("LIST".getBytes()).putInt(4).put("INFO".getBytes());
        bb.put("data".getBytes()).putInt(pcm.length).put(pcm);
        out.writeBytes(java.util.Arrays.copyOf(bb.array(), bb.position()));
        PcmAudio audio = WavCodec.decode(out.toByteArray());
        assertArrayEquals(pcm, audio.data());
    }
}
