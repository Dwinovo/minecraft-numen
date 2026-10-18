package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.brain.Replies;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.permission.Groups;
import com.dwinovo.numen.api.permission.Layers;
import com.dwinovo.numen.api.permission.Permission;
import com.dwinovo.numen.api.permission.PermissionStore;
import com.dwinovo.numen.permission.Mode;
import com.dwinovo.numen.permission.Verdict;
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

    private static long tables(List<CustomPacketPayload> packets) {
        return packets.stream().filter(p -> p instanceof LibraryPayload t && Libraries.PERMISSION.equals(t.kind())).count();
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

    private static JsonObject modeRow(LibraryPayload table, String name) {
        for (JsonElement e : JsonParser.parseString(table.entries()).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("mode") && name.equals(o.get("name").getAsString())) {
                return o;
            }
        }
        return null;
    }

    /**
     * 主人在面板上加一行 deny:服务端答复带着新表,她去挖那块石头被那一行拒了;删掉那一行(同样经协议),她就挖得动。
     * 写错的规则被拒、带着教学式错误的码、没进表。
     */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_permission_panel")
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
        helper.assertTrue(refused != null && refused.request() == 7 && !refused.problem().isEmpty() && refused.entries().contains("\"code\":\"verb\"")
                        && store.rows(Verdict.Kind.DENY).isEmpty(),
                "a mistyped rule was not refused with its code: " + refused);

        Replies.library(owner, Libraries.PERMISSION, "put", "{\"table\":\"deny\",\"rule\":\"" + rule + "\"}");
        LibraryPayload added = lastTable(received(owner));
        helper.assertTrue(added != null && added.problem().isEmpty() && ruleRow(added, "owner", rule) != null
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
                helper.assertTrue(!calls[0].succeeded() && calls[0].outcome().contains("denied by rule " + rule)
                                && level.getBlockState(helper.absolutePos(stoneRel)).is(Blocks.STONE),
                        "the panel's deny row did not hold: " + calls[0].outcome());
                Replies.library(owner, Libraries.PERMISSION, "delete", "{\"table\":\"deny\",\"rule\":\"" + rule + "\"}");
                helper.assertTrue(store.rows(Verdict.Kind.DENY).isEmpty(), "the row was not deleted");
                calls[1] = click(helper, companion, "left", stoneRel);
                step[0] = 2;
            }
        });

        succeedWhen(helper, () -> {
            helper.assertTrue(step[0] == 2 && calls[1].done(), "the clicks have not finished");
            helper.assertTrue(calls[1].succeeded() && level.getBlockState(helper.absolutePos(stoneRel)).isAir(),
                    "the stone could not be dug after the row was deleted: " + calls[1].outcome());
            finish(owner, companion);
        });
    }

    /**
     * 主人在面板上给同伴选模式:存档里的模式就是命令 {@code /numen permission mode} 读到的那个、也是她被裁决时用的那个;
     * 别的玩家发同样的请求动不了不是他名下的同伴,也读不到这位主人的规则;清空主人层不动模式。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_permission_panel")
    public static void the_panel_sets_a_mode_only_on_its_owners_companion(GameTestHelper helper) {
        NumenPlayer companion = spawnBare(helper, "gametest_moody", new BlockPos(4, 2, 5), false);
        ServerPlayer owner = clientOwner(helper, companion, "gametest_mode_owner");
        ServerPlayer stranger = presentPlayer(helper, null, "gametest_mode_stranger");
        Replies.hello(stranger, Presence.PROTOCOL);
        received(stranger);
        PermissionStore store = PermissionStore.of(owner.getServer(), owner.getUUID());
        store.add(Verdict.Kind.ASK, Groups.parse("take(*)"));
        String uuid = companion.getUUID().toString();

        Replies.library(owner, Libraries.PERMISSION, "list", "{}");
        LibraryPayload before = lastTable(received(owner));
        helper.assertTrue(before != null && modeRow(before, "gametest_moody") != null
                        && "ask".equals(modeRow(before, "gametest_moody").get("mode").getAsString())
                        && ruleRow(before, "hard", "break(other)") != null && ruleRow(before, "owner", "take(*)") != null,
                "the owner's table is missing the companion, the hard rules or his row: " + before);

        Replies.library(owner, Libraries.PERMISSION, "mode", "{\"companion\":\"" + uuid + "\",\"mode\":\"observe\"}");
        LibraryPayload after = lastTable(received(owner));
        helper.assertTrue(Permission.modeOf(companion) == Mode.OBSERVE && after != null
                        && "observe".equals(modeRow(after, "gametest_moody").get("mode").getAsString()),
                "the mode was not set or not answered: " + after);

        Replies.library(stranger, Libraries.PERMISSION, "mode", "{\"companion\":\"" + uuid + "\",\"mode\":\"bypass\"}");
        LibraryPayload strangers = lastTable(received(stranger));
        helper.assertTrue(strangers != null && !strangers.problem().isEmpty() && Permission.modeOf(companion) == Mode.OBSERVE,
                "a stranger set the mode of someone else's companion: " + strangers);
        Replies.library(stranger, Libraries.PERMISSION, "reset", "{}");
        lastTable(received(stranger));
        helper.assertTrue(store.rows(Verdict.Kind.ASK).size() == 1, "a stranger's reset cleared the owner's rows");
        Replies.library(stranger, Libraries.PERMISSION, "list", "{}");
        LibraryPayload strangersTable = lastTable(received(stranger));
        helper.assertTrue(strangersTable != null && ruleRow(strangersTable, "owner", "take(*)") == null
                        && modeRow(strangersTable, "gametest_moody") == null,
                "a stranger read the owner's table: " + strangersTable);

        Replies.library(owner, Libraries.PERMISSION, "reset", "{}");
        helper.assertTrue(store.rows(Verdict.Kind.ASK).isEmpty() && Permission.modeOf(companion) == Mode.OBSERVE,
                "reset did not clear only the rows");
        leave(stranger);
        finish(owner, companion);
        helper.succeed();
    }

    /** 她加了一行 deny:不等主人来要,在线的主人面板收到带新行的表,来源标成她、带着她说的理由。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_permission_panel")
    public static void a_row_she_adds_is_pushed_to_the_owners_panel(GameTestHelper helper) {
        NumenPlayer companion = spawnBare(helper, "gametest_scribbler", new BlockPos(4, 2, 5), false);
        ServerPlayer owner = clientOwner(helper, companion, "gametest_push_owner");
        ToolRun deny = lua(companion, "return numen.permission.deny(\"break(owner & minecraft:chest)\", "
                + "{why = \"leave the chests\"})");
        List<CustomPacketPayload> got = new ArrayList<>();

        succeedWhen(helper, () -> {
            got.addAll(received(owner));
            helper.assertTrue(deny.done() && deny.succeeded(), "the deny has not been written: " + deny.outcome());
            LibraryPayload pushed = lastTable(got);
            helper.assertTrue(pushed != null && pushed.request() == 0, "nothing was pushed to the owner's panel");
            JsonObject row = ruleRow(pushed, "owner", "break(owner & minecraft:chest)");
            helper.assertTrue(row != null && "her".equals(row.get("source").getAsString())
                            && "leave the chests".equals(row.get("note").getAsString()),
                    "the pushed table does not mark the row as hers with her reason: " + pushed.entries());
            finish(owner, companion);
        });
    }

    /** 命中账一刻里涨了许多次:合并成一包,带着最终的次数,不是每命中一次推一包。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_permission_panel")
    public static void many_hits_in_a_tick_reach_the_panel_as_one_table(GameTestHelper helper) {
        NumenPlayer companion = spawnBare(helper, "gametest_counter", new BlockPos(4, 2, 5), false);
        ServerPlayer owner = clientOwner(helper, companion, "gametest_count_owner");
        PermissionStore store = PermissionStore.of(owner.getServer(), owner.getUUID());
        var rule = Groups.parse("break(minecraft:tnt)");
        store.add(Verdict.Kind.DENY, rule);
        List<CustomPacketPayload> got = new ArrayList<>();
        helper.runAfterDelay(5, () -> {
            received(owner);
            for (int i = 0; i < 30; i++) {
                store.hit(Layers.OWNER, Verdict.Kind.DENY, rule);
            }
        });
        helper.runAfterDelay(30, () -> {
            got.addAll(received(owner));
            helper.assertTrue(tables(got) == 1, "the hits were not merged into one push: " + tables(got));
            JsonObject row = ruleRow(lastTable(got), "owner", "break(minecraft:tnt)");
            helper.assertTrue(row != null && row.get("count").getAsInt() == 30 && row.get("last").getAsLong() > 0,
                    "the push does not carry the ledger: " + lastTable(got).entries());
            finish(owner, companion);
            helper.succeed();
        });
    }
}
