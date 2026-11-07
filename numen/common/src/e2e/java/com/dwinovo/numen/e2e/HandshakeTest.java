package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 服务端先开口、只说协议号,客户端报上同一版之后才收别的。频道同名不算数:0.1.4 发布的客户端登记着同名的名册与状态频道,
 * 格式却不一样,发过去它解不开就断线。场景扮客户端时直接登记频道、收发字节。
 */
class HandshakeTest extends Scenario {

    private static final String OPENER = "numen_api:server_hello";
    private static final String HELLO = "numen_api:hello";
    private static final String CAPS = "numen_api:client_caps";
    private static final String ACK = "numen_api:hello_ack";
    private static final String ROSTER = "numen_api:companion_list";
    private static final String LIBRARY = "numen_api:library";

    @Test
    void a_release_client_with_a_companion_can_rejoin_and_is_sent_nothing() throws Exception {
        String name = "e2e_old_client";
        String companion = "LegacyPet";
        Bot owner = join(name, "en_us");
        try {
            int mark = owner.mark();
            owner.command("numen player summon " + companion);
            owner.await(mark, m -> m.text().contains("Summoned") && m.text().contains(companion), WAIT, "summon");
            String originalUuid = console("data get entity " + companion + " UUID");

            // 0.1.4 登记的频道:有名册与状态,没有开场。已有同伴时登记、重登之后再登记,两条路都不能发 Numen 包
            register(owner, ROSTER, "numen_api:numen_state", "numen_api:numen_locations");
            assertNothingSent(owner, companion);
            owner.close();
            waitFor(() -> !onList(name), "owner to leave");
            owner = join(name, "en_us");
            register(owner, ROSTER, "numen_api:numen_state", "numen_api:numen_locations");
            assertNothingSent(owner, companion);
            assertEquals(originalUuid, console("data get entity " + companion + " UUID"), "rejoining must keep the body");
        } finally {
            if (owner.connected()) {
                owner.command("numen player despawn " + companion);
                waitFor(() -> !onList(companion), "test companion to leave");
            }
        }
    }

    @Test
    void the_server_opens_once_and_serves_only_a_matching_hello() throws Exception {
        // 场景扮客户端靠游戏阶段登记频道(minecraft:register):Paper 认,NeoForge 服的频道只认配置阶段协商出来的
        assumeTrue("paper".equals(System.getProperty("e2e.platform")), "channels registered in play count on Paper only");
        Bot owner = join("e2e_new_client", "en_us");
        register(owner, ROSTER);
        barrier(owner);
        assertTrue(owner.payloads(ROSTER).isEmpty(), "roster-only registration is not protocol compatibility");

        register(owner, OPENER, ACK, LIBRARY);
        waitFor(() -> owner.payloads(OPENER).size() == 1, "the opener");
        register(owner, OPENER, ROSTER);
        barrier(owner);
        assertEquals(1, owner.payloads(OPENER).size(), "registration events must not repeat the opener");
        assertTrue(owner.payloads(ROSTER).isEmpty(), "the roster waits for the hello");

        // 协议号从开场读,不在场景里另抄一份
        ByteArrayInputStream opener = new ByteArrayInputStream(owner.payloads(OPENER).getFirst());
        int protocol = readVarInt(opener);
        assertEquals(0, opener.available(), "the opener carries the protocol number only");

        owner.payload(HELLO, varInt(protocol + 1));
        barrier(owner);
        assertTrue(owner.payloads(ACK).isEmpty() && owner.payloads(ROSTER).isEmpty(),
                "a hello of another protocol was answered");

        // 报到要版本与能力两样都到齐:只报了版本还不算客户端,不回答
        owner.payload(HELLO, varInt(protocol));
        barrier(owner);
        assertTrue(owner.payloads(ACK).isEmpty() && owner.payloads(ROSTER).isEmpty(),
                "the answer came before the client said what it provides");

        owner.payload(CAPS, varInt(0));   // 不提供任何目标模组只在客户端的联动
        waitFor(() -> owner.payloads(ACK).size() == 1 && owner.payloads(ROSTER).size() == 1, "the answer and the roster");

        MODEL.whenHeard("ping", say("OK"));
        String draft = "{\"draft\":{\"provider\":\"custom\",\"protocol\":\"chat\",\"model\":\"e2e-fake\","
                + "\"base_url\":\"http://127.0.0.1:" + SPEC.modelPort() + "/v1\"},\"key\":\"" + SPEC.modelKey() + "\"}";
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        writeText(request, "provider-check");
        writeText(request, "run");
        writeText(request, draft);
        writeVarInt(request, 42);
        owner.payload("numen_api:library_request", request.toByteArray());
        waitFor(() -> owner.payloads(LIBRARY).stream().map(HandshakeTest::libraryReply)
                .anyMatch(r -> r.request == 42 && r.entries.contains("\"ok\"")), "final model-check reply");
        List<LibraryReply> replies = owner.payloads(LIBRARY).stream()
                .map(HandshakeTest::libraryReply).filter(r -> r.kind.equals("provider-check")).toList();
        assertTrue(replies.stream().anyMatch(r -> r.request == 0 && r.entries.contains("checking")));
        assertTrue(replies.stream().anyMatch(r -> r.request == 42 && r.problem.isEmpty()));
        assertTrue(owner.connected());
    }

    private static void assertNothingSent(Bot owner, String companion) {
        int mark = owner.mark();
        owner.command("numen permission mode " + companion);
        owner.await(mark, m -> m.text().contains(companion), WAIT, "native permission query");
        assertTrue(owner.connected());
        assertTrue(owner.payloadChannels().stream().noneMatch(c -> c.startsWith("numen_api:")),
                "a client that never said hello was sent: " + owner.payloadChannels());
    }

    private static void register(Bot bot, String... channels) {
        bot.payload("minecraft:register", String.join("\0", channels).getBytes(StandardCharsets.UTF_8));
    }

    /** 指令的回话按序到达:它到了,先于它发出的包也都到了。 */
    private static void barrier(Bot bot) {
        int mark = bot.mark();
        bot.command("numen permission rules list");
        bot.await(mark, m -> m.text().contains("deny:") && m.text().contains("allow:")
                && m.text().contains("ask:"), WAIT, "ordered command response");
    }

    private static boolean onList(String name) {
        try {
            return console("list").contains(name);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void waitFor(BooleanSupplier condition, String what) throws InterruptedException {
        long until = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "timed out waiting for " + what);
            Thread.sleep(50);
        }
    }

    private record LibraryReply(String kind, String entries, String problem, int request) {}

    private static LibraryReply libraryReply(byte[] bytes) {
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        LibraryReply reply = new LibraryReply(readText(in), readText(in), readText(in), readVarInt(in));
        assertEquals(0, in.available(), "unread library bytes");
        return reply;
    }

    private static String readText(ByteArrayInputStream in) {
        int size = readVarInt(in);
        byte[] bytes = new byte[size];
        assertEquals(size, in.read(bytes, 0, size));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readVarInt(ByteArrayInputStream in) {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int value = in.read();
            assertTrue(value >= 0, "truncated varint");
            result |= (value & 127) << shift;
            if ((value & 128) == 0) return result;
        }
        throw new AssertionError("oversized varint");
    }

    private static byte[] varInt(int value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, value);
        return out.toByteArray();
    }

    private static void writeText(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        do {
            int next = value & 127;
            value >>>= 7;
            out.write(next | (value == 0 ? 0 : 128));
        } while (value != 0);
    }
}
