package com.dwinovo.numen.plugins.minecraft.e2e;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 频道同名不算数:0.1.4 发布的客户端登记着同名的名册与状态频道,格式却不一样,发过去它解不开就断线。
 * 场景扮客户端时直接登记频道,看服务端有没有发 Numen 的包。
 */
class HandshakeTest extends Scenario {

    private static final String ROSTER = "numen:companion_list";

    @Test
    void a_release_client_with_a_companion_can_rejoin_and_is_sent_nothing() throws Exception {
        String name = "e2e_old_client";
        String companion = "LegacyPet";
        Bot owner = join(name, "en_us");
        try {
            int mark = owner.mark();
            owner.command("numen player summon " + companion);
            owner.await(mark, m -> m.text().contains("Summoned") && m.text().contains(companion), WAIT, "summon");

            // 0.1.4 登记的频道:有名册与状态,没有开场。已有同伴时登记、重登之后再登记,两条路都不能发 Numen 包
            register(owner, ROSTER, "numen:numen_state", "numen:numen_locations");
            assertNothingSent(owner, companion);
            owner.close();
            waitFor(() -> !onList(name), "owner to leave");
            owner = join(name, "en_us");
            // 她的大脑在主人的客户端上:这位主人的客户端没有坐上她的座位,她就不回来
            register(owner, ROSTER, "numen:numen_state", "numen:numen_locations");
            assertNothingSent(owner, companion);
            assertTrue(!onList(companion), "she came back with an owner whose client sits at no seat of hers");
        } finally {
            if (owner.connected()) {
                owner.command("numen player delete " + companion);
                waitFor(() -> !onList(companion), "test companion to leave");
            }
        }
    }

    private static void assertNothingSent(Bot owner, String companion) {
        int mark = owner.mark();
        owner.command("numen permission show " + companion);
        owner.await(mark, m -> m.text().contains(companion), WAIT, "native permission query");
        assertTrue(owner.connected());
        assertTrue(owner.payloadChannels().stream().noneMatch(c -> c.startsWith("numen:")),
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
