package com.dwinovo.numen.e2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 原版客户端进得来:没装我们的任何东西,进服不被拒,进去以后连接是稳的。 */
class JoinTest extends Scenario {

    @Test
    void a_vanilla_client_joins_and_stays() throws Exception {
        Bot bot = join("e2e_joiner", "en_us");

        Thread.sleep(3_000);

        assertTrue(bot.connected(), "the server dropped a vanilla client after it joined");
        assertTrue(console("list").contains("e2e_joiner"), "the player is not on the server's list");
        // 没装客户端的玩家连接上没有我们的频道,服务端不该往上发我们的包
        assertTrue(bot.payloadChannels().stream().noneMatch(channel -> channel.startsWith("numen")),
                "our packets were sent to a client that has none of our channels: " + bot.payloadChannels());
    }
}
