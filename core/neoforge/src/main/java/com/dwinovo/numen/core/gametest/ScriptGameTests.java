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
 * 脚本从入口跑:模型一次回复里的一条组合命令的调用(或一行 {@code script run}),经内脑派发的同一个顺序({@link GameTestKit#round})
 * 逐条派命令——每个命令函数就是那一行命令,占身体的等它收尾再往下走;成功直接返回值、失败抛错,脚本按它分支;主人停止或开口时
 * 停在命令之间,回执如实写停在哪一行。脚本这个名词:存、读、列、删、按名字带参数跑,战绩记在主人名下;内置的 mine 挖空一块
 * 埋在石头里的矿。
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

    /** 一格写成脚本里的三个数。 */
    private static String lua(BlockPos pos) {
        return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
    }

    /** 两行 {@code build.set}:一行做完(那件活收尾)才派下一行,两格都拆掉;回执按行各一句,点出那件活的收尾。 */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_scripts")
    public static void a_program_runs_its_commands_in_order(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_order", new BlockPos(2, 2, 2), true);
        BlockPos first = helper.absolutePos(new BlockPos(4, 2, 2));
        BlockPos second = helper.absolutePos(new BlockPos(2, 2, 4));
        level.setBlockAndUpdate(first, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(second, Blocks.STONE.defaultBlockState());
        LlmToolCall script = programCall("""
                build.set(%s, {block = "air"})
                build.set({%s}, {block = "air"})
                """.formatted(lua(first), lua(second)));
        Round round = round(helper, her, script);
        EventOutbox outbox = EventOutbox.get(level.getServer());

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            String msg = message(round, script);
            helper.assertTrue(receipt(round, script).get("success").getAsBoolean(), "the script failed: " + msg);
            helper.assertTrue(level.getBlockState(first).isAir() && level.getBlockState(second).isAir(),
                    "not both cells were cleared: " + msg);
            helper.assertTrue(msg.startsWith("The script ran to the end: 2 commands"), msg);
            helper.assertTrue(msg.matches("(?s).*line 1 build\\.set: ok — t\\d+ done.*")
                            && msg.matches("(?s).*line 2 build\\.set: ok — t\\d+ done.*"),
                    "a line did not wait for its task to finish: " + msg);
            outbox.forget(her.getUUID());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 第一行当场失败(走去用一格空气):它抛错,脚本接住、不往下走,第二行的那一格还在;回执说停在哪一行、为什么。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void a_failed_command_stops_the_lines_that_depend_on_it(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_branch", new BlockPos(2, 2, 2), true);
        BlockPos air = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockPos kept = helper.absolutePos(new BlockPos(4, 2, 2));
        level.setBlockAndUpdate(kept, Blocks.STONE.defaultBlockState());
        LlmToolCall script = programCall("""
                local walked, why = pcall(move.goto_, %s, {arrive = "use"})
                if not walked then error("could not get there: " .. why, 0) end
                build.set(%s, {block = "air"})
                """.formatted(lua(air), lua(kept)));
        Round round = round(helper, her, script);

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            String msg = message(round, script);
            helper.assertTrue(!receipt(round, script).get("success").getAsBoolean(), "the script succeeded: " + msg);
            helper.assertTrue(msg.startsWith("The script stopped at line 2 after 1 command: could not get there: "
                    + "move.goto_: "), msg);
            helper.assertTrue(msg.contains("line 1 move.goto_: failed"), msg);
            helper.assertTrue(level.getBlockState(kept).is(Blocks.STONE), "the line after the failure ran: " + msg);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 走在路上时主人按停止:脚本停在命令之间,回执写明停在第 1 行、那件活一起停了;后面那一行没跑,身体闲下来。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void the_owner_stopping_her_ends_the_script_between_commands(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_stop", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        BlockPos kept = helper.absolutePos(new BlockPos(4, 2, 2));
        level.setBlockAndUpdate(kept, Blocks.STONE.defaultBlockState());
        LlmToolCall script = programCall("""
                move.goto_(%d, %d)
                build.set(%s, {block = "air"})
                """.formatted(far.getX(), far.getZ(), lua(kept)));
        Round round = round(helper, her, script);
        EventOutbox outbox = EventOutbox.get(level.getServer());

        steps(helper)
                .thenExecuteAfter(5, () -> {
                    helper.assertTrue(round.result(script) == null, "the script ended before the walk did");
                    round.ownerStops();
                })
                .thenWaitUntil(() -> helper.assertTrue(CompanionTickDispatcher.currentTaskFor(her.getUUID()) == null,
                        "her body is still busy after the stop"))
                .thenExecute(() -> {
                    String msg = message(round, script);
                    helper.assertTrue(msg.matches("(?s)The script stopped at line 1 \\(move\\.goto_\\) after 1 command: "
                            + "this turn was cut off; t\\d+ was stopped too\\. Nothing after that ran\\..*"), msg);
                    helper.assertTrue(level.getBlockState(kept).is(Blocks.STONE), "the line after the stop ran");
                    outbox.forget(her.getUUID());
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    /** 走在路上时主人开口:脚本停在命令之间,回执说主人开口了、那件活照常跑;在走的那段路没停。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void the_owner_speaking_ends_the_script_and_the_walk_goes_on(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_spoken", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        LlmToolCall script = programCall("""
                move.goto_(%d, %d)
                move.goto_(%d, %d)
                """.formatted(far.getX(), far.getZ(), far.getX() - 10, far.getZ()));
        Round round = round(helper, her, script);
        EventOutbox outbox = EventOutbox.get(level.getServer());

        steps(helper)
                .thenExecuteAfter(5, () -> round.ownerSays("wait, come back"))
                .thenExecute(() -> {
                    String msg = message(round, script);
                    helper.assertTrue(msg.matches("(?s)The script stopped at line 1 \\(move\\.goto_\\) after 1 command: "
                            + "your owner spoke; t\\d+ keeps running\\..*"), msg);
                    TaskRecord now = CompanionTickDispatcher.currentTaskFor(her.getUUID());
                    helper.assertTrue(now != null && now.getToolName().equals("move goto"),
                            "the walk is no longer running: " + now);
                    outbox.forget(her.getUUID());
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    /**
     * 一块区域的每一部分:{@code area.parts} 直接给出名字的列表,脚本逐个走过去挖;两团矿都挖掉,打印的就是那两个名字。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_scripts")
    public static void a_program_goes_through_the_parts_of_an_area(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos near = helper.absolutePos(new BlockPos(4, 2, 4));
        BlockPos far = helper.absolutePos(new BlockPos(11, 2, 11));
        level.setBlockAndUpdate(near, Blocks.IRON_ORE.defaultBlockState());
        level.setBlockAndUpdate(far, Blocks.IRON_ORE.defaultBlockState());
        NumenPlayer her = spawnAt(helper, "gametest_lua_parts", new BlockPos(2, 2, 2), false);
        her.getInventory().add(new ItemStack(Items.IRON_PICKAXE));
        ToolRun scanned = scanInto(her, 14, "minecraft:iron_ore", "ores");
        LlmToolCall script = programCall("""
                for _, p in ipairs(area.parts("ores")) do
                  print(p)
                  move.goto_(p, {arrive = "dig"})
                  work.dig(p)
                end
                """);
        Round[] round = new Round[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(scanned.done(), "the scan has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(scanned.succeeded(), "the scan failed: " + scanned.reply());
                    round[0] = round(helper, her, script);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(round[0].hasSettled(), "the script has not finished");
                    String msg = message(round[0], script);
                    helper.assertTrue(receipt(round[0], script).get("success").getAsBoolean(),
                            "the script failed: " + msg);
                    helper.assertTrue(msg.contains("printed:\nores/g1\nores/g2"),
                            "it did not go through both parts: " + msg);
                    helper.assertTrue(!level.getBlockState(near).is(Blocks.IRON_ORE)
                            && !level.getBlockState(far).is(Blocks.IRON_ORE), "not both parts were dug: " + msg);
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), her))
                .thenSucceed();
    }

    /**
     * 内置的 mine:四颗铁矿埋在一块石头里,扫进区域后一行 {@code script run mine ores} 挖空它——走到够得着、挖、捡,直到区域里
     * 一格不剩;铁都进了包,回执说跑完了,战绩记一次跑完。
     */
    @GameTest(template = "floor20", timeoutTicks = 100000, batch = "numen_scripts")
    public static void the_mine_script_digs_out_ore_buried_in_stone(GameTestHelper helper) {
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
        ToolRun scanned = scanInto(her, 8, "minecraft:iron_ore", "ores");
        LlmToolCall run = commandCall("script run mine ores");
        Round[] round = new Round[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(scanned.done(), "the scan has not replied"))
                .thenExecute(() -> {
                    helper.assertTrue(scanned.succeeded(), "the scan failed: " + scanned.reply());
                    round[0] = round(helper, her, run);
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(round[0].hasSettled(), "mine has not finished");
                    String msg = message(round[0], run);
                    helper.assertTrue(msg.startsWith("Script mine ran to the end"), msg);
                    for (BlockPos ore : ores) {
                        helper.assertTrue(!level.getBlockState(ore).is(Blocks.IRON_ORE),
                                "ore left at " + ore + ": " + msg);
                    }
                    helper.assertTrue(her.getInventory().countItem(Items.RAW_IRON) == ores.size(),
                            "she carries " + her.getInventory().countItem(Items.RAW_IRON) + " raw iron of "
                                    + ores.size() + ": " + msg);
                    helper.assertTrue(command(her, "script list").reply().contains("Runs: 1, ran to the end: 1"),
                            "the run was not counted");
                })
                .thenExecute(() -> CompanionFactory.despawn(level.getServer(), her))
                .thenSucceed();
    }

    /**
     * 她存一份自己的脚本、读它、带参数按名字跑两次,战绩累计;读不通的不收、说哪一行;内置的同名存不进、删不掉;她自己的删得掉。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_scripts")
    public static void she_saves_reads_runs_and_deletes_her_own_script(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_saver", new BlockPos(2, 2, 2), true);
        BlockPos cell = helper.absolutePos(new BlockPos(4, 2, 2));
        level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
        EventOutbox outbox = EventOutbox.get(level.getServer());

        ToolRun broken = command(her, "script save \"-- Never compiles.\nlocal x = = 1\" --name gt-broken");
        helper.assertTrue(!broken.succeeded() && broken.reply().contains("does not compile")
                && broken.reply().contains("gt-broken:2:"), "a script that does not compile was kept: " + broken.reply());
        ToolRun builtin = command(her, "script save \"-- Mine nothing.\nprint(1)\" --name mine");
        helper.assertTrue(!builtin.succeeded() && builtin.reply().contains("read-only"),
                "a built-in script was overwritten: " + builtin.reply());
        helper.assertTrue(!command(her, "script delete mine").succeeded(), "a built-in script was deleted");

        ToolRun saved = command(her, "script save \"-- Clear the cell at x y z.\nlocal x, y, z = ...\n"
                + "build.set(x, y, z, {block = 'air'})\" --name gt-clear");
        helper.assertTrue(saved.succeeded() && saved.reply().contains("Saved script gt-clear"), saved.reply());
        ToolRun shown = command(her, "script show gt-clear");
        helper.assertTrue(shown.succeeded() && shown.reply().contains("saved by gametest_lua_saver")
                && shown.reply().contains("build.set(x, y, z") && shown.reply().contains("Never run."), shown.reply());

        LlmToolCall run = commandCall("script run gt-clear " + xyz(cell));
        Round first = round(helper, her, run);

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(first.hasSettled(), "the first run has not finished"))
                .thenExecute(() -> {
                    String msg = message(first, run);
                    helper.assertTrue(msg.startsWith("Script gt-clear ran to the end: 1 command"), msg);
                    helper.assertTrue(level.getBlockState(cell).isAir(), "the script did not clear the cell");
                    level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
                    // 第一次的收尾事件已经读过;下一轮从空出箱读起,和主人客户端上取走即清一样
                    outbox.forget(her.getUUID());
                })
                .thenExecute(() -> round(helper, her, commandCall("script run gt-clear " + xyz(cell))))
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(cell).isAir(),
                        "the second run did not clear the cell"))
                .thenWaitUntil(() -> {
                    String listed = command(her, "script list").reply();
                    helper.assertTrue(listed.contains("gt-clear — Clear the cell at x y z. [saved by gametest_lua_saver]"
                            + " Runs: 2, ran to the end: 2"), "the record does not add up: " + listed);
                    helper.assertTrue(listed.contains("mine — ") && listed.contains("[built in]"), listed);
                })
                .thenExecute(() -> {
                    ToolRun deleted = command(her, "script delete gt-clear");
                    helper.assertTrue(deleted.succeeded(), deleted.reply());
                    helper.assertTrue(!command(her, "script show gt-clear").succeeded(),
                            "the deleted script is still there");
                    outbox.forget(her.getUUID());
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }
}
