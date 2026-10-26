package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 插件接口:插件登记的身体状态片段与事件送到她那里。 */
public class PluginGameTests {

    /** 插件批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_plugin")
    public static void preparePluginBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 插件经那扇门挂上的东西,和 Numen API 自带的走同一条路:测试里登记一个假插件,它从身体上读一段状态
     * (只对这只同伴说话),再登记一种事件并发一条。{@code status_self} 里有那段状态;
     * 那条事件以插件登记的类型进她的收件箱,kind 就是那个类型。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_plugin")
    public static void a_plugins_body_state_and_event_reach_her(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_charmed", new BlockPos(4, 2, 4), false);
        UUID self = companion.getUUID();
        com.dwinovo.numen.api.NumenPlugins.register("gt", numen -> {
            numen.contributeBodyState(body -> body.getUUID().equals(self)
                    ? "<gametest_charm>wearing a gametest charm</gametest_charm>" : "");
            numen.registerEventType("gametest_charm_changed", false);
            numen.emit(companion, "gametest_charm_changed", java.util.Map.of("slot", "neck"),
                    "put on a gametest charm", false);
        });
        ToolRun reply = lua(companion, "numen.status.self()");

        succeedWhen(helper, () -> {
            helper.assertTrue(reply.reply() != null, "status_self has not replied");
            var status = dataIn(reply.reply());
            // 身体状态片段以 Numen API 渲染的 <worn> 打头,之后接插件登记的片段:这个插件的那段紧跟在 <worn> 后面
            helper.assertTrue(status.has("body_state") && status.get("body_state").getAsString().startsWith("<worn>")
                            && status.get("body_state").getAsString()
                            .endsWith("</worn><gametest_charm>wearing a gametest charm</gametest_charm>"),
                    "status_self leaves out what the plugin reads off her body: " + reply.reply());
            var kept = GameTestKit.events(companion).stream()
                    .filter(e -> e.type().equals("gametest_charm_changed")).toList();
            helper.assertTrue(kept.size() == 1
                            && kept.get(0).text().startsWith("<event kind=\"gametest_charm_changed\"")
                            && kept.get(0).text().contains("slot=\"neck\""),
                    "the plugin's event did not go out as its own kind: " + GameTestKit.events(companion));
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 玩家收到的最近一张插件表里这一个插件的那一行;没收到表或没有这一行是 {@code null}。 */
    private static com.google.gson.JsonObject pluginRow(java.util.List<?> told, String id) {
        com.google.gson.JsonObject found = null;
        for (Object packet : told) {
            if (packet instanceof com.dwinovo.numen.api.network.payload.LibraryPayload table
                    && com.dwinovo.numen.api.brain.Libraries.PLUGIN.equals(table.kind())) {
                for (var row : com.google.gson.JsonParser.parseString(table.entries()).getAsJsonArray()) {
                    if (id.equals(row.getAsJsonObject().get("id").getAsString())) {
                        found = row.getAsJsonObject();
                    }
                }
            }
        }
        return found;
    }

    private static boolean listed(String id) {
        return com.dwinovo.numen.api.plugin.PluginSwitches.onDisk().disabled().contains(id);
    }

    /**
     * 管理员经协议关掉一个插件:他收到的表里那一行下次启动是关的、这一次仍是已加载,停用名单落了盘;再打开,名单里就没了。
     * 写盘在后台车道上,所以等它落下去再看文件。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_plugin")
    public static void an_admin_switches_a_plugin_off_and_the_list_is_written_to_disk(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        com.dwinovo.numen.api.NumenPlugins.register("gt_toggle", numen -> { });
        var admin = GameTestKit.presentPlayer(helper, null, "gametest_plugin_boss");
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(admin.getGameProfile(),
                4, false));
        com.dwinovo.numen.api.brain.Replies.hello(admin, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        GameTestKit.received(admin);

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PLUGIN, "disable",
                "{\"id\":\"gt_toggle\"}");
        var row = pluginRow(GameTestKit.received(admin), "gt_toggle");
        helper.assertTrue(row != null && row.get("disabledNext").getAsBoolean() && row.get("pending").getAsBoolean()
                        && row.get("toggle").getAsBoolean() && "loaded".equals(row.get("status").getAsString()),
                "the admin was not shown the plugin as off from the next start while still running: " + row);

        GameTestKit.steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(listed("gt_toggle"), "the list has not reached the disk"))
                .thenExecute(() -> com.dwinovo.numen.api.brain.Replies.library(admin,
                        com.dwinovo.numen.api.brain.Libraries.PLUGIN, "enable", "{\"id\":\"gt_toggle\"}"))
                .thenWaitUntil(() -> helper.assertTrue(!listed("gt_toggle"), "the plugin is still on the list"))
                .thenExecute(() -> {
                    server.getPlayerList().deop(admin.getGameProfile());
                    GameTestKit.leave(admin);
                })
                .thenSucceed();
    }

    /** 不是管理员的玩家读得到插件表、行上没有开关;他要关插件被拒,说明原因,名单不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_plugin")
    public static void a_player_reads_the_plugins_but_cannot_switch_one(GameTestHelper helper) {
        com.dwinovo.numen.api.NumenPlugins.register("gt_fixed", numen -> { });
        var player = GameTestKit.presentPlayer(helper, null, "gametest_plugin_hand");
        com.dwinovo.numen.api.brain.Replies.hello(player, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        GameTestKit.received(player);

        com.dwinovo.numen.api.brain.Replies.library(player, com.dwinovo.numen.api.brain.Libraries.PLUGIN, "disable",
                "{\"id\":\"gt_fixed\"}");
        var told = GameTestKit.received(player);
        var row = pluginRow(told, "gt_fixed");
        helper.assertTrue(row != null && !row.get("toggle").getAsBoolean() && !row.get("disabledNext").getAsBoolean(),
                "the player was shown a switch, or the plugin as off: " + row);
        helper.assertTrue(told.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload l
                        && l.kind().equals(com.dwinovo.numen.api.brain.Libraries.PLUGIN) && !l.problem().isEmpty()),
                "the refusal gave no reason: " + told);
        helper.assertTrue(!listed("gt_fixed"), "the refused request reached the list");
        GameTestKit.leave(player);
        helper.succeed();
    }

    /**
     * 启动时名单上的插件不 setup、状态是已关闭,没在名单上的照常装:用一份预先写好名单的新花名册,
     * 经真的那扇门(登记得到的)装两个插件,看它们登记的事件种类在不在。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_plugin")
    public static void a_plugin_on_the_list_at_start_is_not_set_up(GameTestHelper helper) throws java.io.IOException {
        var door = new java.util.concurrent.atomic.AtomicReference<com.dwinovo.numen.api.NumenApi>();
        com.dwinovo.numen.api.NumenPlugins.register("gt_door", door::set);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("numen-gametest-plugins-");
        var switches = new com.dwinovo.numen.api.plugin.PluginSwitches(new com.dwinovo.numen.api.store.JsonDoc(
                dir.resolve(com.dwinovo.numen.api.plugin.PluginSwitches.FILE),
                com.dwinovo.numen.api.store.JsonDoc.Format.of(1), com.dwinovo.numen.api.store.Io.direct(e -> {
                    throw e;
                })));
        switches.write(java.util.Set.of("gt_start_off"));
        var roster = new com.dwinovo.numen.api.plugin.PluginRoster(() -> switches, id -> com.dwinovo.numen.api.plugin.PluginRoster.Meta.NONE);

        roster.run("gt_start_off", numen -> numen.registerEventType("gametest_start_off", false), door.get());
        roster.run("gt_start_on", numen -> numen.registerEventType("gametest_start_on", false), door.get());

        var rows = roster.plugins();
        helper.assertTrue(!com.dwinovo.numen.api.agent.inbox.EventTypes.isRegistered("gametest_start_off"),
                "the plugin on the list was set up");
        helper.assertTrue(com.dwinovo.numen.api.agent.inbox.EventTypes.isRegistered("gametest_start_on"),
                "the plugin off the list was not set up");
        helper.assertTrue(rows.stream().anyMatch(r -> r.id().equals("gt_start_off")
                        && r.status() == com.dwinovo.numen.api.plugin.PluginRoster.Status.DISABLED),
                "the plugin on the list is not shown as off: " + rows);
        helper.assertTrue(rows.stream().anyMatch(r -> r.id().equals("gt_start_on")
                        && r.status() == com.dwinovo.numen.api.plugin.PluginRoster.Status.LOADED
                        && r.adds().get(com.dwinovo.numen.api.plugin.PluginRoster.Kind.EVENT_TYPE) == 1),
                "the plugin off the list is not shown as loaded with what it added: " + rows);
        helper.succeed();
    }

    /** 联动的目标模组没装:闸门不装它,它也不算这台服务器上的插件,花名册里没有这一行。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_plugin")
    public static void a_link_whose_target_is_not_installed_is_not_a_plugin(GameTestHelper helper) {
        boolean[] installed = {false};
        new com.dwinovo.numen.plugins.Gate(mod -> false, path -> null)
                .open("gametest_absent_mod", "gt_absent", () -> () -> installed[0] = true);

        helper.assertTrue(!installed[0], "the link was installed without its target");
        helper.assertTrue(com.dwinovo.numen.api.NumenPlugins.roster().plugins().stream()
                        .noneMatch(p -> p.id().equals("gt_absent")),
                "a link without its target is on the plugin list");
        helper.succeed();
    }
}
