package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.Checks;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.permission.Verdict;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
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
 * 面板的权限页:主人经库协议读写他的规则与同伴的模式,改了之后她在游戏里真的照新的办;规则有变(她加了一行、命中账涨了)
 * 合并成一包推给在线的主人。从协议的入口({@code Replies.library})进,和客户端发来的请求走同一条路。
 */
public class PermissionPanelGameTests {

    @BeforeBatch(batch = "numen_permission_panel")
    public static void prepareBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 装着我们的客户端的主人认下这只同伴,同伴在名册上有一行(面板的模式列表读名册)。 */
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

    /** 一张表里这一行规则;没有为 null。 */
    private static JsonObject ruleRow(LibraryPayload table, String layer, String rule) {
        for (JsonElement e : JsonParser.parseString(table.entries()).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("rule") && layer.equals(o.get("layer").getAsString()) && rule.equals(o.get("rule").getAsString())) {
                return o;
            }
        }
        return null;
    }

    /**
     * 主人在面板上加一行 deny:服务端答复带着新表,她去挖那块石头被那一行拒了;删掉那一行(同样经协议),她就挖得动。
     * 写错的规则被拒、带着教学式错误的码、没进表。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission_panel")
    public static void a_rule_added_in_the_panel_holds_in_the_world_until_it_is_deleted(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stoneRel = new BlockPos(6, 2, 5);
        level.setBlockAndUpdate(helper.absolutePos(stoneRel), Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnBare(helper, "gametest_panelist", new BlockPos(4, 2, 5), true);
        ServerPlayer owner = clientOwner(helper, companion, "gametest_panel_owner");
        PermissionStore store = PermissionStore.of(owner.getServer(), owner.getUUID());
        String rule = "break(nature & minecraft:stone)";

        Replies.library(owner, Libraries.PERMISSION, "put", "{\"table\":\"deny\",\"rule\":\"brek(owner)\"}", 7);
        LibraryPayload refused = lastTable(received(owner));
        Checks.assertTrue(helper, refused != null && refused.request() == 7 && !refused.problem().isEmpty() && refused.entries().contains("\"code\":\"verb\"")
                        && store.rows(Verdict.Kind.DENY).isEmpty(),
                "a mistyped rule was not refused with its code: " + refused);

        Replies.library(owner, Libraries.PERMISSION, "put", "{\"table\":\"deny\",\"rule\":\"" + rule + "\"}");
        LibraryPayload added = lastTable(received(owner));
        Checks.assertTrue(helper, added != null && added.problem().isEmpty() && ruleRow(added, "owner", rule) != null
                        && "owner".equals(ruleRow(added, "owner", rule).get("source").getAsString()),
                "the answer does not carry the new row: " + added);

        ToolRun[] calls = new ToolRun[2];
        int[] step = {0};
        helper.runAfterDelay(5, () -> {
            calls[0] = click(helper, companion, "left", stoneRel);
            step[0] = 1;
        });
        helper.onEachTick(() -> {
            if (step[0] == 1 && calls[0].done()) {
                Checks.assertTrue(helper, !calls[0].succeeded() && calls[0].outcome().contains("denied by rule " + rule)
                                && level.getBlockState(helper.absolutePos(stoneRel)).is(Blocks.STONE),
                        "the panel's deny row did not hold: " + calls[0].outcome());
                Replies.library(owner, Libraries.PERMISSION, "delete", "{\"table\":\"deny\",\"rule\":\"" + rule + "\"}");
                Checks.assertTrue(helper, store.rows(Verdict.Kind.DENY).isEmpty(), "the row was not deleted");
                calls[1] = click(helper, companion, "left", stoneRel);
                step[0] = 2;
            }
        });

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, step[0] == 2 && calls[1].done(), "the clicks have not finished");
            Checks.assertTrue(helper, calls[1].succeeded() && level.getBlockState(helper.absolutePos(stoneRel)).isAir(),
                    "the stone could not be dug after the row was deleted: " + calls[1].outcome());
            finish(owner, companion);
        });
    }

    /** 她加了一行 deny:不等主人来要,在线的主人面板收到带新行的表,来源标成她、带着她说的理由。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_permission_panel")
    public static void a_row_she_adds_is_pushed_to_the_owners_panel(GameTestHelper helper) {
        NumenPlayer companion = spawnBare(helper, "gametest_scribbler", new BlockPos(4, 2, 5), false);
        ServerPlayer owner = clientOwner(helper, companion, "gametest_push_owner");
        ToolRun deny = lua(companion, "return numen.permission.deny(\"break(owner & minecraft:chest)\", "
                + "{why = \"leave the chests\"})");
        List<CustomPacketPayload> got = new ArrayList<>();

        succeedWhen(helper, () -> {
            got.addAll(received(owner));
            Checks.assertTrue(helper, deny.done() && deny.succeeded(), "the deny has not been written: " + deny.outcome());
            LibraryPayload pushed = lastTable(got);
            Checks.assertTrue(helper, pushed != null && pushed.request() == 0, "nothing was pushed to the owner's panel");
            JsonObject row = ruleRow(pushed, "owner", "break(owner & minecraft:chest)");
            Checks.assertTrue(helper, row != null && "her".equals(row.get("source").getAsString())
                            && "leave the chests".equals(row.get("note").getAsString()),
                    "the pushed table does not mark the row as hers with her reason: " + pushed.entries());
            finish(owner, companion);
        });
    }
}
