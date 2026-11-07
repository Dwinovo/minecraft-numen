package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 客户端报上同一版协议之后才收 Numen 的包。频道同名不算数:0.1.4 发布的客户端登记着同名的名册与状态频道,
 * 格式却不一样,发过去它解不开就断线。场景扮客户端时直接登记频道、收发字节。
 */
class HandshakeTest extends Scenario {

    private static final String ROSTER = "numen_api:companion_list";

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
}
