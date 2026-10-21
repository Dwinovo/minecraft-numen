package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.dwinovo.numen.e2e.FakeModel.say;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Paper 的可选频道不能只凭同名就认作同版:0.1.4 发布包没有 hello,名册每行也没有 removedFor。 */
class PaperClientCompatibilityTest extends Scenario {

    private static final String ROSTER = "numen_api:companion_list";
    private static final String ACK = "numen_api:hello_ack";
    private static final String LIBRARY = "numen_api:library";

    @BeforeEach
    void paperChannelRegistration() throws Exception {
        assumeTrue("paper".equals(System.getProperty("e2e.platform")),
                "tests Bukkit's optional plugin-channel registration");
    }

    @Test
    void a_release_client_with_a_companion_can_rejoin_without_an_incompatible_roster() throws Exception {
        String name = "e2e_old_client";
        String companion = "LegacyPet";
        Bot owner = join(name, "en_us");
        try {
            int mark = owner.mark();
            owner.command("numen player summon " + companion);
            owner.await(mark, m -> m.text().contains("Summoned") && m.text().contains(companion), WAIT, "summon");
            String originalUuid = console("data get entity " + companion + " UUID");

            // 已有同伴时的频道登记,以及重登后的补发,两条路径都不能发新版名册。
            register(owner, ROSTER, "numen_api:numen_state", "numen_api:numen_locations");
            assertLegacyConnection(owner, companion);
            owner.close();
            waitFor(() -> !ownerOnList(name), "owner to leave");
            owner = join(name, "en_us");
            register(owner, ROSTER, "numen_api:numen_state", "numen_api:numen_locations");
            assertLegacyConnection(owner, companion);
            assertEquals(originalUuid, console("data get entity " + companion + " UUID"), "rejoining must keep the body");
        } finally {
            if (owner.connected()) {
                owner.command("numen player despawn " + companion);
                waitFor(() -> !ownerOnList(companion), "test companion to leave");
            }
        }
    }

    @Test
    void late_handshake_channel_registration_bootstraps_once_and_model_check_answers() throws Exception {
        Bot owner = join("e2e_new_client", "en_us");
        register(owner, ROSTER);
        barrier(owner);
        assertTrue(owner.payloads(ROSTER).isEmpty(), "roster-only registration is not protocol compatibility");

        register(owner, ACK, LIBRARY);
        waitFor(() -> owner.payloads(ROSTER).size() == 1, "modern roster bootstrap");
        register(owner, ACK, ROSTER);
        barrier(owner);
        assertEquals(1, owner.payloads(ROSTER).size(), "registration events must not repeat the bootstrap");

        // 读服务端握手版本再用同版报到,此场景不把版本号复制成另一个常量。
        owner.payload("numen_api:hello", new byte[]{0});
        waitFor(() -> !owner.payloads(ACK).isEmpty(), "protocol acknowledgement");
        int protocol = readVarInt(new ByteArrayInputStream(owner.payloads(ACK).getFirst()));
        ByteArrayOutputStream hello = new ByteArrayOutputStream();
        writeVarInt(hello, protocol);
        owner.payload("numen_api:hello", hello.toByteArray());
        waitFor(() -> owner.payloads(ACK).size() == 2, "matching handshake");

        MODEL.whenHeard("ping", say("OK"));
        String draft = "{\"draft\":{\"provider\":\"custom\",\"protocol\":\"chat\",\"model\":\"e2e-fake\","
                + "\"base_url\":\"http://127.0.0.1:" + SPEC.modelPort() + "/v1\"},\"key\":\"" + SPEC.modelKey() + "\"}";
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        writeText(request, "provider-check");
        writeText(request, "run");
        writeText(request, draft);
        writeVarInt(request, 42);
        owner.payload("numen_api:library_request", request.toByteArray());
        waitFor(() -> owner.payloads(LIBRARY).stream().map(PaperClientCompatibilityTest::libraryReply)
                .anyMatch(r -> r.request == 42 && r.entries.contains("\"ok\"")), "final model-check reply");
        List<LibraryReply> replies = owner.payloads(LIBRARY).stream()
                .map(PaperClientCompatibilityTest::libraryReply).filter(r -> r.kind.equals("provider-check")).toList();
        assertTrue(replies.stream().anyMatch(r -> r.request == 0 && r.entries.contains("checking")));
        assertTrue(replies.stream().anyMatch(r -> r.request == 42 && r.problem.isEmpty()));
        assertTrue(owner.connected());
    }

    private static void assertLegacyConnection(Bot owner, String companion) {
        int mark = owner.mark();
        owner.command("numen permission mode " + companion);
        owner.await(mark, m -> m.text().contains(companion), WAIT, "native permission query");
        assertTrue(owner.connected());
        assertTrue(owner.payloadChannels().stream().noneMatch(c -> c.startsWith("numen_api:")),
                "legacy optional channels received newer packets: " + owner.payloadChannels());
    }

    private static void register(Bot bot, String... channels) {
        bot.payload("minecraft:register", String.join("\0", channels).getBytes(StandardCharsets.UTF_8));
    }

    private static void barrier(Bot bot) {
        int mark = bot.mark();
        bot.command("numen permission rules list");
        bot.await(mark, m -> m.text().contains("deny:") && m.text().contains("allow:")
                && m.text().contains("ask:"), WAIT, "ordered command response");
    }

    private static boolean ownerOnList(String name) {
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
