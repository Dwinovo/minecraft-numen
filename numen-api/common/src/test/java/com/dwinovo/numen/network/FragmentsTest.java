package com.dwinovo.numen.network;

import com.dwinovo.numen.network.payload.ClientCallPayload;
import com.dwinovo.numen.network.payload.ClientCallResultPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.network.payload.FragmentPayload;
import com.dwinovo.numen.network.payload.RunProgramPayload;
import com.dwinovo.numen.program.ModuleSet;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 超过单包上限的消息:切成片、对端拼回是同一条;没超的不分片;拼装中的消息有总上限和同时条数的上限,断线清残片。
 */
@Tag("mc")
class FragmentsTest {

    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final ResourceLocation KIND = ResourceLocation.fromNamespaceAndPath("numen_api", "run_program");

    private static byte[] bytes(int n) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) (i * 31 + i / 251);
        }
        return out;
    }

    private static FragmentPayload fragment(Wire wire, int id, int index, int count, byte[] chunk) {
        return new FragmentPayload(FragmentPayload.typeOf(wire), id, index, count,
                index == 0 ? Optional.of(KIND) : Optional.empty(), chunk);
    }

    /** 发送方切出来的包,逐个过线上的编解码,收件箱拼回;返回拼回的字节。 */
    private static byte[] sentAndAssembled(Wire wire, byte[] message) {
        Fragments.Inbox inbox = new Fragments.Inbox(wire);
        Fragments.Outcome last = null;
        for (CustomPacketPayload packet : Fragments.split(wire, 7, KIND, message)) {
            ByteBuf buf = Unpooled.buffer();
            FragmentPayload.codecOf(wire).encode(buf, (FragmentPayload) packet);
            assertTrue(wire.holds(buf.readableBytes()), "a fragment is one packet: " + buf.readableBytes());
            last = inbox.accept(FragmentPayload.codecOf(wire).decode(buf));
        }
        Fragments.Complete whole = assertInstanceOf(Fragments.Complete.class, last);
        assertEquals(KIND, whole.kind());
        assertEquals(0, inbox.assembling());
        return whole.bytes();
    }

    @Test
    void aMessageIsCutAndPutBackTheSameAtEveryBoundarySizeInBothDirections() {
        for (Wire wire : Wire.values()) {
            int chunk = Fragments.chunkBytes(wire);
            for (int size : new int[]{1, chunk - 1, chunk, chunk + 1, 2 * chunk, 2 * chunk + 1, 5 * chunk + 17,
                    Wire.MESSAGE_BYTES}) {
                byte[] message = bytes(size);
                assertArrayEquals(message, sentAndAssembled(wire, message), wire + " " + size);
                assertEquals((size + chunk - 1) / chunk, Fragments.split(wire, 1, KIND, message).size());
            }
        }
    }

    @Test
    void everyFragmentButTheLastIsFullAndOnlyTheFirstNamesTheKind() {
        int chunk = Fragments.chunkBytes(Wire.TO_SERVER);
        List<CustomPacketPayload> cut = Fragments.split(Wire.TO_SERVER, 3, KIND, bytes(2 * chunk + 5));
        assertEquals(3, cut.size());
        for (int i = 0; i < 3; i++) {
            FragmentPayload f = (FragmentPayload) cut.get(i);
            assertEquals(3, f.id());
            assertEquals(i, f.index());
            assertEquals(3, f.count());
            assertEquals(i == 0, f.inner().isPresent());
            assertEquals(i < 2 ? chunk : 5, f.chunk().length);
        }
    }

    /** 最坏的头(负数 id 的 VarInt 占 5 字节、很长的种类)加满一片,仍是一个包。 */
    @Test
    void aFullFragmentWithTheWorstHeaderStillFitsOnePacket() {
        ResourceLocation longest = ResourceLocation.fromNamespaceAndPath("n".repeat(20), "p".repeat(43));
        for (Wire wire : Wire.values()) {
            FragmentPayload worst = new FragmentPayload(FragmentPayload.typeOf(wire), Integer.MIN_VALUE, 0,
                    Fragments.maxCount(wire), Optional.of(longest), bytes(Fragments.chunkBytes(wire)));
            ByteBuf buf = Unpooled.buffer();
            FragmentPayload.codecOf(wire).encode(buf, worst);
            assertTrue(wire.holds(buf.readableBytes()), wire + " " + buf.readableBytes());
            FragmentPayload back = FragmentPayload.codecOf(wire).decode(buf);
            assertEquals(longest, back.inner().orElseThrow());
            assertArrayEquals(worst.chunk(), back.chunk());
        }
    }

    // ---- 发送:没超的不分片,超了才分 ----

    private static RunProgramPayload program(int codeChars, Map<String, String> bodies) {
        Map<String, String> manifest = new LinkedHashMap<>();
        bodies.forEach((hash, body) -> manifest.put("my." + hash, hash));
        return new RunProgramPayload(A, "call_1", "-".repeat(codeChars), new ModuleSet(manifest, bodies));
    }

    private static List<CustomPacketPayload> sent(RunProgramPayload p) {
        return Fragments.packets(Wire.TO_SERVER, RunProgramPayload.STREAM_CODEC, p, Unpooled::buffer);
    }

    @Test
    void aMessageThatFitsOnePacketGoesAsItselfWithNoFragmentAtAll() {
        RunProgramPayload small = program(100, Map.of());
        List<CustomPacketPayload> packets = sent(small);
        assertEquals(1, packets.size());
        assertSame(small, packets.get(0));

        // 正好装满一个包的边界:还是它自己;多一个字节才分片
        int fill = Wire.TO_SERVER.bytes() - 200;
        while (Wire.size(RunProgramPayload.STREAM_CODEC, program(fill, Map.of()), Unpooled::buffer) < Wire.TO_SERVER.bytes()) {
            fill++;
        }
        RunProgramPayload exact = program(fill, Map.of());
        assertEquals(Wire.TO_SERVER.bytes(), Wire.size(RunProgramPayload.STREAM_CODEC, exact, Unpooled::buffer));
        assertSame(exact, sent(exact).get(0));
        RunProgramPayload over = program(fill + 1, Map.of());
        assertTrue(sent(over).get(0) instanceof FragmentPayload, "one byte over the packet is cut");
    }

    @Test
    void aBigProgramWithABigModuleGoesUpAsFragmentsAndComesBackEqual() {
        String module = "-- A big one.\nlocal M = {}\n" + "-- filler line\n".repeat(30_000) + "return M\n";
        RunProgramPayload run = program(50, Map.of("aaaaaaaaaaaa", module));
        assertTrue(module.length() > 400_000);
        assertEquals(run, Fragments.crossed(Wire.TO_SERVER, RunProgramPayload.STREAM_CODEC, run));
        List<CustomPacketPayload> packets = sent(run);
        assertTrue(packets.size() > 10, "" + packets.size());
        assertTrue(packets.stream().allMatch(p -> p instanceof FragmentPayload));
    }

    @Test
    void aBigRequestGoesDownAsFragmentsWhenItIsOverTheDownwardPacketAndNotBefore() {
        String code = "x".repeat(Wire.TO_CLIENT.bytes() + 10);
        ClientCallPayload big = new ClientCallPayload(A, "call_1#2", "numen.module.save", "{\"code\":\"" + code + "\"}");
        assertEquals(big, Fragments.crossed(Wire.TO_CLIENT, ClientCallPayload.STREAM_CODEC, big));
        assertTrue(Fragments.packets(Wire.TO_CLIENT, ClientCallPayload.STREAM_CODEC, big, Unpooled::buffer).size() > 1);

        ClientCallPayload fits = new ClientCallPayload(A, "call_1#3", "numen.module.save", "{\"code\":\"x\"}");
        assertEquals(List.of(fits),
                Fragments.packets(Wire.TO_CLIENT, ClientCallPayload.STREAM_CODEC, fits, Unpooled::buffer));
    }

    @Test
    void aBigAnswerGoesUpAsFragmentsWithItsNewModules() {
        String reply = "{\"success\":true,\"data\":{\"code\":\"" + "y".repeat(100_000) + "\"}}";
        ClientCallResultPayload answer = new ClientCallResultPayload(A, "call_1#2", reply,
                Optional.of(new ModuleSet(Map.of("my.pit", "bbbbbbbbbbbb"), Map.of("bbbbbbbbbbbb", "z".repeat(60_000)))));
        assertEquals(answer, Fragments.crossed(Wire.TO_SERVER, ClientCallResultPayload.STREAM_CODEC, answer));
    }

    /** 内容有界的包不分片:装不下是填它的代码错了,仍然当场抛(见 WireTest)。 */
    @Test
    void aPayloadThatDoesNotDeclareFragmentableIsStillOnePacketOrAnError() {
        CompanionListPayload wrong = new CompanionListPayload("w".repeat(Wire.TO_CLIENT.bytes()), List.of());
        assertThrows(IllegalStateException.class, () -> Fragments.packets(Wire.TO_CLIENT,
                CompanionListPayload.STREAM_CODEC, wrong, () -> new net.minecraft.network.RegistryFriendlyByteBuf(
                        Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY)));
    }

    // ---- 总上限 ----

    @Test
    void aMessageOverTheTotalLimitIsNotCutByTheSender() {
        assertThrows(IllegalStateException.class,
                () -> Fragments.split(Wire.TO_SERVER, 1, KIND, bytes(Wire.MESSAGE_BYTES + 1)));
        assertTrue(Wire.TO_SERVER.carries(Wire.MESSAGE_BYTES));
        assertTrue(!Wire.TO_SERVER.carries(Wire.MESSAGE_BYTES + 1));
    }

    @Test
    void aMessageThatDeclaresMoreFragmentsThanTheTotalLimitCouldHoldIsRefusedAtItsFirstFragment() {
        Wire wire = Wire.TO_SERVER;
        Fragments.Inbox inbox = new Fragments.Inbox(wire);
        byte[] full = bytes(Fragments.chunkBytes(wire));
        int count = Fragments.maxCount(wire) + 1;
        Fragments.Outcome out = inbox.accept(fragment(wire, 1, 0, count, full));
        assertInstanceOf(Fragments.Rejected.class, out);
        assertEquals(0, inbox.assembling());
    }

    @Test
    void aMessageThatRunsPastTheTotalLimitWhileAssemblingIsDroppedAtOnce() {
        Wire wire = Wire.TO_SERVER;
        Fragments.Inbox inbox = new Fragments.Inbox(wire);
        byte[] full = bytes(Fragments.chunkBytes(wire));
        int count = Fragments.maxCount(wire);
        Fragments.Outcome last = null;
        int sentFragments = 0;
        for (int i = 0; i < count; i++) {
            last = inbox.accept(fragment(wire, 9, i, count, full));
            sentFragments++;
            if (last instanceof Fragments.Rejected) {
                break;
            }
        }
        assertInstanceOf(Fragments.Rejected.class, last);
        assertEquals(count, sentFragments, "every fragment but the last that would not fit was taken");
        assertEquals(0, inbox.assembling(), "what was assembled so far is thrown away");
        assertInstanceOf(Fragments.Pending.class, inbox.accept(fragment(wire, 9, 1, count, full)),
                "the rest of a dropped message has nowhere to go");
    }

    @Test
    void fragmentsThatAreNotInOrderOrNotFullDropTheirMessage() {
        Wire wire = Wire.TO_SERVER;
        int chunk = Fragments.chunkBytes(wire);
        Fragments.Inbox inbox = new Fragments.Inbox(wire);
        assertInstanceOf(Fragments.Pending.class, inbox.accept(fragment(wire, 1, 0, 3, bytes(chunk))));
        assertInstanceOf(Fragments.Rejected.class, inbox.accept(fragment(wire, 1, 2, 3, bytes(4))), "skipped one");
        assertEquals(0, inbox.assembling());

        assertInstanceOf(Fragments.Rejected.class, inbox.accept(fragment(wire, 2, 0, 3, bytes(1))),
                "a first fragment of one byte is not full");
        assertInstanceOf(Fragments.Pending.class, inbox.accept(fragment(wire, 3, 0, 2, bytes(chunk))));
        assertInstanceOf(Fragments.Rejected.class, inbox.accept(fragment(wire, 3, 0, 2, bytes(chunk))),
                "the same id began again");
        assertEquals(0, inbox.assembling());
        assertInstanceOf(Fragments.Rejected.class, inbox.accept(fragment(wire, 4, 0, 0, bytes(chunk))));
        assertInstanceOf(Fragments.Rejected.class, inbox.accept(fragment(wire, 4, 0, 1, new byte[0])));
    }

    // ---- 同时拼装的条数、断线 ----

    @Test
    void aConnectionAssemblesAtMostSomeMessagesAtOnceAndFreesASlotWhenOneIsDone() {
        Wire wire = Wire.TO_SERVER;
        int chunk = Fragments.chunkBytes(wire);
        Fragments.Inbox inbox = new Fragments.Inbox(wire);
        for (int id = 0; id < Wire.ASSEMBLING; id++) {
            assertInstanceOf(Fragments.Pending.class, inbox.accept(fragment(wire, id, 0, 2, bytes(chunk))));
        }
        assertEquals(Wire.ASSEMBLING, inbox.assembling());
        assertInstanceOf(Fragments.Rejected.class, inbox.accept(fragment(wire, 100, 0, 2, bytes(chunk))),
                "one more is refused");
        assertEquals(Wire.ASSEMBLING, inbox.assembling(), "the ones already coming are kept");

        // 交错着的消息各自拼得回来
        for (int id = Wire.ASSEMBLING - 1; id >= 0; id--) {
            Fragments.Complete done = assertInstanceOf(Fragments.Complete.class,
                    inbox.accept(fragment(wire, id, 1, 2, bytes(3))));
            assertEquals(chunk + 3, done.bytes().length);
        }
        assertEquals(0, inbox.assembling());
        assertInstanceOf(Fragments.Pending.class, inbox.accept(fragment(wire, 100, 0, 2, bytes(chunk))));
    }

    @Test
    void aDisconnectDropsWhatWasNotFinished() {
        Wire wire = Wire.TO_CLIENT;
        int chunk = Fragments.chunkBytes(wire);
        Fragments.Inbox inbox = new Fragments.Inbox(wire);
        inbox.accept(fragment(wire, 1, 0, 2, bytes(chunk)));
        inbox.accept(fragment(wire, 2, 0, 2, bytes(chunk)));
        assertEquals(2, inbox.assembling());
        inbox.clear();
        assertEquals(0, inbox.assembling());
        assertInstanceOf(Fragments.Pending.class, inbox.accept(fragment(wire, 1, 1, 2, bytes(3))),
                "the second half of a message that was dropped completes nothing");
    }

    @Test
    void theFragmentsOfSeveralSendersInterleavedGiveBackEachMessage() {
        Wire wire = Wire.TO_SERVER;
        byte[] one = bytes(3 * Fragments.chunkBytes(wire) + 1);
        byte[] two = bytes(2 * Fragments.chunkBytes(wire) + 9);
        List<CustomPacketPayload> a = Fragments.split(wire, 1, KIND, one);
        List<CustomPacketPayload> b = Fragments.split(wire, 2, KIND, two);
        Fragments.Inbox inbox = new Fragments.Inbox(wire);
        List<Fragments.Complete> done = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            for (List<CustomPacketPayload> side : List.of(a, b)) {
                if (i < side.size() && inbox.accept((FragmentPayload) side.get(i)) instanceof Fragments.Complete c) {
                    done.add(c);
                }
            }
        }
        assertEquals(2, done.size());
        assertArrayEquals(two, done.get(0).bytes());   // b 的片少,先收齐
        assertArrayEquals(one, done.get(1).bytes());
    }
}
