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
     * 管理员经协议关掉一个插件:他收到的表里那一行是关的、状态当场是已关闭(只经门登记的插件不用重启),停用名单落了盘;再打开,名单里
     * 就没了。写盘在后台车道上,所以等它落下去再看文件。
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
        helper.assertTrue(row != null && row.get("disabled").getAsBoolean() && !row.get("pending").getAsBoolean()
                        && row.get("toggle").getAsBoolean() && "disabled".equals(row.get("status").getAsString()),
                "the admin was not shown the plugin as switched off at once: " + row);

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
        helper.assertTrue(row != null && !row.get("toggle").getAsBoolean() && !row.get("disabled").getAsBoolean(),
                "the player was shown a switch, or the plugin as off: " + row);
        helper.assertTrue(told.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload l
                        && l.kind().equals(com.dwinovo.numen.api.brain.Libraries.PLUGIN) && !l.problem().isEmpty()),
                "the refusal gave no reason: " + told);
        helper.assertTrue(!listed("gt_fixed"), "the refused request reached the list");
        GameTestKit.leave(player);
        helper.succeed();
    }

    /**
     * 启动时名单上的插件不 setup、状态是已关闭,没在名单上的照常装:换一份预先写好名单的新花名册,经真的那扇门(登记得到的)
     * 装两个插件,看它们登记的事件种类在不在。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_plugin")
    public static void a_plugin_on_the_list_at_start_is_not_set_up(GameTestHelper helper) throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("numen-gametest-plugins-");
        var switches = new com.dwinovo.numen.api.plugin.PluginSwitches(new com.dwinovo.numen.api.store.JsonDoc(
                dir.resolve(com.dwinovo.numen.api.plugin.PluginSwitches.FILE),
                com.dwinovo.numen.api.store.JsonDoc.Format.of(1), com.dwinovo.numen.api.store.Io.direct(e -> {
                    throw e;
                })));
        switches.write(java.util.Set.of("gt_start_off"));
        var roster = new com.dwinovo.numen.api.plugin.PluginRoster(() -> switches, id -> com.dwinovo.numen.api.plugin.PluginRoster.Meta.NONE);
        var before = com.dwinovo.numen.api.NumenPlugins.roster();
        com.dwinovo.numen.api.NumenPlugins.useRoster(roster);
        try {
            com.dwinovo.numen.api.NumenPlugins.register("gt_start_off",
                    numen -> numen.registerEventType("gametest_start_off", false));
            com.dwinovo.numen.api.NumenPlugins.register("gt_start_on",
                    numen -> numen.registerEventType("gametest_start_on", false));
        } finally {
            com.dwinovo.numen.api.NumenPlugins.useRoster(before);
        }

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

    /** 一组会来会去的函数:管理员关开它所在的插件时,她的函数清单跟着变。 */
    public static final class Dyn {

        private Dyn() {}

        @com.dwinovo.numen.api.sdk.Fn("Answer with seven, to show the group is there.")
        @com.dwinovo.numen.api.sdk.Example("gt_dyn.dyn.ping()")
        public static int ping(com.dwinovo.numen.api.sdk.ServerCall call) {
            return 7;
        }

        @com.dwinovo.numen.api.sdk.Fn("Hold still for a few ticks while the caller waits (a short body job).")
        @com.dwinovo.numen.api.sdk.Example("gt_dyn.dyn.hold()")
        public static com.dwinovo.numen.api.sdk.Pending<Void> hold(com.dwinovo.numen.api.sdk.ServerCall call) {
            return call.sync(new DynRecord(call));
        }
    }

    /** 会来会去的插件带的一种活:它的执行器是经门登记的,插件撤回时一起撤。 */
    private static final class DynRecord extends com.dwinovo.numen.api.task.TaskRecord<Void> {
        DynRecord(com.dwinovo.numen.api.sdk.ServerCall call) {
            super(call, call.her().level().getGameTime() + 100);
        }
    }

    private static final class DynTask implements com.dwinovo.numen.api.task.Task {
        private int held;

        @Override
        public String name() {
            return "gt_dyn_hold";
        }

        @Override
        public com.dwinovo.numen.api.task.TaskState tick(NumenPlayer companion) {
            return ++held >= 3 ? com.dwinovo.numen.api.task.TaskState.SUCCESS
                    : com.dwinovo.numen.api.task.TaskState.RUNNING;
        }

        @Override
        public void stop(NumenPlayer companion, StopReason why) {
        }

        @Override
        public com.dwinovo.numen.api.task.TaskResult result(com.dwinovo.numen.api.task.TaskState terminal) {
            return com.dwinovo.numen.api.task.TaskResult.ok("held");
        }
    }

    /** 她这一刻调一次 {@code gt_dyn.dyn.ping()}。 */
    private static ToolRun ping(NumenPlayer companion) {
        return lua(companion, "return gt_dyn.dyn.ping()");
    }

    private static boolean answered(ToolRun run) {
        return run.succeeded() && valueIn(run.reply()).getAsInt() == 7;
    }

    private static boolean listsDyn() {
        return com.dwinovo.numen.api.sdk.ApiDocs.index(com.dwinovo.numen.api.script.Modules.factory()).contains("gt_dyn.dyn");
    }

    /**
     * 管理员当场关掉一个只经门登记的插件,不用重启:她的函数清单里它那一组没了、调它得 no_function;再打开,那一组回来、能调。
     * 管理员收到的那一行是已关闭、不标重启。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_plugin")
    public static void an_admin_switches_a_plugin_off_and_her_function_listing_loses_its_group(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        NumenPlayer companion = spawnAt(helper, "gametest_dynamic", new BlockPos(4, 2, 4), false);
        int before = com.dwinovo.numen.api.task.TaskFactory.size();
        com.dwinovo.numen.api.NumenPlugins.register("gt_dyn", numen -> {
            numen.api("dyn", "Test fixture: a group that comes and goes with its plugin.", Dyn.class);
            numen.taskRunner(DynRecord.class, (body, record) -> new DynTask());
        });
        helper.assertTrue(com.dwinovo.numen.api.task.TaskFactory.size() == before + 1,
                "the plugin's task runner was not registered");
        var admin = GameTestKit.presentPlayer(helper, null, "gametest_plugin_toggler");
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(admin.getGameProfile(),
                4, false));
        com.dwinovo.numen.api.brain.Replies.hello(admin, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        GameTestKit.received(admin);

        helper.assertTrue(listsDyn(), "her listing lacks the group before it was switched off");
        ToolRun on = ping(companion);
        helper.assertTrue(answered(on), "the group's function did not answer: " + on.outcome());

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PLUGIN, "disable",
                "{\"id\":\"gt_dyn\"}");
        var row = pluginRow(GameTestKit.received(admin), "gt_dyn");
        helper.assertTrue(row != null && row.get("disabled").getAsBoolean() && !row.get("pending").getAsBoolean()
                        && "disabled".equals(row.get("status").getAsString()),
                "the admin was not shown the plugin as off at once, without a restart: " + row);
        helper.assertTrue(!listsDyn(), "her listing still has the group after the plugin was switched off");
        ToolRun off = ping(companion);
        helper.assertTrue(!off.succeeded() && "no_function".equals(off.kind()),
                "her program could still call the switched-off group: " + off.outcome());

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PLUGIN, "enable",
                "{\"id\":\"gt_dyn\"}");
        row = pluginRow(GameTestKit.received(admin), "gt_dyn");
        helper.assertTrue(row != null && !row.get("disabled").getAsBoolean() && "loaded".equals(row.get("status").getAsString()),
                "the admin was not shown the plugin as on again: " + row);
        helper.assertTrue(listsDyn(), "her listing did not get the group back");
        ToolRun back = ping(companion);
        helper.assertTrue(answered(back), "the group's function did not answer after switching on: " + back.outcome());

        // 再关再开一轮:任务执行器不留尸体、也不登记两份
        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PLUGIN, "disable",
                "{\"id\":\"gt_dyn\"}");
        GameTestKit.received(admin);
        helper.assertTrue(com.dwinovo.numen.api.task.TaskFactory.size() == before,
                "the task runner stayed registered after the plugin was switched off");
        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PLUGIN, "enable",
                "{\"id\":\"gt_dyn\"}");
        GameTestKit.received(admin);
        helper.assertTrue(com.dwinovo.numen.api.task.TaskFactory.size() == before + 1,
                "the task runner was registered twice or not at all after switching the plugin on again");
        server.getPlayerList().deop(admin.getGameProfile());
        GameTestKit.leave(admin);

        java.util.concurrent.atomic.AtomicReference<ToolRun> held = new java.util.concurrent.atomic.AtomicReference<>();
        steps(helper)
                .thenExecute(() -> held.set(lua(companion, "gt_dyn.dyn.hold()")))
                .thenWaitUntil(() -> helper.assertTrue(held.get().succeeded(),
                        "the switched-on plugin's task did not run: " + held.get().outcome()))
                .thenExecute(() -> CompanionFactory.despawn(server, companion))
                .thenSucceed();
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
