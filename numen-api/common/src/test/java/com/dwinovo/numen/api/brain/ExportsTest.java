package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.ExportChunkPayload;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 导出按段下行:每刻每人一段、每段装得进一个包、总大小不设上限、客户端按序收齐内容一致、下线作废。 */
class ExportsTest {

    private static final UUID PLAYER = UUID.randomUUID();
    private static final UUID HER = UUID.randomUUID();

    private static byte[] bytes(int size) {
        byte[] out = new byte[size];
        new Random(7).nextBytes(out);
        return out;
    }

    @Test
    void anExportBiggerThanOneMessageArrivesWholeAndIdentical() {
        byte[] zip = bytes(Wire.MESSAGE_BYTES + 12345);   // 超过单条消息的上限
        Exports exports = new Exports();
        Exports.Receiver client = new Exports.Receiver();
        List<ExportChunkPayload> sent = new ArrayList<>();
        assertTrue(exports.start(PLAYER, HER, "小焰", zip));

        for (int tick = 0; tick < 10_000 && !client.complete(); tick++) {
            exports.tick(id -> true, (id, chunk) -> {
                assertEquals(PLAYER, id);
                sent.add(chunk);
                assertTrue(client.accept(chunk), "a segment arrived out of order");
            });
        }

        assertTrue(client.complete());
        assertArrayEquals(zip, client.bytes());
        assertTrue(sent.size() > 100, "it was not cut into segments: " + sent.size());
        for (ExportChunkPayload chunk : sent) {
            int size = Wire.size(ExportChunkPayload.STREAM_CODEC, chunk, Unpooled::buffer);
            assertTrue(Wire.TO_CLIENT.holds(size), "a segment does not fit one packet: " + size);
        }
        assertTrue(exports.start(PLAYER, HER, "小焰", new byte[0]), "a finished export still holds the line");
    }

    @Test
    void eachPlayerGetsOneSegmentPerTickAndOneExportAtATime() {
        Exports exports = new Exports();
        byte[] zip = bytes(ExportChunkPayload.SEGMENT_BYTES * 3);
        assertTrue(exports.start(PLAYER, HER, "小焰", zip));
        assertFalse(exports.start(PLAYER, UUID.randomUUID(), "别的", zip), "the second goes behind the first");
        UUID other = UUID.randomUUID();
        assertTrue(exports.start(other, HER, "小焰", zip), "someone else's is independent");

        List<UUID> tickOne = new ArrayList<>();
        exports.tick(id -> true, (id, chunk) -> tickOne.add(id));

        assertEquals(Set.of(PLAYER, other), new HashSet<>(tickOne));
        assertEquals(2, tickOne.size(), "more than one segment per player in a tick");
    }

    @Test
    void aPlayerWhoLeftDropsTheExportAndAnEmptyZipIsOneSegment() {
        Exports exports = new Exports();
        exports.start(PLAYER, HER, "小焰", bytes(ExportChunkPayload.SEGMENT_BYTES * 5));
        List<ExportChunkPayload> sent = new ArrayList<>();
        exports.tick(id -> true, (id, chunk) -> sent.add(chunk));
        exports.tick(id -> false, (id, chunk) -> sent.add(chunk));
        exports.tick(id -> true, (id, chunk) -> sent.add(chunk));
        assertEquals(1, sent.size(), "it kept sending after he left");
        assertTrue(exports.start(PLAYER, HER, "小焰", new byte[0]), "the dropped export still holds the line");
        exports.tick(id -> true, (id, chunk) -> { });

        exports.start(PLAYER, HER, "小焰", new byte[0]);
        List<ExportChunkPayload> empty = new ArrayList<>();
        exports.tick(id -> true, (id, chunk) -> empty.add(chunk));
        assertEquals(1, empty.size());
        assertEquals(1, empty.get(0).total());
    }

    @Test
    void theReceiverRefusesSegmentsOutOfOrder() {
        Exports.Receiver client = new Exports.Receiver();
        assertFalse(client.accept(new ExportChunkPayload(HER, "x", 1, 3, new byte[1])), "started in the middle");
        assertTrue(client.accept(new ExportChunkPayload(HER, "x", 0, 3, new byte[1])));
        assertFalse(client.accept(new ExportChunkPayload(HER, "x", 0, 3, new byte[1])), "a repeat");
        assertFalse(client.accept(new ExportChunkPayload(HER, "x", 1, 4, new byte[1])), "the count changed");
        assertFalse(client.complete());
    }
}
