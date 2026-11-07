package com.dwinovo.numen.gametest;

import net.minecraft.world.entity.EntitySpawnReason;
import com.dwinovo.numen.api.gametest.Checks;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.network.Fragments;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.ClientCallPayload;
import com.dwinovo.numen.api.network.payload.ClientCallResultPayload;
import com.dwinovo.numen.api.network.payload.FragmentPayload;
import com.dwinovo.numen.api.program.CallObserver;
import com.dwinovo.numen.api.program.ClientEndpoint;
import com.dwinovo.numen.api.program.NetworkTransport;
import com.dwinovo.numen.api.program.ServerPrograms;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import com.dwinovo.numen.api.task.TaskRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import net.minecraft.world.Difficulty;
import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 程序从入口跑:模型一次回复里的一段程序,经内脑派发的同一个顺序({@link GameTestKit#round})逐个派 API 调用——占身体的等它收尾
 * 再往下走;成功直接返回值、失败抛错,程序按它分支;主人停止或开口时停在调用之间,回执如实写停在哪一行。模块:按名字直接用,
 * 存、读、列、删、还原出厂的,改了文件下一次就用新的,坏了只影响用它的程序,战绩记在主人那一份里;内置的
 * numen.work.mine 挖空一块埋在石头里的矿;计划写进回执,对话流据此画清单。
 */
public class ScriptGameTests {

    // 每个批次都自己定下世界(难度、时刻、天气、模拟距离、权限设置),不继承上一批留下的:批次的先后说不准
    @BeforeBatch(batch = "numen_scripts")
    public static void prepareScriptsBatch(ServerLevel level) {
        GameTestKit.settleWorld(level, Difficulty.PEACEFUL, GameTestKit.NOON);
    }

    private static JsonObject receipt(Round round, LlmToolCall call) {
        return JsonParser.parseString(round.result(call)).getAsJsonObject();
    }

    /** {@code numen.module.list} 交回的清单里叫 {@code name} 的那一份;没有是 null。 */
    private static JsonObject module(ToolRun list, String name) {
        for (var row : valueIn(list.reply()).getAsJsonArray()) {
            if (row.getAsJsonObject().get("name").getAsString().equals(name)) {
                return row.getAsJsonObject();
            }
        }
        return null;
    }

    private static String message(Round round, LlmToolCall call) {
        return receipt(round, call).get("message").getAsString();
    }

    /** 两行 {@code numen.build.place}:一行做完(那件活收尾)才派下一行,两格都拆掉;回执按行各一句,点出那件活的收尾。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void a_program_runs_its_calls_in_order(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_order", new BlockPos(2, 2, 2), true);
        BlockPos first = helper.absolutePos(new BlockPos(4, 2, 2));
        BlockPos second = helper.absolutePos(new BlockPos(2, 2, 4));
        level.setBlockAndUpdate(first, Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(second, Blocks.STONE.defaultBlockState());
        LlmToolCall script = programCall("""
                numen.build.place({{name = "air", pos = %s}})
                numen.build.place({{name = "air", pos = %s}})
                """.formatted(xyz(first), xyz(second)));
        Round round = round(helper, her, script);

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, round.hasSettled(), "the script has not finished");
            String msg = message(round, script);
            Checks.assertTrue(helper, receipt(round, script).get("success").getAsBoolean(), "the script failed: " + msg);
            Checks.assertTrue(helper, level.getBlockState(first).isAir() && level.getBlockState(second).isAir(),
                    "not both cells were cleared: " + msg);
            Checks.assertTrue(helper, msg.startsWith("ok · 2 calls"), msg);
            Checks.assertTrue(helper, msg.matches("(?s).*\nstderr:\nline 1 numen\\.build\\.place: .*\nline 2 numen\\.build\\.place: .*"),
                    "a line did not wait for its task to finish and report its account: " + msg);
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
                numen.move.walk({x = %d, z = %d})
                numen.build.place({{name = "air", pos = %s}})
                """.formatted(far.getX(), far.getZ(), xyz(kept)));
        Round round = round(helper, her, script);

        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, CompanionTickDispatcher.currentTaskFor(her.getUUID()) != null,
                        "the walk has not started"))
                .thenExecute(() -> {
                    Checks.assertTrue(helper, round.result(script) == null, "the script ended before the walk did");
                    round.ownerStops();
                })
                .thenWaitUntil(() -> Checks.assertTrue(helper, CompanionTickDispatcher.currentTaskFor(her.getUUID()) == null,
                        "her body is still busy after the stop"))
                .thenWaitUntil(() -> Checks.assertTrue(helper, round.programReceipt() != null,
                        "the program has not handed in its receipt"))
                .thenExecute(() -> {
                    // 切断时客户端放弃了这一批(模型从历史里的切断点知道);服务端上的程序当场停下,它的回执写明停在哪
                    Checks.assertTrue(helper, round.result(script) == null, "the cut-off turn was handed a result");
                    String msg = JsonParser.parseString(round.programReceipt()).getAsJsonObject().get("message")
                            .getAsString();
                    Checks.assertTrue(helper, msg.matches("(?s)The script stopped at line 1 \\(numen\\.move\\.walk\\) after 1 call: "
                            + "this turn was cut off; t\\d+ was stopped too\\. Nothing after that ran\\..*"), msg);
                    Checks.assertTrue(helper, level.getBlockState(kept).is(Blocks.STONE), "the line after the stop ran");
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
                numen.move.walk({x = %d, z = %d})
                numen.move.walk({x = %d, z = %d})
                """.formatted(far.getX(), far.getZ(), far.getX() - 10, far.getZ()));
        Round round = round(helper, her, script);

        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, CompanionTickDispatcher.currentTaskFor(her.getUUID()) != null,
                        "the walk has not started"))
                .thenExecute(() -> round.ownerSays("wait, come back"))
                .thenWaitUntil(() -> Checks.assertTrue(helper, round.hasSettled(), "the script did not stop when the owner spoke"))
                .thenExecute(() -> {
                    String msg = message(round, script);
                    Checks.assertTrue(helper, msg.matches("(?s)The script stopped at line 1 \\(numen\\.move\\.walk\\) after 1 call: "
                            + "your owner spoke; t\\d+ keeps running\\..*"), msg);
                    TaskRecord<?> now = CompanionTickDispatcher.currentTaskFor(her.getUUID());
                    Checks.assertTrue(helper, now != null && now.getToolName().equals("numen.move.walk"),
                            "the walk is no longer running: " + now);
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    /**
     * 内置的 numen.work.mine:四颗铁矿埋在一块石头里,一段程序扫到它们,把那一团交给 {@code numen.work.mine} 挖空——走到够得着、挖、
     * 捡,直到交给它的一格不剩;铁都进了包,返回挖了几格。它里面没有一处接住错误往下走:哪一步失败,整段就停在那一步。
     */
    @GameTest(template = "floor20", timeoutTicks = 366, batch = "numen_scripts")
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
                "return numen.work.mine(numen.scan.blocks(\"minecraft:iron_ore\", {radius = 8})[1])");
        Round[] round = new Round[1];

        steps(helper)
                .thenExecute(() -> round[0] = round(helper, her, run))
                .thenWaitUntil(() -> {
                    Checks.assertTrue(helper, round[0].hasSettled(), "numen.work.mine has not finished");
                    String msg = message(round[0], run);
                    Checks.assertTrue(helper, msg.startsWith("ok · "), msg);
                    Checks.assertTrue(helper, round[0].data().get("returned").getAsInt()
                            >= ores.size(), "numen.work.mine did not return the cells it dug: " + msg);
                    for (BlockPos ore : ores) {
                        Checks.assertTrue(helper, !level.getBlockState(ore).is(Blocks.IRON_ORE),
                                "ore left at " + ore + ": " + msg);
                    }
                    Checks.assertTrue(helper, her.getInventory().countItem(Items.RAW_IRON) == ores.size(),
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
            // 放进这次 GameTest 专用的服务器目录里,和 jar 同步下来的内置模块在同一处
            com.dwinovo.numen.api.gametest.TestLibraries.builtinModule(COPIED, """
                    -- Test fixture: a built-in module to change, delete and reset.
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
        Checks.assertTrue(helper, shown.receipt() != null && shown.receipt().contains("function M.version()"),
                "the factory module does not read as itself: " + shown.receipt());
        ToolRun changed = lua(her, """
                local code = numen.module.show("gt.copied").code
                local mine = string.gsub(code, "\\nreturn M%s*$", "\\nfunction M.gt_marker() return 7 end\\nreturn M\\n")
                numen.module.save(mine, {name = "gt.copied"})
                return gt.copied.gt_marker() + gt.copied.version()
                """);
        Checks.assertTrue(helper, changed.ranToTheEnd() && changed.receipt().contains("returned: 8"),
                "her change was not used: " + changed.receipt());
        JsonObject listed = module(lua(her, "numen.module.list()"), COPIED);
        Checks.assertTrue(helper, listed != null && listed.get("summary").getAsString().startsWith("Test fixture")
                && listed.get("whose").getAsString().equals("built in, changed"), "the list: " + listed);
        Checks.assertTrue(helper, lua(her, "return numen.module.show(\"" + COPIED + "\", {factory = true}).code").receipt()
                        .contains("function M.version()") && !lua(her, "return numen.module.show(\"" + COPIED
                        + "\", {factory = true}).code").receipt().contains("gt_marker"), "the factory text is gone");
        ToolRun deleted = lua(her, "numen.module.delete(\"" + COPIED + "\")");
        Checks.assertTrue(helper, deleted.succeeded(), deleted.reply());
        ToolRun unused = lua(her, "return gt.copied.version()");
        Checks.assertTrue(helper, !unused.ranToTheEnd() && unused.receipt().contains("no_function"),
                "a deleted module is still used: " + unused.receipt());
        JsonObject gone = module(lua(her, "numen.module.list()"), COPIED);
        Checks.assertTrue(helper, gone != null && gone.get("whose").getAsString().equals("built in, deleted; "
                + "numen.module.reset(\"" + COPIED + "\") brings it back"), "the list does not say it was deleted: " + gone);
        ToolRun reset = lua(her, "numen.module.reset(\"" + COPIED + "\")");
        Checks.assertTrue(helper, reset.succeeded(), reset.reply());
        ToolRun back = lua(her, "return gt.copied.version(), type(gt.copied.gt_marker)");
        Checks.assertTrue(helper, back.ranToTheEnd() && back.receipt().contains("returned: 1"),
                "reset did not bring the factory text back: " + back.receipt());
        Checks.assertTrue(helper, !lua(her, "return gt.copied.gt_marker()").ranToTheEnd(), "her function outlived the reset");
        CompanionFactory.despawn(helper.getLevel().getServer(), her);
        helper.succeed();
    }

    /**
     * 她存一个自己的模块(在 my 下)、读它、在两段程序里按名字 {@code my.gt_clear} 用它,战绩累计;读不通的、改第 ① 层函数的、不在
     * my 下又不是内置名字的不收、说为什么;主人拿编辑器改了文件,下一段程序就用新的;目录里一个坏模块只让用到它的程序出错;她自己的
     * 删得掉。模块目录是这次 GameTest 专用的,不是主人的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void she_saves_uses_and_deletes_a_module_of_her_own(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_saver", new BlockPos(2, 2, 2), true);
        BlockPos cell = helper.absolutePos(new BlockPos(4, 2, 2));
        level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
        java.nio.file.Path dir = com.dwinovo.numen.api.script.Modules.of(her.getUUID()).dir();
        Checks.assertTrue(helper, dir.toString().contains("numen-gametest-lib-"),
                "GameTest reads modules from " + dir + ", not from its own empty directory");

        ToolRun flat = lua(her, "numen.module.save(\"-- Flat.\\nreturn {}\", {name = \"gt_flat\"})");
        Checks.assertTrue(helper, !flat.succeeded() && flat.reply().contains("my.gt_flat"),
                "a module of her own was kept outside my: " + flat.reply());
        ToolRun broken = lua(her, "numen.module.save(\"-- Never compiles.\\nlocal x = = 1\", {name = \"my.gt_broken\"})");
        Checks.assertTrue(helper, !broken.succeeded() && broken.reply().contains("my.gt_broken:2:"),
                "a module that does not compile was kept: " + broken.reply());
        ToolRun redefines = lua(her, "numen.module.save(\"-- Takes numen.build.place.\\nlocal M = {}\\nfunction numen.build.place() end\\n"
                + "return M\", {name = \"my.gt_thief\"})");
        Checks.assertTrue(helper, !redefines.succeeded() && redefines.reply().contains("numen.build.place is an API function"),
                "a module that redefines an API function was kept: " + redefines.reply());

        ToolRun saved = lua(her, "numen.module.save(\"-- Clearing cells.\\nlocal M = {}\\n---Clear one cell.\\n"
                + "function M.cell(p)\\n  numen.build.place({{name = 'air', pos = p}})\\nend\\nreturn M\", {name = \"my.gt_clear\"})");
        Checks.assertTrue(helper, saved.succeeded() && "my.gt_clear".equals(saved.field("name")) && "new".equals(saved.field("how")),
                saved.reply());
        ToolRun shown = lua(her, "numen.module.show(\"my.gt_clear\")");
        Checks.assertTrue(helper, shown.succeeded() && "yours".equals(shown.field("whose"))
                && String.valueOf(shown.field("code")).contains("function M.cell(p)")
                && Long.valueOf(0).equals(shown.field("runs")), shown.reply());

        String at = com.dwinovo.numen.api.sdk.Positions.literal(cell);
        LlmToolCall run = programCall("my.gt_clear.cell(" + at + ")");
        Round first = round(helper, her, run);
        Round[] second = new Round[1];

        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, first.hasSettled(), "the first program has not finished"))
                .thenExecute(() -> {
                    String msg = message(first, run);
                    Checks.assertTrue(helper, msg.startsWith("ok · ") && msg.contains("\nline 1 numen.build.place: "), msg);
                    Checks.assertTrue(helper, level.getBlockState(cell).isAir(), "the module did not clear the cell");
                    level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
                })
                .thenExecute(() -> second[0] = round(helper, her, programCall("my.gt_clear.cell(" + at + ")")))
                .thenWaitUntil(() -> Checks.assertTrue(helper, second[0].hasSettled() && level.getBlockState(cell).isAir(),
                        "the second program did not clear the cell and end"))
                .thenWaitUntil(() -> {
                    ToolRun list = lua(her, "numen.module.list()");
                    JsonObject mine = module(list, "my.gt_clear");
                    Checks.assertTrue(helper, mine != null && mine.get("summary").getAsString().equals("Clearing cells.")
                                    && mine.get("whose").getAsString().equals("yours") && mine.get("runs").getAsInt() == 2
                                    && mine.get("finished").getAsInt() == 2, "the record does not add up: " + mine);
                    JsonObject work = module(list, "numen.work");
                    Checks.assertTrue(helper, work != null && work.get("whose").getAsString().equals("built in"), list.reply());
                })
                .thenExecute(() -> {
                    // 主人拿编辑器改了文件:/reload 之后,下一段程序就用新的
                    try {
                        java.nio.file.Files.writeString(dir.resolve("my").resolve("gt_clear.lua"),
                                "-- Clearing cells.\nlocal M = {}\n---Say which version this is.\n"
                                        + "function M.version() return 2 end\nreturn M\n");
                        java.nio.file.Files.writeString(dir.resolve("my").resolve("gt_rotten.lua"), "local M = {\n");
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    Modules.of(her.getUUID()).reload();
                    ToolRun edited = lua(her, "return my.gt_clear.version()");
                    Checks.assertTrue(helper, edited.ranToTheEnd() && edited.receipt().contains("returned: 2"),
                            "the edited file was not used: " + edited.receipt());
                    ToolRun untouched = lua(her, "return my.gt_clear.version() + 1");
                    Checks.assertTrue(helper, untouched.ranToTheEnd(), "a broken module nobody uses broke a program: "
                            + untouched.receipt());
                    ToolRun rotten = lua(her, "return my.gt_rotten.x");
                    Checks.assertTrue(helper, !rotten.ranToTheEnd() && rotten.receipt().contains("module my.gt_rotten does not "
                            + "compile"), rotten.receipt());
                    Checks.assertTrue(helper, lua(her, "numen.module.delete(\"my.gt_rotten\")").succeeded(), "the rotten one stays");
                    ToolRun deleted = lua(her, "numen.module.delete(\"my.gt_clear\")");
                    Checks.assertTrue(helper, deleted.succeeded(), deleted.reply());
                    Checks.assertTrue(helper, !lua(her, "numen.module.show(\"my.gt_clear\")").succeeded(),
                            "the deleted module is still there");
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    /**
     * 形状里要游戏在场才答得出的两件:图案的标签({@code ##logs} 是标签里每个方块的默认状态,{@code ##*oak_logs} 是它们的每一种状态)读自这个世界
     * 加载好的数据包,{@code expand_vert} 把选区拉到这个维度的最低到最高一格。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void shape_reads_the_worlds_tags_and_height(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_shape_reader", new BlockPos(2, 2, 2), false);
        ToolRun run = lua(her, """
                local logs = numen.shape.pattern("##logs")[1].blocks
                local oak = false
                for _, b in ipairs(logs) do
                  oak = oak or b == "minecraft:oak_log[axis=y]"
                end
                local tall = numen.shape.cuboid({x = 0, y = 64, z = 0}, {x = 1, y = 65, z = 1}):expand_vert():cells()
                return {logs = #logs, oak = oak, oak_states = #numen.shape.pattern("##*oak_logs")[1].blocks,
                        count = #tall, lowest = tall[1].y, highest = tall[#tall].y}
                """);
        Checks.assertTrue(helper, run.ranToTheEnd(), "the shape program failed: " + run.receipt());
        var got = run.data().getAsJsonObject("returned");
        Checks.assertTrue(helper, got.get("logs").getAsInt() >= 20 && got.get("oak").getAsBoolean(),
                "##logs is every log of the tag, in its default state: " + got);
        Checks.assertTrue(helper, got.get("oak_states").getAsInt() == 12, "##*oak_logs is 4 blocks of 3 axes: " + got);
        int height = level.getMaxY() + 1 - level.getMinY();
        Checks.assertTrue(helper, got.get("count").getAsInt() == 4 * height && got.get("lowest").getAsInt() == level.getMinY()
                        && got.get("highest").getAsInt() == level.getMaxY(),
                "expand_vert reaches from the lowest to the highest block: " + got);
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
    }

    /** 一只实体原样就是一处地方:{@code numen.scan.entities} 列出的那头牛交给 {@code numen.move.walk},她走到牛跟前。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void an_entity_a_scan_found_is_a_place_to_walk_to(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_herder", new BlockPos(2, 2, 2), false);
        var cow = net.minecraft.world.entity.EntityType.COW.create(level, EntitySpawnReason.STRUCTURE);
        Checks.assertTrue(helper, cow != null, "the cow did not spawn");
        BlockPos at = helper.absolutePos(new BlockPos(12, 2, 12));
        cow.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
        cow.setNoAi(true);
        level.addFreshEntity(cow);
        LlmToolCall script = programCall("""
                local cow = numen.scan.entities("passive", {radius = 20})[1]
                numen.move.walk(cow)
                return cow.id
                """);
        Round round = round(helper, her, script);

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, round.hasSettled(), "the script has not finished");
            JsonObject receipt = receipt(round, script);
            Checks.assertTrue(helper, receipt.get("success").getAsBoolean()
                            && round.data().get("returned").getAsString().equals(com.dwinovo.numen.api.sdk.EntityLabel.of(cow)),
                    "the walk to the cow failed: " + receipt);
            Checks.assertTrue(helper, her.distanceTo(cow) <= 4, "she did not get to the cow: " + her.distanceTo(cow));
            cow.discard();
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 夹具组 {@code gt.gt_client}:只有主人的客户端答得了的函数(客户端函数)。 */
    public static final class ClientFns {

        private ClientFns() {}

        public record Text(@Doc("Any text.") String text) {}

        @Fn("Say hello from the owner's client.")
        @Example("gt.gt_client.hello()")
        public static String hello(ClientCall call) {
            return "hello from the client";
        }

        @Fn("Return how long the text it was given is.")
        @Example("gt.gt_client.length(\"abc\")")
        public static int length(ClientCall call, Text args) {
            return args.text().length();
        }
    }

    static {
        if (GameTestKit.numenTestsEnabled()) {
            NumenPlugins.register("gt", numen -> numen.api("gt_client",
                    "Test fixture: functions only the owner's client answers.", ClientFns.class));
        }
    }

    /** 夹具组 {@code gtcm.pad}:目标模组只在客户端的联动的函数,主人的客户端报了它才有。 */
    public static final class PadFns {

        private PadFns() {}

        @Fn("Read the pad of the client-only mod.")
        @Example("gtcm.pad.read()")
        public static String read(ClientCall call) {
            return "the pad";
        }
    }

    static {
        if (GameTestKit.numenTestsEnabled()) {
            NumenPlugins.registerForClientMod("gtcm", "gametest_pad_mod", numen -> numen.api("pad",
                    "Test fixture: a client-only mod's functions.", PadFns.class));
        }
    }

    /**
     * 程序里的客户端函数向主人的连接发 {@code ClientCallPayload}:主人的连接是 {@code OwnerLine}(没有客户端),这里扮他的客户端答
     * ({@code ClientEndpoint}),答复经上行的处理函数回到服务端。服务端从包进到包出的路——认主人、量大小、反向请求、答复——
     * 都真走一遍,只是没有网线。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void a_client_function_is_asked_over_the_owners_connection_and_answered_by_his_client(
            GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_wire", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, her, "gametest_wire_owner");
        com.dwinovo.numen.api.brain.Replies.hello(owner, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        AtomicReference<Program.Outcome> result = new AtomicReference<>();
        ServerPrograms.run(her, her.getUUID(), owner.getUUID(), new ServerPrograms.Request("wire-" + UUID.randomUUID(),
                """
                local me = numen.status.self()
                local words = gt.gt_client.hello()
                return me.name .. ":" .. words
                """, Modules.of(her.getUUID()), true), NetworkTransport.INSTANCE, CallObserver.NONE, result::set);

        steps(helper)
                .thenWaitUntil(() -> {
                    for (CustomPacketPayload payload : received(owner)) {
                        if (payload instanceof ClientCallPayload call) {
                            ClientEndpoint.handle(call, answer -> ClientCallResultPayload.handle(answer, owner));
                        }
                    }
                    Checks.assertTrue(helper, result.get() != null, "the receipt has not come back");
                })
                .thenExecute(() -> {
                    String receipt = result.get().receipt();
                    JsonObject data = JsonParser.parseString(receipt).getAsJsonObject();
                    Checks.assertTrue(helper, data.get("success").getAsBoolean()
                                    && data.get("message").getAsString().endsWith("\nreturned: gametest_wire:hello from the client"),
                            "the program did not run with its client function answered over the wire: " + receipt);
                    Checks.assertTrue(helper, result.get().calls().size() == 2,
                            "the calls' outcomes did not come back: " + receipt);
                    leave(owner);
                    CompanionFactory.despawn(helper.getLevel().getServer(), her);
                })
                .thenSucceed();
    }

    /** 夹具:主人客户端上的一个配方来源,答一条机器配方和一条与原版同名的合成配方(补上原版没有的机器与类别名)。 */
    public static final class KnowledgeClient {

        private KnowledgeClient() {}

        public record Query(@Doc("The item.") net.minecraft.world.item.Item item) {}

        @Fn("Fixture: a recipe source on the owner's client.")
        public static com.dwinovo.numen.api.knowledge.RecipePage read(ClientCall call, Query args) {
            return com.dwinovo.numen.api.knowledge.RecipePage.of(List.of(
                    new com.dwinovo.numen.api.knowledge.Recipe(java.util.Optional.of("gt:press_sticks"), "gt:press",
                            java.util.Optional.of("Press"), "minecraft:stick", 4, List.of("250mB water"),
                            List.of("oak_log"), List.of("gt_press"), java.util.Optional.empty(),
                            java.util.Optional.empty(), java.util.Optional.empty(), List.of()),
                    new com.dwinovo.numen.api.knowledge.Recipe(java.util.Optional.of("minecraft:stick"),
                            "minecraft:crafting", java.util.Optional.of("Crafting"), "minecraft:stick", 4, List.of(),
                            List.of(), List.of("minecraft:crafting_table"), java.util.Optional.empty(),
                            java.util.Optional.empty(), java.util.Optional.empty(), List.of())));
        }
    }

    /**
     * 配方来源经插件的门登记:动词 {@code numen.inv.recipes} 逐个问来源,原版的与主人客户端上的合成一份、同一条配方只列一次并注明两个来源;
     * 目标模组主人没装的那个来源在结果里明写缺了、为什么、缺的是哪类信息,什么也不静默丢。撤回插件后来源不再被问。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void recipe_sources_are_merged_and_a_missing_one_is_named(GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_sourced", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, her, "gametest_sourced_owner");
        com.dwinovo.numen.api.brain.Replies.hello(owner, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        NumenPlugins.register("gtk", numen -> numen.knowledgeSource(com.dwinovo.numen.api.knowledge.KnowledgeKind.RECIPES,
                "which machine does it", List.of("chance"), KnowledgeClient.class));
        NumenPlugins.registerForClientMod("gtm", "gametest_machine_mod", numen -> numen.knowledgeSource(
                com.dwinovo.numen.api.knowledge.KnowledgeKind.RECIPES, "fluids", List.of("energy"),
                KnowledgeClient.class));
        AtomicReference<Program.Outcome> result = new AtomicReference<>();
        ServerPrograms.run(her, her.getUUID(), owner.getUUID(), new ServerPrograms.Request("sources-" + UUID.randomUUID(),
                """
                local r = numen.inv.recipes("minecraft:stick")
                local state = {}
                for _, s in ipairs(r.sources) do
                  state[#state + 1] = s.source .. "=" .. s.status .. (s.why and ("(" .. s.why .. ")") or "")
                end
                local merged, machine
                for _, x in ipairs(r.recipes) do
                  if x.id == "minecraft:stick" then merged = table.concat(x.sources, "+") .. ":" .. x.catalysts[1] end
                  if x.category == "gt:press" then machine = x.sources[1] .. ":" .. x.category_name end
                end
                return table.concat(state, ",") .. "|" .. tostring(merged) .. "|" .. tostring(machine)
                """, Modules.of(her.getUUID()), true), NetworkTransport.INSTANCE, CallObserver.NONE, result::set);

        steps(helper)
                .thenWaitUntil(() -> {
                    for (CustomPacketPayload payload : received(owner)) {
                        if (payload instanceof ClientCallPayload call) {
                            ClientEndpoint.handle(call, answer -> ClientCallResultPayload.handle(answer, owner));
                        }
                    }
                    Checks.assertTrue(helper, result.get() != null, "the receipt has not come back");
                })
                .thenExecute(() -> {
                    String said = JsonParser.parseString(result.get().receipt()).getAsJsonObject().get("message")
                            .getAsString();
                    Checks.assertTrue(helper, said.contains("returned: numen=answered,gtk=answered,gtm=missing(no_function: ")
                                    && said.contains("gametest_machine_mod")
                                    && said.endsWith("|numen+gtk:minecraft:crafting_table|gtk:Press"),
                            "the sources were not merged and reported: " + said);
                    NumenPlugins.roster().withdraw("gtk");
                    NumenPlugins.roster().withdraw("gtm");
                    leave(owner);
                    CompanionFactory.despawn(helper.getLevel().getServer(), her);
                })
                .thenSucceed();
    }

    /**
     * 目标模组只在客户端的联动:主人的客户端报到时没报它,她调它当场以 {@code no_function} 失败、说明缺哪个模组,什么也没发给主人的客户端;
     * 她的系统提示里也没有它。报了它之后,同一个调用走到主人的客户端,提示里也有了。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void a_client_only_mods_functions_exist_for_her_only_when_the_owners_client_reports_it(
            GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_padded", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, her, "gametest_pad_owner");
        // 她的主人以名册为准(和模块库、技能库一样):按真实流程把她记进名册
        com.dwinovo.numen.api.entity.CompanionRegistry.get(helper.getLevel().getServer()).put(her.getUUID(),
                new com.dwinovo.numen.api.entity.CompanionRegistry.Entry("gametest_padded", owner.getUUID(),
                        net.minecraft.world.level.Level.OVERWORLD, her.blockPosition()));
        com.dwinovo.numen.api.brain.Replies.hello(owner, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        received(owner);
        AtomicReference<Program.Outcome> refused = new AtomicReference<>();
        AtomicReference<Program.Outcome> answered = new AtomicReference<>();
        boolean[] sent = {false};

        steps(helper)
                .thenExecute(() -> {
                    Checks.assertFalse(helper, com.dwinovo.numen.api.sdk.ApiDocs.index(Modules.of(her.getUUID())).contains("gtcm.pad"),
                            "the index lists a client-only mod the owner's client did not report");
                    ServerPrograms.run(her, her.getUUID(), owner.getUUID(), new ServerPrograms.Request(
                            "padless-" + UUID.randomUUID(), "return pcall(gtcm.pad.read)", Modules.of(her.getUUID()), true),
                            NetworkTransport.INSTANCE, CallObserver.NONE, refused::set);
                })
                .thenWaitUntil(() -> Checks.assertTrue(helper, refused.get() != null, "the refusal has not come back"))
                .thenExecute(() -> {
                    String receipt = refused.get().receipt();
                    Checks.assertTrue(helper, receipt.contains("no_function") && receipt.contains("needs the gametest_pad_mod mod"),
                            "the missing mod was not named: " + receipt);
                    Checks.assertTrue(helper, received(owner).stream().noneMatch(p -> p instanceof ClientCallPayload),
                            "a call for a mod the client lacks was sent to it");
                    // 主人的客户端报了它(版本在先,能力在后,到齐才算报到)
                    com.dwinovo.numen.api.brain.Replies.hello(owner, com.dwinovo.numen.api.brain.Presence.PROTOCOL,
                            java.util.Set.of("gtcm"));
                    received(owner);
                    Checks.assertTrue(helper, com.dwinovo.numen.api.sdk.ApiDocs.index(Modules.of(her.getUUID())).contains("gtcm.pad"),
                            "the index does not list a client-only mod the owner's client reported");
                    ServerPrograms.run(her, her.getUUID(), owner.getUUID(), new ServerPrograms.Request(
                            "padded-" + UUID.randomUUID(), "return gtcm.pad.read()", Modules.of(her.getUUID()), true),
                            NetworkTransport.INSTANCE, CallObserver.NONE, answered::set);
                })
                .thenWaitUntil(() -> {
                    for (CustomPacketPayload payload : received(owner)) {
                        if (payload instanceof ClientCallPayload call) {
                            sent[0] = true;
                            ClientEndpoint.handle(call, answer -> ClientCallResultPayload.handle(answer, owner));
                        }
                    }
                    Checks.assertTrue(helper, answered.get() != null, "the receipt has not come back");
                })
                .thenExecute(() -> {
                    Checks.assertTrue(helper, sent[0] && answered.get().receipt().contains("returned: the pad"),
                            "the call did not reach the owner's client: " + answered.get().receipt());
                    leave(owner);
                    com.dwinovo.numen.api.entity.CompanionRegistry.get(helper.getLevel().getServer()).remove(her.getUUID());
                    CompanionFactory.despawn(helper.getLevel().getServer(), her);
                })
                .thenSucceed();
    }

    /**
     * 下行也分片:程序给客户端函数一段 1.5 MB 的文字,反向请求({@code ClientCallPayload})超过一个下行的包(1 MB),真实的
     * {@code NumenNetwork.sendToPlayer} 把它切成片经主人的连接({@code OwnerLine})发出;这里扮主人的客户端,收件箱拼回、
     * 交给客户端执行体,答复经上行回到服务端,程序跑到最后。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void a_request_over_one_downward_packet_reaches_the_owners_client_in_fragments(
            GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_big_request", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, her, "gametest_big_request_owner");
        com.dwinovo.numen.api.brain.Replies.hello(owner, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        String text = "z".repeat(Wire.TO_CLIENT.bytes() + Wire.TO_CLIENT.bytes() / 2);
        Fragments.Inbox inbox = new Fragments.Inbox(Wire.TO_CLIENT);
        AtomicReference<Program.Outcome> result = new AtomicReference<>();
        int[] fragments = new int[1];
        ServerPrograms.run(her, her.getUUID(), owner.getUUID(), new ServerPrograms.Request("big-" + UUID.randomUUID(),
                "return gt.gt_client.length([==[" + text + "]==])", Modules.of(her.getUUID()), true),
                NetworkTransport.INSTANCE, CallObserver.NONE, result::set);

        steps(helper)
                .thenWaitUntil(() -> {
                    for (CustomPacketPayload payload : received(owner)) {
                        if (payload instanceof FragmentPayload piece) {
                            fragments[0]++;
                            ByteBuf wire = Unpooled.buffer();
                            FragmentPayload.TO_CLIENT_CODEC.encode(wire, piece);
                            payload = NumenNetwork.assembled(inbox, FragmentPayload.TO_CLIENT_CODEC.decode(wire));
                        }
                        if (payload instanceof ClientCallPayload call) {
                            ClientEndpoint.handle(call, answer -> ClientCallResultPayload.handle(
                                    Fragments.crossed(Wire.TO_SERVER, ClientCallResultPayload.STREAM_CODEC, answer),
                                    owner));
                        }
                    }
                    Checks.assertTrue(helper, result.get() != null, "the receipt has not come back");
                })
                .thenExecute(() -> {
                    String receipt = result.get().receipt();
                    Checks.assertTrue(helper, fragments[0] > 1, "the request did not come down as fragments: " + fragments[0]);
                    Checks.assertTrue(helper, receipt.contains("returned: " + text.length()),
                            "the program did not run to the end with the whole text: " + receipt);
                    leave(owner);
                    CompanionFactory.despawn(helper.getLevel().getServer(), her);
                })
                .thenSucceed();
    }

    private static void sample(String what, ToolRun run) {
        Constants.LOG.info("[numen-sample] {} ({} characters of message) -> {}", what,
                JsonParser.parseString(run.receipt()).getAsJsonObject().get("message").getAsString().length(),
                run.receipt());
    }
}
