package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 程序从入口跑:模型一次回复里的一段程序,经内脑派发的同一个顺序({@link GameTestKit#round})逐个派 API 调用——占身体的等它收尾
 * 再往下走;成功直接返回值、失败抛错,程序按它分支;主人停止或开口时停在调用之间,回执如实写停在哪一行。模块:按名字直接用,
 * 存、读、列、删、还原出厂的,改了文件下一次就用新的,坏了只影响用它的程序,战绩记在主人那一份里;内置的
 * numen.work.mine 挖空一块埋在石头里的矿;计划写进回执,对话流据此画清单。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class ScriptGameTests {

    private static JsonObject receipt(Round round, LlmToolCall call) {
        return JsonParser.parseString(round.result(call)).getAsJsonObject();
    }

    private static String message(Round round, LlmToolCall call) {
        return receipt(round, call).get("message").getAsString();
    }

    /** 两行 {@code numen.build.set}:一行做完(那件活收尾)才派下一行,两格都拆掉;回执按行各一句,点出那件活的收尾。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_scripts")
    public static void a_program_runs_its_calls_in_order(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_order", new BlockPos(2, 2, 2), true);
        BlockPos first = helper.absolutePos(new BlockPos(4, 2, 2));
        BlockPos second = helper.absolutePos(new BlockPos(2, 2, 4));
        level.setBlockAndUpdate(first, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(second, Blocks.STONE.defaultBlockState());
        LlmToolCall script = programCall("""
                numen.build.set(%s, {block = "air"})
                numen.build.set(%s, {block = "air"})
                """.formatted(xyz(first), xyz(second)));
        Round round = round(helper, her, script);
        EventOutbox outbox = EventOutbox.get(level.getServer());

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            String msg = message(round, script);
            helper.assertTrue(receipt(round, script).get("success").getAsBoolean(), "the script failed: " + msg);
            helper.assertTrue(level.getBlockState(first).isAir() && level.getBlockState(second).isAir(),
                    "not both cells were cleared: " + msg);
            helper.assertTrue(msg.startsWith("The script ran to the end: 2 calls"), msg);
            helper.assertTrue(msg.matches("(?s).*line 1 numen\\.build\\.set: ok — t\\d+ done.*")
                            && msg.matches("(?s).*line 2 numen\\.build\\.set: ok — t\\d+ done.*"),
                    "a line did not wait for its task to finish: " + msg);
            outbox.forget(her.getUUID());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 第一行当场失败(走去用一格空气):库里的 {@code numen.move.goto_} 写路线那一步抛错,脚本接住、不往下走,第二行的那一格还在;
     * 回执说停在哪一行、为什么,失败的那次调用记在调库函数的那一行上。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void a_failed_call_stops_the_lines_that_depend_on_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_branch", new BlockPos(2, 2, 2), true);
        BlockPos air = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockPos kept = helper.absolutePos(new BlockPos(4, 2, 2));
        level.setBlockAndUpdate(kept, Blocks.STONE.defaultBlockState());
        LlmToolCall script = programCall("""
                local walked, why = pcall(numen.move.goto_, %s, {arrive = "use"})
                if not walked then error("could not get there: " .. why, 0) end
                numen.build.set(%s, {block = "air"})
                """.formatted(xyz(air), xyz(kept)));
        Round round = round(helper, her, script);

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            String msg = message(round, script);
            helper.assertTrue(!receipt(round, script).get("success").getAsBoolean(), "the script succeeded: " + msg);
            // 库函数 numen.move.goto_ 里调 numen.route.new 抛出的错误值原样到了脚本:拼进字符串是"函数: 种类 — 原因"
            helper.assertTrue(msg.startsWith("The script stopped at line 2 after 1 call: could not get there: "
                    + "numen.route.new: bad_argument — "), msg);
            helper.assertTrue(msg.contains("line 1 numen.route.new: bad_argument — "), msg);
            helper.assertTrue(level.getBlockState(kept).is(Blocks.STONE), "the line after the failure ran: " + msg);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 走在路上时主人按停止:脚本停在调用之间,回执写明停在第 1 行、那件活一起停了;后面那一行没跑,身体闲下来。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void the_owner_stopping_her_ends_the_script_between_calls(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_stop", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        BlockPos kept = helper.absolutePos(new BlockPos(4, 2, 2));
        level.setBlockAndUpdate(kept, Blocks.STONE.defaultBlockState());
        LlmToolCall script = programCall("""
                numen.move.goto_({x = %d, z = %d})
                numen.build.set(%s, {block = "air"})
                """.formatted(far.getX(), far.getZ(), xyz(kept)));
        Round round = round(helper, her, script);
        EventOutbox outbox = EventOutbox.get(level.getServer());

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(CompanionTickDispatcher.currentTaskFor(her.getUUID()) != null,
                        "the walk has not started"))
                .thenExecute(() -> {
                    helper.assertTrue(round.result(script) == null, "the script ended before the walk did");
                    round.ownerStops();
                })
                .thenWaitUntil(() -> helper.assertTrue(CompanionTickDispatcher.currentTaskFor(her.getUUID()) == null,
                        "her body is still busy after the stop"))
                .thenExecute(() -> {
                    String msg = message(round, script);
                    helper.assertTrue(msg.matches("(?s)The script stopped at line 1 \\(numen\\.move\\.go\\) after 3 calls: "
                            + "this turn was cut off; t\\d+ was stopped too\\. Nothing after that ran\\..*"), msg);
                    helper.assertTrue(level.getBlockState(kept).is(Blocks.STONE), "the line after the stop ran");
                    outbox.forget(her.getUUID());
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    /** 走在路上时主人开口:脚本停在调用之间,回执说主人开口了、那件活照常跑;在走的那段路没停。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void the_owner_speaking_ends_the_script_and_the_walk_goes_on(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_spoken", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        LlmToolCall script = programCall("""
                numen.move.goto_({x = %d, z = %d})
                numen.move.goto_({x = %d, z = %d})
                """.formatted(far.getX(), far.getZ(), far.getX() - 10, far.getZ()));
        Round round = round(helper, her, script);
        EventOutbox outbox = EventOutbox.get(level.getServer());

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(CompanionTickDispatcher.currentTaskFor(her.getUUID()) != null,
                        "the walk has not started"))
                .thenExecute(() -> round.ownerSays("wait, come back"))
                .thenExecute(() -> {
                    String msg = message(round, script);
                    helper.assertTrue(msg.matches("(?s)The script stopped at line 1 \\(numen\\.move\\.go\\) after 3 calls: "
                            + "your owner spoke; t\\d+ keeps running\\..*"), msg);
                    TaskRecord now = CompanionTickDispatcher.currentTaskFor(her.getUUID());
                    helper.assertTrue(now != null && now.getToolName().equals("numen.move.go"),
                            "the walk is no longer running: " + now);
                    outbox.forget(her.getUUID());
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    /**
     * 一次扫描的每一团:{@code numen.scan.blocks} 直接给出团的列表(近的在前),脚本逐个走过去挖;两团矿都挖掉,打印的是两团最近那一格,
     * 近的在前。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_scripts")
    public static void a_program_goes_through_the_clusters_of_a_scan(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos far = helper.absolutePos(new BlockPos(11, 2, 11));
        level.setBlockAndUpdate(near, Blocks.IRON_ORE.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.IRON_ORE.defaultBlockState());
        NumenPlayer her = spawnAt(helper, "gametest_lua_parts", new BlockPos(2, 2, 2), false);
        her.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        LlmToolCall script = programCall("""
                for _, c in ipairs(numen.scan.blocks("minecraft:iron_ore", {radius = 14})) do
                  print(c.nearest.pos.x, c.nearest.pos.z)
                  numen.move.goto_(c.blocks, {arrive = "dig"})
                  numen.work.dig(c.blocks)
                end
                """);
        Round[] round = new Round[1];

        steps(helper)
                .thenExecute(() -> round[0] = round(helper, her, script))
                .thenWaitUntil(() -> {
                    helper.assertTrue(round[0].hasSettled(), "the script has not finished");
                    String msg = message(round[0], script);
                    helper.assertTrue(receipt(round[0], script).get("success").getAsBoolean(),
                            "the script failed: " + msg);
                    helper.assertTrue(msg.contains("printed:\n" + near.getX() + "\t" + near.getZ() + "\n" + far.getX()
                                    + "\t" + far.getZ()), "it did not go through both clusters, near first: " + msg);
                    helper.assertTrue(!level.getBlockState(near).is(Blocks.IRON_ORE)
                            && !level.getBlockState(far).is(Blocks.IRON_ORE), "not both clusters were dug: " + msg);
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), her))
                .thenSucceed();
    }

    /**
     * 内置的 numen.work.mine:四颗铁矿埋在一块石头里,一段程序扫到它们,把那一团交给 {@code numen.work.mine} 挖空——走到够得着、挖、
     * 捡,直到交给它的一格不剩;铁都进了包,返回挖了几格。它里面没有一处接住错误往下走:哪一步失败,整段就停在那一步。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_scripts")
    public static void work_mine_digs_out_ore_buried_in_stone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 6; x <= 12; x++) {
            for (int z = 6; z <= 12; z++) {
                for (int y = 2; y <= 6; y++) {
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, y, z)), Blocks.STONE.defaultBlockState());
                }
            }
        }
        List<BlockPos> ores = List.of(new BlockPos(9, 3, 9), new BlockPos(10, 3, 9), new BlockPos(9, 3, 10),
                new BlockPos(9, 4, 9)).stream().map(helper::absolutePos).toList();
        ores.forEach(ore -> level.setBlockAndUpdate(ore, Blocks.IRON_ORE.defaultBlockState()));
        NumenPlayer her = spawnAt(helper, "gametest_lua_mine", new BlockPos(9, 7, 9), false);
        her.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        LlmToolCall run = programCall(
                "return numen.work.mine(numen.scan.blocks(\"minecraft:iron_ore\", {radius = 8})[1].blocks)");
        Round[] round = new Round[1];

        steps(helper)
                .thenExecute(() -> round[0] = round(helper, her, run))
                .thenWaitUntil(() -> {
                    helper.assertTrue(round[0].hasSettled(), "numen.work.mine has not finished");
                    String msg = message(round[0], run);
                    helper.assertTrue(msg.startsWith("The script ran to the end"), msg);
                    helper.assertTrue(receipt(round[0], run).getAsJsonObject("data").get("returned").getAsInt()
                            >= ores.size(), "numen.work.mine did not return the cells it dug: " + msg);
                    for (BlockPos ore : ores) {
                        helper.assertTrue(!level.getBlockState(ore).is(Blocks.IRON_ORE),
                                "ore left at " + ore + ": " + msg);
                    }
                    helper.assertTrue(her.getInventory().countItem(Items.RAW_IRON) == ores.size(),
                            "she carries " + her.getInventory().countItem(Items.RAW_IRON) + " raw iron of "
                                    + ores.size() + ": " + msg);
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), her))
                .thenSucceed();
    }

    /** 出厂的一个夹具模块:改它、删它、还原它,不碰别的 GameTest 在用的出厂模块。 */
    private static final String COPIED = "gt.copied";

    static {
        if (GameTestKit.numenTestsEnabled()) {
            com.dwinovo.numen.script.BuiltinModules.register(COPIED, """
                    -- Test fixture: a factory module to change, delete and reset.
                    local M = {}
                    ---Say which version this is.
                    function M.version() return 1 end
                    return M
                    """);
        }
    }

    /**
     * 出厂模块装在她的目录里,读得到、改得了、删得掉、还原得回:{@code numen.module.show} 给出全文;她照抄一份、加一个函数,存成同名的
     * ——程序里用的就是改过的,原有的函数照样在;清单标出"出厂的,改过";{@code {factory = true}} 还看得到出厂原文;删掉之后程序里没有了,
     * 不会自己装回;{@code reset} 装回出厂那一份,她加的函数没了。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_scripts")
    public static void a_factory_module_is_changed_deleted_and_reset(GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_lua_copier", new BlockPos(2, 2, 2), false);
        ToolRun shown = lua(her, "return numen.module.show(\"" + COPIED + "\")");
        helper.assertTrue(shown.receipt() != null && shown.receipt().contains("function M.version()"),
                "the factory module does not read as itself: " + shown.receipt());
        ToolRun changed = lua(her, """
                local code = numen.module.show("gt.copied").code
                local mine = string.gsub(code, "\\nreturn M%s*$", "\\nfunction M.gt_marker() return 7 end\\nreturn M\\n")
                numen.module.save(mine, {name = "gt.copied"})
                return gt.copied.gt_marker() + gt.copied.version()
                """);
        helper.assertTrue(changed.ranToTheEnd() && changed.receipt().contains("returned: 8"),
                "her change was not used: " + changed.receipt());
        String listed = lua(her, "numen.module.list()").reply();
        helper.assertTrue(listed.contains(COPIED + " — Test fixture") && listed.contains("[built in, changed]"), listed);
        helper.assertTrue(lua(her, "return numen.module.show(\"" + COPIED + "\", {factory = true}).code").receipt()
                        .contains("function M.version()") && !lua(her, "return numen.module.show(\"" + COPIED
                        + "\", {factory = true}).code").receipt().contains("gt_marker"), "the factory text is gone");
        ToolRun deleted = lua(her, "numen.module.delete(\"" + COPIED + "\")");
        helper.assertTrue(deleted.succeeded() && deleted.reply().contains("it stays deleted until numen.module.reset"),
                deleted.reply());
        ToolRun gone = lua(her, "return gt.copied.version()");
        helper.assertTrue(!gone.ranToTheEnd() && gone.receipt().contains("no_function"),
                "a deleted module is still used: " + gone.receipt());
        helper.assertTrue(lua(her, "numen.module.list()").outcome().contains("[built in, deleted; "
                + "numen.module.reset(\"" + COPIED + "\") brings it back]"), "the list does not say it was deleted");
        ToolRun reset = lua(her, "numen.module.reset(\"" + COPIED + "\")");
        helper.assertTrue(reset.succeeded(), reset.reply());
        ToolRun back = lua(her, "return gt.copied.version(), type(gt.copied.gt_marker)");
        helper.assertTrue(back.ranToTheEnd() && back.receipt().contains("returned: 1"),
                "reset did not bring the factory text back: " + back.receipt());
        helper.assertTrue(!lua(her, "return gt.copied.gt_marker()").ranToTheEnd(), "her function outlived the reset");
        CompanionFactory.despawn(helper.getLevel().getServer(), her);
        helper.succeed();
    }

    /**
     * 她存一个自己的模块(在 my 下)、读它、在两段程序里按名字 {@code my.gt_clear} 用它,战绩累计;读不通的、改第 ① 层函数的、不在
     * my 下又不是内置名字的不收、说为什么;主人拿编辑器改了文件,下一段程序就用新的;目录里一个坏模块只让用到它的程序出错;她自己的
     * 删得掉。模块目录是这次 GameTest 专用的,不是主人的。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_scripts")
    public static void she_saves_uses_and_deletes_a_module_of_her_own(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_saver", new BlockPos(2, 2, 2), true);
        BlockPos cell = helper.absolutePos(new BlockPos(4, 2, 2));
        level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
        EventOutbox outbox = EventOutbox.get(level.getServer());
        java.nio.file.Path dir = com.dwinovo.numen.script.Modules.of(her.getUUID()).dir();
        helper.assertTrue(dir.getFileName().toString().startsWith("numen-gametest-lua-"),
                "GameTest reads modules from " + dir + ", not from its own empty directory");

        ToolRun flat = lua(her, "numen.module.save(\"-- Flat.\\nreturn {}\", {name = \"gt_flat\"})");
        helper.assertTrue(!flat.succeeded() && flat.reply().contains("my.gt_flat"),
                "a module of her own was kept outside my: " + flat.reply());
        ToolRun broken = lua(her, "numen.module.save(\"-- Never compiles.\\nlocal x = = 1\", {name = \"my.gt_broken\"})");
        helper.assertTrue(!broken.succeeded() && broken.reply().contains("my.gt_broken:2:"),
                "a module that does not compile was kept: " + broken.reply());
        ToolRun redefines = lua(her, "numen.module.save(\"-- Takes numen.build.set.\\nlocal M = {}\\nfunction numen.build.set() end\\n"
                + "return M\", {name = \"my.gt_thief\"})");
        helper.assertTrue(!redefines.succeeded() && redefines.reply().contains("numen.build.set is an API function"),
                "a module that redefines an API function was kept: " + redefines.reply());

        ToolRun saved = lua(her, "numen.module.save(\"-- Clearing cells.\\nlocal M = {}\\n---Clear one cell.\\n"
                + "function M.cell(p)\\n  numen.build.set(p, {block = 'air'})\\nend\\nreturn M\", {name = \"my.gt_clear\"})");
        helper.assertTrue(saved.succeeded() && saved.reply().contains("Saved module my.gt_clear"), saved.reply());
        ToolRun shown = lua(her, "numen.module.show(\"my.gt_clear\")");
        helper.assertTrue(shown.succeeded() && shown.reply().contains("(yours)")
                && shown.reply().contains("function M.cell(p)") && shown.reply().contains("No program used it yet."),
                shown.reply());

        String at = com.dwinovo.numen.cli.Shapes.literal(cell);
        LlmToolCall run = programCall("my.gt_clear.cell(" + at + ")");
        Round first = round(helper, her, run);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(first.hasSettled(), "the first program has not finished"))
                .thenExecute(() -> {
                    String msg = message(first, run);
                    helper.assertTrue(msg.startsWith("The script ran to the end") && msg.contains("numen.build.set: ok"),
                            msg);
                    helper.assertTrue(level.getBlockState(cell).isAir(), "the module did not clear the cell");
                    level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
                    // 第一次的收尾事件已经读过;下一轮从空出箱读起,和主人客户端上取走即清一样
                    outbox.forget(her.getUUID());
                })
                .thenExecute(() -> round(helper, her, programCall("my.gt_clear.cell(" + at + ")")))
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(cell).isAir(),
                        "the second program did not clear the cell"))
                .thenWaitUntil(() -> {
                    String listed = lua(her, "numen.module.list()").reply();
                    helper.assertTrue(listed.contains("my.gt_clear — Clearing cells. [yours] Programs that used it: 2, "
                            + "ran to the end: 2"), "the record does not add up: " + listed);
                    helper.assertTrue(listed.contains("numen.work — ") && listed.contains("[built in]"), listed);
                })
                .thenExecute(() -> {
                    // 主人拿编辑器改了文件:不重启,下一段程序就用新的
                    try {
                        java.nio.file.Files.writeString(dir.resolve("my").resolve("gt_clear.lua"),
                                "-- Clearing cells.\nlocal M = {}\n---Say which version this is.\n"
                                        + "function M.version() return 2 end\nreturn M\n");
                        java.nio.file.Files.writeString(dir.resolve("my").resolve("gt_rotten.lua"), "local M = {\n");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    ToolRun edited = lua(her, "return my.gt_clear.version()");
                    helper.assertTrue(edited.ranToTheEnd() && edited.receipt().contains("returned: 2"),
                            "the edited file was not used: " + edited.receipt());
                    ToolRun untouched = lua(her, "return my.gt_clear.version() + 1");
                    helper.assertTrue(untouched.ranToTheEnd(), "a broken module nobody uses broke a program: "
                            + untouched.receipt());
                    ToolRun rotten = lua(her, "return my.gt_rotten.x");
                    helper.assertTrue(!rotten.ranToTheEnd() && rotten.receipt().contains("module my.gt_rotten does not "
                            + "compile"), rotten.receipt());
                    helper.assertTrue(lua(her, "numen.module.delete(\"my.gt_rotten\")").succeeded(), "the rotten one stays");
                    ToolRun deleted = lua(her, "numen.module.delete(\"my.gt_clear\")");
                    helper.assertTrue(deleted.succeeded(), deleted.reply());
                    helper.assertTrue(!lua(her, "numen.module.show(\"my.gt_clear\")").succeeded(),
                            "the deleted module is still there");
                    outbox.forget(her.getUUID());
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    /**
     * 计划写进回执:{@code numen.todo.write} 收下整份计划,回执的数据里原样留着这次调用的参数,对话流从那里读出清单;做着的不止一项
     * 被拒,说清只能有一项在做。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_scripts")
    public static void a_written_plan_is_echoed_on_the_receipt(GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_lua_planner", new BlockPos(2, 2, 2), false);
        ToolRun plan = lua(her, "numen.todo.write({\"[x] walk to the mine\", \"[>] dig the iron\", \"[ ] smelt it\"})");
        helper.assertTrue(plan.ranToTheEnd(), "the plan was not written: " + plan.receipt());
        JsonObject data = JsonParser.parseString(plan.receipt()).getAsJsonObject().getAsJsonObject("data");
        var echoed = data.getAsJsonArray("echoed");
        helper.assertTrue(echoed != null && echoed.size() == 1
                        && echoed.get(0).getAsJsonObject().get("function").getAsString().equals("numen.todo.write")
                        && echoed.get(0).getAsJsonObject().getAsJsonObject("args").getAsJsonArray("items").size() == 3,
                "the plan is not echoed on the receipt: " + plan.receipt());
        helper.assertTrue(plan.receipt().contains("plan written: 1/3 done; doing now: dig the iron"), plan.receipt());
        ToolRun two = lua(her, "numen.todo.write({\"[>] dig\", \"[>] smelt\"})");
        helper.assertTrue(!two.ranToTheEnd() && two.receipt().contains("exactly one step is [>]"),
                "a plan with two steps in progress was taken: " + two.receipt());
        CompanionFactory.despawn(helper.getLevel().getServer(), her);
        helper.succeed();
    }

    /**
     * 查到的东西原样交给动作:{@code numen.scan.blocks} 一团的最近一格、{@code numen.scan.block} 读到的一块,都直接进 {@code numen.work.dig};两格都挖掉,
     * 程序拿到的是两份数据({@code dug} 各一格),不是话。
     */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_scripts")
    public static void what_a_query_returns_goes_into_an_action_as_it_is(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_handover", new BlockPos(4, 2, 4), true);
        BlockPos first = helper.absolutePos(new BlockPos(6, 2, 4));
        BlockPos second = helper.absolutePos(new BlockPos(4, 2, 6));
        level.setBlockAndUpdate(first, Blocks.PEARLESCENT_FROGLIGHT.defaultBlockState());
        level.setBlockAndUpdate(second, Blocks.VERDANT_FROGLIGHT.defaultBlockState());
        LlmToolCall script = programCall("""
                local found = numen.scan.blocks("minecraft:pearlescent_froglight", {radius = 6})
                local a = numen.work.dig(found[1].nearest)
                local b = numen.work.dig(numen.scan.block(%s))
                return {a.dug, b.dug}
                """.formatted(xyz(second)));
        Round round = round(helper, her, script);

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            JsonObject receipt = receipt(round, script);
            helper.assertTrue(receipt.get("success").getAsBoolean(), "the script failed: " + receipt);
            helper.assertTrue(receipt.getAsJsonObject("data").get("returned").toString().equals("[1,1]"),
                    "the two digs did not hand back one cell each as data: " + receipt);
            helper.assertTrue(level.getBlockState(first).isAir() && level.getBlockState(second).isAir(),
                    "a block handed on as it was is still standing: " + receipt);
            EventOutbox.get(level.getServer()).forget(her.getUUID());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 一只实体原样就是一处地方:{@code numen.scan.entities} 列出的那头牛交给 {@code numen.move.goto_},她走到牛跟前。 */
    @GameTest(template = "floor16", timeoutTicks = 2000, batch = "numen_scripts")
    public static void an_entity_a_scan_found_is_a_place_to_walk_to(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_herder", new BlockPos(2, 2, 2), false);
        var cow = net.minecraft.world.entity.EntityType.COW.create(level);
        helper.assertTrue(cow != null, "the cow did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(12, 2, 12));
        cow.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        cow.setNoAi(true);
        level.addFreshEntity(cow);
        LlmToolCall script = programCall("""
                local cow = numen.scan.entities("passive", {radius = 20})[1]
                numen.move.goto_(cow, {arrive = "near"})
                return cow.id
                """);
        Round round = round(helper, her, script);

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            JsonObject receipt = receipt(round, script);
            helper.assertTrue(receipt.get("success").getAsBoolean()
                            && receipt.getAsJsonObject("data").get("returned").getAsInt() == cow.getId(),
                    "the walk to the cow failed: " + receipt);
            helper.assertTrue(her.distanceTo(cow) <= 4, "she did not get to the cow: " + her.distanceTo(cow));
            EventOutbox.get(level.getServer()).forget(her.getUUID());
            cow.discard();
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 失败是一个值:{@code pcall} 接住的错误有种类与能照抄的下一行,拼进字符串是"函数: 种类 — 原因"。够不着的一格是
     * {@code out_of_reach},下一行是走过去再挖;旧写法(三个数的列表、一串字)是 {@code bad_argument},下一行是改写好的那一次调用。
     * 哪一次都没挖。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_scripts")
    public static void a_failed_call_is_a_value_with_its_kind_and_next_line(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_catcher", new BlockPos(1, 2, 1), true);
        BlockPos far = helper.absolutePos(new BlockPos(14, 2, 14));
        BlockPos near = helper.absolutePos(new BlockPos(3, 2, 1));
        level.setBlockAndUpdate(far, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        level.setBlockAndUpdate(near, Blocks.OCHRE_FROGLIGHT.defaultBlockState());
        LlmToolCall script = programCall("""
                local _, far = pcall(numen.work.dig, %s)
                local _, list = pcall(numen.work.dig, {%d, %d, %d})
                local _, text = pcall(numen.work.dig, "%d %d %d")
                print("caught: " .. far)
                return {far = {far.kind, far.hint}, list = {list.kind, list.hint}, text = {text.kind, text.hint}}
                """.formatted(xyz(far), near.getX(), near.getY(), near.getZ(), near.getX(), near.getY(), near.getZ()));
        Round round = round(helper, her, script);

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            JsonObject receipt = receipt(round, script);
            helper.assertTrue(receipt.get("success").getAsBoolean(), "the script did not catch the errors: " + receipt);
            JsonObject got = receipt.getAsJsonObject("data").getAsJsonObject("returned");
            var farErr = got.getAsJsonArray("far");
            helper.assertTrue("out_of_reach".equals(farErr.get(0).getAsString())
                            && farErr.get(1).getAsString().equals("numen.move.goto_(" + xyz(far) + ", {arrive = \"dig\"})\n"
                                    + "numen.work.dig(" + xyz(far) + ")"),
                    "out of reach is not its own kind with the walk as the next line: " + got);
            String rewritten = "numen.work.dig(" + xyz(near) + ")";
            for (String shape : List.of("list", "text")) {
                var err = got.getAsJsonArray(shape);
                helper.assertTrue("bad_argument".equals(err.get(0).getAsString())
                                && rewritten.equals(err.get(1).getAsString()),
                        "the old " + shape + " shape is not refused with the call rewritten: " + got);
            }
            helper.assertTrue(receipt.get("message").getAsString().contains("caught: numen.work.dig: out_of_reach — "),
                    "an error joined into a string does not read as function, kind and why: " + receipt);
            helper.assertTrue(level.getBlockState(far).is(Blocks.OCHRE_FROGLIGHT)
                    && level.getBlockState(near).is(Blocks.OCHRE_FROGLIGHT), "a refused call dug a block");
            CompanionFactory.despawn(level.getServer(), her);
        });
    }
}
