package com.dwinovo.numen.client.voice;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmAudioStreamTest {
    @Test
    void returnsDirectBuffersAndExhaustsAtEnd() {
        PcmAudioStream stream = new PcmAudioStream(new PcmAudio(24_000, new byte[]{1, 2, 3, 4}));
        ByteBuffer first = stream.read(3);
        ByteBuffer second = stream.read(3);
        ByteBuffer end = stream.read(3);

        assertTrue(first.isDirect());
        assertTrue(second.isDirect());
        assertTrue(end.isDirect());
        assertArrayEquals(new byte[]{1, 2, 3}, bytes(first));
        assertArrayEquals(new byte[]{4}, bytes(second));
        assertEqualsZero(end);
    }

    private static byte[] bytes(ByteBuffer buffer) {
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        return out;
    }

    private static void assertEqualsZero(ByteBuffer buffer) {
        org.junit.jupiter.api.Assertions.assertEquals(0, buffer.remaining());
    }
}
