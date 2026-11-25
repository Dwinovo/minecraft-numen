package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.permission.Settings;
import com.dwinovo.numen.permission.Tier;
import com.dwinovo.numen.permission.Verb;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 面板的权限页:主人经库协议读写他每只同伴的档位与"不用问"的名单,改了之后她在游戏里真的照新的办;设置有变(她调严了一个档位)
 * 合并成一包推给在线的主人。从协议的入口({@code Replies.library})进,和客户端发来的请求走同一条路。
 */
public class PermissionPanelGameTests {

    @BeforeBatch(batch = "numen_permission_panel")
    public static void prepareBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 装着我们的客户端的主人认下这只同伴,同伴在名册上有一行(面板的同伴列表读名册)。 */
    private static ServerPlayer clientOwner(GameTestHelper helper, NumenPlayer companion, String name) {
        ServerPlayer owner = presentOwner(helper, companion, name);
        CompanionRegistry.get(owner.getServer()).put(companion.getUUID(), new CompanionRegistry.Entry(
                companion.getName().getString(), owner.getUUID(), Level.OVERWORLD, companion.blockPosition()));
        Replies.hello(owner, Presence.PROTOCOL);
        received(owner);
        return owner;
    }

    private static void finish(ServerPlayer owner, NumenPlayer companion) {
        CompanionRegistry.get(owner.getServer()).remove(companion.getUUID());
        CompanionFactory.despawn(owner.getServer(), companion);
        leave(owner);
    }

    /** 收到的包里最近一张"权限"表;没有为 null。 */
    private static LibraryPayload lastTable(List<CustomPacketPayload> packets) {
        LibraryPayload last = null;
        for (CustomPacketPayload p : packets) {
            if (p instanceof LibraryPayload table && Libraries.PERMISSION.equals(table.kind())) {
                last = table;
            }
        }
        return last;
    }

    /** 一张表里这只同伴这种动作的档位行;没有为 null。 */
    private static JsonObject tierRow(LibraryPayload table, NumenPlayer companion, String verb) {
        for (JsonElement e : JsonParser.parseString(table.entries()).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            if ("tier".equals(o.get("row").getAsString()) && verb.equals(o.get("verb").getAsString())
                    && companion.getUUID().toString().equals(o.get("companion").getAsString())) {
                return o;
            }
        }
        return null;
    }

    /**
     * 主人在面板上把"挖方块"定成不许:服务端答复带着新表,她去挖那块石头被主人的这一档拒了;再定回许(同样经协议),她就挖得动。
     * 认不出的动作被拒、带着原因、没改任何档位。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission_panel")
    public static void a_tier_set_in_the_panel_holds_in_the_world_until_it_is_set_back(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stoneRel = new BlockPos(6, 2, 5);
        level.setBlockAndUpdate(helper.absolutePos(stoneRel), Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_panelist", new BlockPos(4, 2, 5), true);
        ServerPlayer owner = clientOwner(helper, companion, "gametest_panel_owner");
        PermissionStore store = PermissionStore.of(owner.getServer(), owner.getUUID());
        String who = companion.getUUID().toString();

        Replies.library(owner, Libraries.PERMISSION, "tier",
                "{\"companion\":\"" + who + "\",\"verb\":\"dig\",\"tier\":\"deny\"}", 7);
        LibraryPayload refused = lastTable(received(owner));
        helper.assertTrue(refused != null && refused.request() == 7 && !refused.problem().isEmpty()
                        && store.settingsOf(companion.getUUID()).equals(Settings.FACTORY),
                "a mistyped verb was not refused: " + refused);

        Replies.library(owner, Libraries.PERMISSION, "tier",
                "{\"companion\":\"" + who + "\",\"verb\":\"break\",\"tier\":\"deny\"}", 0);
        LibraryPayload set = lastTable(received(owner));
        helper.assertTrue(set != null && set.problem().isEmpty() && tierRow(set, companion, "break") != null
                        && "deny".equals(tierRow(set, companion, "break").get("tier").getAsString()),
                "the answer does not carry the new tier: " + set);

        ToolRun[] calls = new ToolRun[2];
        int[] step = {0};
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "left", stoneRel);
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            if (step[0] == 1 && calls[0].done()) {
                helper.assertTrue(!calls[0].succeeded() && calls[0].outcome().contains("setting for break is deny")
                                && level.getBlockState(helper.absolutePos(stoneRel)).is(Blocks.STONE),
                        "the panel's tier did not hold: " + calls[0].outcome());
                Replies.library(owner, Libraries.PERMISSION, "tier",
                        "{\"companion\":\"" + who + "\",\"verb\":\"break\",\"tier\":\"allow\"}", 0);
                helper.assertTrue(store.settingsOf(companion.getUUID()).tier(Verb.BREAK) == Tier.ALLOW,
                        "the tier was not set back");
                calls[1] = click(helper, companion, "left", stoneRel);
                step[0] = 2;
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 2 && calls[1].done(), "the clicks have not finished");
            helper.assertTrue(calls[1].succeeded() && level.getBlockState(helper.absolutePos(stoneRel)).isAir(),
                    "the stone could not be dug after the tier was set back: " + calls[1].outcome());
            finish(owner, companion);
        });
    }

    /** 她把一个档位调严:不等主人来要,在线的主人面板收到带新档位的表。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission_panel")
    public static void a_tightening_she_makes_is_pushed_to_the_owners_panel(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_scribbler", new BlockPos(4, 2, 5), false);
        ServerPlayer owner = clientOwner(helper, companion, "gametest_push_owner");
        ToolRun tighten = lua(companion, "return numen.permission.set(\"attack\", \"ask\")");
        List<CustomPacketPayload> got = new ArrayList<>();

        succeedWhen(helper, () -> {
            got.addAll(received(owner));
            helper.assertTrue(tighten.done() && tighten.succeeded(), "the tightening has not been written: " + tighten.outcome());
            LibraryPayload pushed = lastTable(got);
            helper.assertTrue(pushed != null && pushed.request() == 0, "nothing was pushed to the owner's panel");
            JsonObject row = tierRow(pushed, companion, "attack");
            helper.assertTrue(row != null && "ask".equals(row.get("tier").getAsString()),
                    "the pushed table does not carry the tier she set: " + pushed.entries());
            finish(owner, companion);
        });
    }
}
