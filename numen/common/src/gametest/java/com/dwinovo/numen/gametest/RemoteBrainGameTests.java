package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.agent.loop.HaltReason;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.brain.CompanionBrains;
import com.dwinovo.numen.api.brain.CompanionLoop;
import com.dwinovo.numen.api.brain.CompanionLoops;
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
            this.server = helper.getLevel().getServer();
            this.companion = spawnAt(helper, name, new BlockPos(3, 2, 3), false);
            this.owner = ownerPlayer != null ? ownerPlayer.getUUID() : UUID.randomUUID();
            companion.setOwnerUuid(owner);
            CompanionRegistry.get(server).put(companion.getUUID(), new CompanionRegistry.Entry(name, owner,
                    Level.OVERWORLD, companion.blockPosition()));
            CompanionBrains.reset(server, owner);   // 换掉 spawnAt 配的不思考的脑,换成产品线路接的
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
            this.brain = new McpTestClient(port, mcp.tokens().issue(owner, McpToken.Role.BRAIN,
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
}
