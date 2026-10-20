package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.network.payload.ExportChunkPayload;
import com.dwinovo.numen.api.network.payload.SkinHandoverPayload;
import com.dwinovo.numen.api.skin.SkinProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 连专用服务器的玩家的旧皮肤原图交还到他本机:每刻一段、一张发完旧记录就删、没发完的留着、客户端按序拼回。 */
class SkinHandoverTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @TempDir
    Path dir;

    private Path root() {
        return dir.resolve("skin");
    }

    private void entry(String space, String name, byte[] png) throws IOException {
        Path file = root().resolve(space).resolve(name + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, new SkinProfile("classic", "v", "s", png).write(), StandardCharsets.UTF_8);
    }

    private static byte[] image(int size) {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (i * 31);
        }
        return out;
    }

    @Test
    void everyImageArrivesWholeAndItsOldRecordIsGoneOnceItWasSent() throws IOException {
        byte[] big = image(ExportChunkPayload.SEGMENT_BYTES * 2 + 100);
        byte[] small = image(10);
        entry("my", "大", big);
        entry("my", "小", small);
        SkinHandover handover = new SkinHandover();
        assertEquals(2, handover.offer(PLAYER, root()));
        List<SkinHandoverPayload> sent = new ArrayList<>();
        Segments.Assembler bigParts = new Segments.Assembler();
        Segments.Assembler smallParts = new Segments.Assembler();

        for (int tick = 0; tick < 20; tick++) {
            handover.tick(id -> true, (id, segment) -> {
                sent.add(segment);
                assertTrue((segment.name().equals("大") ? bigParts : smallParts)
                        .accept(segment.index(), segment.total(), segment.bytes()));
            });
        }

        assertEquals(4, sent.size(), "大的三段,小的一段,每刻一段");
        assertArrayEquals(big, bigParts.bytes());
        assertArrayEquals(small, smallParts.bytes());
        assertFalse(Files.exists(root()), "发完的旧记录删了,空了的目录一起收掉");
    }

    @Test
    void whatWasNotFinishedStaysForNextTimeAndOfferingAgainDoesNotQueueItTwice() throws IOException {
        entry("my", "大", image(ExportChunkPayload.SEGMENT_BYTES * 3));
        SkinHandover handover = new SkinHandover();
        handover.offer(PLAYER, root());
        List<SkinHandoverPayload> sent = new ArrayList<>();
        handover.tick(id -> true, (id, segment) -> sent.add(segment));

        assertEquals(0, handover.offer(PLAYER, root()), "已经排着的不重复排");
        handover.tick(id -> false, (id, segment) -> sent.add(segment));   // 他下线了
        handover.tick(id -> true, (id, segment) -> sent.add(segment));

        assertEquals(1, sent.size(), "下线后不再发");
        assertTrue(Files.exists(root().resolve("my").resolve("大.json")), "没发完的留着");
        assertEquals(1, handover.offer(PLAYER, root()), "他再连上,从头接着发");
    }

    @Test
    void anOldRecordWithoutAnImageHasNothingToHandOverAndIsDropped() throws IOException {
        entry("my", "借来的", new byte[0]);
        SkinHandover handover = new SkinHandover();

        assertEquals(0, handover.offer(PLAYER, root()));

        assertFalse(Files.exists(root()));
        assertEquals(0, handover.offer(PLAYER, root()), "没有库就什么也不排");
    }
}
