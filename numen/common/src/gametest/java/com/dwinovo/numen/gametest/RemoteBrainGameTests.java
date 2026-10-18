package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.agent.loop.HaltReason;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.brain.CompanionBrains;
import com.dwinovo.numen.api.brain.CompanionLoop;
import com.dwinovo.numen.api.brain.CompanionLoops;
import com.dwinovo.numen.api.brain.Mind;
import com.dwinovo.numen.api.brain.OwnerPresence;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.home.Homes;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.mcp.server.McpConfig;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.mcp.server.McpToken;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.dwinovo.numen.gametest.GameTestKit.spawnAt;
import static com.dwinovo.numen.gametest.GameTestKit.succeedWhen;

/**
 * (端点宿主是进程里唯一的一个,所以每条用例各占一个批次,不并行。)
 *
 * <p>远程端点接上真的服务端大脑:主人在不在由 {@code OwnerPresence} 一处算——人在游戏里,或他的外部大脑令牌会话连着。
 * 外部大脑是进程内的真 HTTP 客户端,走令牌、会话、断开;同伴的大脑是 {@code CompanionBrains} 接的产品线路
 * (不是用例手填的"主人在不在"),所以驾驶席与待命都是产品里真实算出来的。
 */
public class RemoteBrainGameTests {

    /** 一只带产品线路大脑的同伴,和一个扮外部大脑的客户端。 */
    private static final class Rig {
        final MinecraftServer server;
        final NumenPlayer companion;
        final UUID owner;
        final CompanionLoop loop;
        final McpTestClient brain;
        final List<HaltReason> halts = new CopyOnWriteArrayList<>();

        /** @param ownerPlayer 扮主人的在场玩家;null = 主人不在游戏里 */
        Rig(GameTestHelper helper, String name, ServerPlayer ownerPlayer) throws Exception {
            this(helper, name, ownerPlayer, null, McpToken.Role.BRAIN);
        }

        /**
         * @param mind 她的大脑用的"模型";null = 产品线路接的真模型(没绑档案,不会真去想)
         * @param role 扮外部大脑还是渠道的令牌身份
         */
        Rig(GameTestHelper helper, String name, ServerPlayer ownerPlayer, Mind mind, McpToken.Role role)
                throws Exception {
            this.server = helper.getLevel().getServer();
            this.companion = spawnAt(helper, name, new BlockPos(3, 2, 3), false);
            this.owner = ownerPlayer != null ? ownerPlayer.getUUID() : UUID.randomUUID();
            companion.setOwnerUuid(owner);
            CompanionRegistry.get(server).put(companion.getUUID(), new CompanionRegistry.Entry(name, owner,
                    Level.OVERWORLD, companion.blockPosition()));
            if (mind == null) {
                CompanionBrains.reset(server, owner);   // 换掉 spawnAt 配的不思考的脑,换成产品线路接的
            } else {
                // 同样的产品线路,只是模型换成用例脚本化的;"主人在不在""驾驶席"仍是产品里真算的
                CompanionBrains.create(server, companion.getUUID(), owner, Homes.of(server).companion(companion.getUUID()),
                        mind, Replies.link(server, owner), () -> OwnerPresence.present(server, owner),
                        () -> McpMode.instance().driving(owner, companion.getUUID()));
            }
            this.loop = CompanionLoops.get(companion.getUUID()).orElseThrow();
            loop.subscribe(event -> {
                if (event instanceof LoopEvent.Halted halted) {
                    halts.add(halted.reason());
                }
            });
            McpMode mcp = McpMode.instance();
            int port;
            try (var probe = new java.net.ServerSocket(0)) {
                port = probe.getLocalPort();
            }
            if (!mcp.applySettings(McpConfig.LOOPBACK, port, 30, List.of()) || !mcp.setEnabled(true)) {
                throw new IllegalStateException("the endpoint did not start: " + mcp.lastError());
            }
            this.brain = new McpTestClient(port, mcp.tokens().issue(owner, role,
                    McpToken.Scope.ALL, "gametest", 0).secret());
        }

        Hold hold() {
            return loop.status().hold();
        }

        long disconnects() {
            return halts.stream().filter(r -> r == HaltReason.DISCONNECT).count();
        }

        void close() {
            McpMode.instance().setEnabled(false);
            CompanionLoops.dismissed(server, companion.getUUID());
            CompanionRegistry.get(server).remove(companion.getUUID());
            CompanionFactory.despawn(server, companion);
        }
    }

    /** 主人不在游戏里:她待命;他的外部大脑连上,驾驶席换人、她不再待命;它断开,和登出一样收拾一次回到待命。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_remote_1")
    public static void an_external_brain_connecting_wakes_her_and_leaving_puts_her_away_again(GameTestHelper helper)
            throws Exception {
        Rig her = new Rig(helper, "gametest_remote_away", null);
        helper.assertTrue(her.hold() == Hold.AWAY, "not away while nobody is here: " + her.hold());
        helper.assertTrue(her.disconnects() == 0, "collected before anyone had been here: " + her.halts);

        her.brain.connect();

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(her.hold() == Hold.EXTERNAL, "the external brain did not take the seat: " + her.hold());
            helper.assertTrue(her.disconnects() == 0, "she was collected while the brain was connected");
            try {
                her.brain.disconnect();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(her.hold() == Hold.AWAY, "not put away after the brain left: " + her.hold());
            helper.assertTrue(her.disconnects() == 1, "not collected once after the brain left: " + her.halts);
            her.close();
        });
    }

    /** 主人在游戏里:外部大脑断开不收拾,驾驶席交还内脑,她不待命。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_remote_2")
    public static void the_brain_leaving_while_the_owner_is_here_hands_the_seat_back_without_collecting(
            GameTestHelper helper) throws Exception {
        ServerPlayer owner = GameTestKit.presentPlayer(helper, null, "gametest_remote_owner");
        Rig her = new Rig(helper, "gametest_remote_here", owner);
        her.brain.connect();

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(her.hold() == Hold.EXTERNAL, "the external brain did not take the seat: " + her.hold());
            try {
                her.brain.disconnect();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(her.hold() != Hold.EXTERNAL && her.hold() != Hold.AWAY,
                    "the seat did not come back to her own brain: " + her.hold());
            helper.assertTrue(her.disconnects() == 0, "collected although the owner is in the game: " + her.halts);
            her.close();
            GameTestKit.leave(owner);
            helper.succeed();
        });
    }

    /** 人先下线、外部大脑还连着:他还在,不收拾;外部大脑再断开,才收拾。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_remote_3")
    public static void the_player_leaving_is_not_leaving_while_his_external_brain_is_connected(GameTestHelper helper)
            throws Exception {
        ServerPlayer owner = GameTestKit.presentPlayer(helper, null, "gametest_remote_leaver");
        Rig her = new Rig(helper, "gametest_remote_stays", owner);
        her.brain.connect();
        helper.runAfterDelay(3, () -> GameTestKit.leave(owner));

        helper.runAfterDelay(10, () -> {
            helper.assertTrue(her.hold() == Hold.EXTERNAL, "she stopped driving when only the player left: "
                    + her.hold());
            helper.assertTrue(her.disconnects() == 0, "collected while his brain was still connected: " + her.halts);
            try {
                her.brain.disconnect();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(her.hold() == Hold.AWAY, "not put away after both were gone: " + her.hold());
            helper.assertTrue(her.disconnects() == 1, "not collected once: " + her.halts);
            her.close();
        });
    }

    private static com.dwinovo.numen.api.permission.ConsentItem oakLog() {
        return new com.dwinovo.numen.api.permission.ConsentItem(com.dwinovo.numen.permission.Verb.BREAK,
                new BlockPos(1, 64, 1), com.dwinovo.numen.api.permission.ConsentItem.NO_ENTITY, "oak_log", null,
                net.minecraft.network.chat.Component.translatable("block.minecraft.oak_log"), "break(owner)",
                "belongs to the owner", net.minecraft.network.chat.Component.literal("belongs to the owner"), false,
                com.dwinovo.numen.permission.Rule.parse("break(minecraft:oak_log)",
                        com.dwinovo.numen.api.permission.Groups.VOCABULARY));
    }

    private static com.google.gson.JsonObject accept(String decision) {
        com.google.gson.JsonObject content = new com.google.gson.JsonObject();
        content.addProperty("decision", decision);
        com.google.gson.JsonObject result = new com.google.gson.JsonObject();
        result.addProperty("action", "accept");
        result.add("content", content);
        return result;
    }

    /**
     * 主人不在游戏里、外部大脑驾驶着她:要问主人的事不当场按"主人不在"拒绝,而是经外部大脑的客户端用 elicitation
     * 问坐在电脑前的人;他的选择走和游戏里答复框同一个 {@code ConsentDesk.reply}。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_remote_4")
    public static void a_consent_goes_to_the_human_at_the_external_brains_client(GameTestHelper helper)
            throws Exception {
        Rig her = new Rig(helper, "gametest_remote_asks", null);
        her.brain.connect(true);
        McpTestClient.Tap tap = her.brain.openStream();
        Object scope = new Object();
        var desk = com.dwinovo.numen.api.permission.ConsentDesk.of(her.companion);
        com.dwinovo.numen.api.permission.ConsentDesk.Ticket[] ticket = new com.dwinovo.numen.api.permission.ConsentDesk.Ticket[1];
        com.google.gson.JsonObject[] asked = new com.google.gson.JsonObject[1];

        helper.runAfterDelay(10, () -> {   // 流接上了
            ticket[0] = desk.ask(scope, List.of(oakLog()));
            helper.assertTrue(ticket[0].poll() == null, "refused at once although his brain is connected: "
                    + ticket[0].poll());
        });
        helper.runAfterDelay(25, () -> {
            asked[0] = tap.messages.stream().filter(m -> m.has("method")
                    && m.get("method").getAsString().equals("elicitation/create")).findFirst().orElse(null);
            helper.assertTrue(asked[0] != null, "the human was not asked at the external brain's client: "
                    + tap.messages);
            String message = asked[0].getAsJsonObject("params").get("message").getAsString();
            helper.assertTrue(message.contains("gametest_remote_asks") && message.contains("oak_log"),
                    "the question does not say who wants what: " + message);
            try {
                her.brain.answer(asked[0], accept("allow"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(ticket[0] != null && ticket[0].poll() != null && ticket[0].poll().allowed(),
                    "his choice did not reach the desk: " + (ticket[0] == null ? null : ticket[0].poll()));
            helper.assertTrue(desk.pending() == null, "the request is still pending");
            her.close();
        });
    }

    /** 外部大脑的客户端不会答 elicitation:征询不当场拒绝,挂着等主人进游戏答;连外部大脑也走了,才按"主人不在"拒绝。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_remote_5")
    public static void a_client_that_cannot_elicit_leaves_the_consent_waiting_for_the_owner(GameTestHelper helper)
            throws Exception {
        Rig her = new Rig(helper, "gametest_remote_mute", null);
        her.brain.connect();
        var desk = com.dwinovo.numen.api.permission.ConsentDesk.of(her.companion);
        com.dwinovo.numen.api.permission.ConsentDesk.Ticket[] ticket = new com.dwinovo.numen.api.permission.ConsentDesk.Ticket[1];

        helper.runAfterDelay(5, () -> ticket[0] = desk.ask(new Object(), List.of(oakLog())));
        helper.runAfterDelay(15, () -> {
            helper.assertTrue(ticket[0].poll() == null && desk.pending() != null,
                    "not waiting for the owner: " + ticket[0].poll());
            try {
                her.brain.disconnect();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(ticket[0] != null && ticket[0].poll() != null && !ticket[0].poll().allowed(),
                    "not refused once nobody was left to answer: " + (ticket[0] == null ? null : ticket[0].poll()));
            her.close();
        });
    }

    /**
     * 渠道来的话:主人不在游戏里,话由服务端大脑去想,回话回那个渠道;这一轮做完他还不在,她回到收拾好的样子
     * (待命,收拾一次),不因来过一句话一直醒着。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_remote_6")
    public static void a_channels_words_are_thought_about_and_answered_then_she_goes_back_to_standing_by(
            GameTestHelper helper) throws Exception {
        BrainGameTests.Scripted mind = new BrainGameTests.Scripted();
        mind.say("在的,马上来");
        Rig her = new Rig(helper, "gametest_remote_chat", null, mind, McpToken.Role.CHANNEL);
        helper.assertTrue(her.hold() == Hold.AWAY, "not standing by before anyone spoke: " + her.hold());
        java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> replies = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        // 工具调用要等服务端主线程办事,所以另起一条线程扮渠道桥
        Thread bridge = new Thread(() -> {
            try {
                her.brain.connect();
                com.google.gson.JsonObject words = new com.google.gson.JsonObject();
                words.addProperty("companion", "gametest_remote_chat");
                words.addProperty("text", "在吗");
                sent.set(her.brain.call("send", words));
                com.google.gson.JsonObject wait = new com.google.gson.JsonObject();
                wait.addProperty("wait_seconds", 20);
                replies.set(her.brain.call("get_replies", wait));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "gametest-channel-bridge");
        bridge.setDaemon(true);
        bridge.start();

        succeedWhen(helper, () -> {
            helper.assertTrue(failure.get() == null, "the bridge failed: " + failure.get());
            helper.assertTrue(replies.get() != null, "no reply came back to the channel (sent: " + sent.get() + ")");
            helper.assertTrue(mind.asked.size() == 1, "her brain was not asked once: " + mind.asked.size());
            helper.assertTrue(replies.get().contains("在的,马上来") && replies.get().contains("\"said\""),
                    "the channel did not get her answer: " + replies.get());
            helper.assertTrue(her.hold() == Hold.AWAY, "she did not go back to standing by: " + her.hold());
            helper.assertTrue(her.disconnects() == 1, "not collected once after the round: " + her.halts);
            her.close();
        });
    }

    /** 渠道来访期间她要问主人的事:不当场按"主人不在"拒绝,以通用形式交给渠道;主人在渠道上的答复走征询台。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_remote_7")
    public static void a_consent_during_a_channel_visit_is_handed_to_the_channel(GameTestHelper helper)
            throws Exception {
        Rig her = new Rig(helper, "gametest_remote_visited", null, new BrainGameTests.Scripted(),
                McpToken.Role.CHANNEL);
        List<com.dwinovo.numen.api.permission.ConsentVenues.Offer> offered = new CopyOnWriteArrayList<>();
        com.dwinovo.numen.api.brain.Visits.begin(her.companion.getUUID(), her.owner,
                new com.dwinovo.numen.api.brain.Visits.Channel() {
                    @Override
                    public void said(UUID companion, String text) {
                    }

                    @Override
                    public void ask(com.dwinovo.numen.api.permission.ConsentVenues.Offer offer) {
                        offered.add(offer);
                    }

                    @Override
                    public void clear(long id, com.dwinovo.numen.api.permission.ConsentDesk.Withdrawal why) {
                    }
                });
        var desk = com.dwinovo.numen.api.permission.ConsentDesk.of(her.companion);

        var ticket = desk.ask(new Object(), List.of(oakLog()));

        helper.assertTrue(ticket.poll() == null, "refused at once although the owner is on a channel: " + ticket.poll());
        helper.assertTrue(offered.size() == 1 && offered.get(0).id() == ticket.request().id()
                        && offered.get(0).doing().contains("oak_log") && offered.get(0).companion().equals(her.companion.getUUID()),
                "the channel was not handed the question in the general form: " + offered);
        var reply = com.dwinovo.numen.api.permission.ConsentDesk.reply(her.owner, her.companion, ticket.request().id(),
                com.dwinovo.numen.api.permission.ConsentAnswer.Decision.DENY, "用箱子里的");
        helper.assertTrue(reply == com.dwinovo.numen.api.permission.ConsentDesk.Reply.ANSWERED, "answer refused: " + reply);
        helper.assertTrue(ticket.poll() != null && !ticket.poll().allowed()
                && ticket.poll().words().equals("用箱子里的"), "the owner's answer did not reach the asker: " + ticket.poll());
        com.dwinovo.numen.api.brain.Visits.end(her.companion.getUUID());
        her.close();
        helper.succeed();
    }
}
