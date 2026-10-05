package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.Fragments;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.ClientCallPayload;
import com.dwinovo.numen.network.payload.ClientCallResultPayload;
import com.dwinovo.numen.network.payload.FragmentPayload;
import com.dwinovo.numen.network.payload.ProgramResultPayload;
import com.dwinovo.numen.network.payload.RunProgramPayload;
import com.dwinovo.numen.program.ClientEndpoint;
import com.dwinovo.numen.program.ModuleSync;
import com.dwinovo.numen.program.ProgramUplink;
import com.dwinovo.numen.program.RunResult;
import com.dwinovo.numen.script.Modules;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_scripts")
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
        EventOutbox outbox = EventOutbox.get(level.getServer());

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            String msg = message(round, script);
            helper.assertTrue(receipt(round, script).get("success").getAsBoolean(), "the script failed: " + msg);
            helper.assertTrue(level.getBlockState(first).isAir() && level.getBlockState(second).isAir(),
                    "not both cells were cleared: " + msg);
            helper.assertTrue(msg.startsWith("ok · 2 calls"), msg);
            helper.assertTrue(msg.matches("(?s).*\nstderr:\nline 1 numen\\.build\\.place: .*\nline 2 numen\\.build\\.place: .*"),
                    "a line did not wait for its task to finish and report its account: " + msg);
            outbox.forget(her.getUUID());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 第一行当场失败(走去用一格空气):库里的 {@code numen.move.to} 规划出一份走不通的计划,{@code numen.move.go} 照它走就抛 no_path,脚本接住、
     * 不往下走,第二行的那一格还在;
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
                local walked, why = pcall(numen.move.to, %s, {arrive = "use"})
                if not walked then error("could not get there: " .. why, 0) end
                numen.build.place({{name = "air", pos = %s}})
                """.formatted(xyz(air), xyz(kept)));
        Round round = round(helper, her, script);

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            String msg = message(round, script);
            helper.assertTrue(!receipt(round, script).get("success").getAsBoolean(), "the script succeeded: " + msg);
            // 库函数 numen.move.to 里 numen.move.go 抛出的错误值原样到了脚本:拼进字符串是"函数: 种类 — 原因";规划本身不抛
            helper.assertTrue(msg.startsWith("The script stopped at line 2 after 2 calls: could not get there: "
                    + "numen.move.go: no_path — "), msg);
            helper.assertTrue(msg.contains("\nstderr:\nline 1 numen.move.go: no_path — ") && !msg.contains("line 1 numen.route.plan"),
                    "the failed call is in stderr, the plan that only returned data is not: " + msg);
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
                numen.move.to({x = %d, z = %d})
                numen.build.place({{name = "air", pos = %s}})
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
                .thenWaitUntil(() -> helper.assertTrue(round.programReceipt() != null,
                        "the program has not handed in its receipt"))
                .thenExecute(() -> {
                    // 切断时客户端放弃了这一批(模型从历史里的切断点知道);服务端上的程序当场停下,它的回执写明停在哪
                    helper.assertTrue(round.result(script) == null, "the cut-off turn was handed a result");
                    String msg = JsonParser.parseString(round.programReceipt()).getAsJsonObject().get("message")
                            .getAsString();
                    helper.assertTrue(msg.matches("(?s)The script stopped at line 1 \\(numen\\.move\\.go\\) after 2 calls: "
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
                numen.move.to({x = %d, z = %d})
                numen.move.to({x = %d, z = %d})
                """.formatted(far.getX(), far.getZ(), far.getX() - 10, far.getZ()));
        Round round = round(helper, her, script);
        EventOutbox outbox = EventOutbox.get(level.getServer());

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(CompanionTickDispatcher.currentTaskFor(her.getUUID()) != null,
                        "the walk has not started"))
                .thenExecute(() -> round.ownerSays("wait, come back"))
                .thenWaitUntil(() -> helper.assertTrue(round.hasSettled(), "the script did not stop when the owner spoke"))
                .thenExecute(() -> {
                    String msg = message(round, script);
                    helper.assertTrue(msg.matches("(?s)The script stopped at line 1 \\(numen\\.move\\.go\\) after 2 calls: "
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
                  numen.move.to(c, {arrive = "dig"})
                  numen.work.dig(c)
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
                    helper.assertTrue(msg.contains("stdout:\n" + near.getX() + "\t" + near.getZ() + "\n" + far.getX()
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
                "return numen.work.mine(numen.scan.blocks(\"minecraft:iron_ore\", {radius = 8})[1])");
        Round[] round = new Round[1];

        steps(helper)
                .thenExecute(() -> round[0] = round(helper, her, run))
                .thenWaitUntil(() -> {
                    helper.assertTrue(round[0].hasSettled(), "numen.work.mine has not finished");
                    String msg = message(round[0], run);
                    helper.assertTrue(msg.startsWith("ok · "), msg);
                    helper.assertTrue(round[0].data().get("returned").getAsInt()
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
        JsonObject listed = module(lua(her, "numen.module.list()"), COPIED);
        helper.assertTrue(listed != null && listed.get("summary").getAsString().startsWith("Test fixture")
                && listed.get("whose").getAsString().equals("built in, changed"), "the list: " + listed);
        helper.assertTrue(lua(her, "return numen.module.show(\"" + COPIED + "\", {factory = true}).code").receipt()
                        .contains("function M.version()") && !lua(her, "return numen.module.show(\"" + COPIED
                        + "\", {factory = true}).code").receipt().contains("gt_marker"), "the factory text is gone");
        ToolRun deleted = lua(her, "numen.module.delete(\"" + COPIED + "\")");
        helper.assertTrue(deleted.succeeded(), deleted.reply());
        ToolRun unused = lua(her, "return gt.copied.version()");
        helper.assertTrue(!unused.ranToTheEnd() && unused.receipt().contains("no_function"),
                "a deleted module is still used: " + unused.receipt());
        JsonObject gone = module(lua(her, "numen.module.list()"), COPIED);
        helper.assertTrue(gone != null && gone.get("whose").getAsString().equals("built in, deleted; "
                + "numen.module.reset(\"" + COPIED + "\") brings it back"), "the list does not say it was deleted: " + gone);
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
        ToolRun redefines = lua(her, "numen.module.save(\"-- Takes numen.build.place.\\nlocal M = {}\\nfunction numen.build.place() end\\n"
                + "return M\", {name = \"my.gt_thief\"})");
        helper.assertTrue(!redefines.succeeded() && redefines.reply().contains("numen.build.place is an API function"),
                "a module that redefines an API function was kept: " + redefines.reply());

        ToolRun saved = lua(her, "numen.module.save(\"-- Clearing cells.\\nlocal M = {}\\n---Clear one cell.\\n"
                + "function M.cell(p)\\n  numen.build.place({{name = 'air', pos = p}})\\nend\\nreturn M\", {name = \"my.gt_clear\"})");
        helper.assertTrue(saved.succeeded() && "my.gt_clear".equals(saved.field("name")) && "new".equals(saved.field("how")),
                saved.reply());
        ToolRun shown = lua(her, "numen.module.show(\"my.gt_clear\")");
        helper.assertTrue(shown.succeeded() && "yours".equals(shown.field("whose"))
                && String.valueOf(shown.field("code")).contains("function M.cell(p)")
                && Long.valueOf(0).equals(shown.field("runs")), shown.reply());

        String at = com.dwinovo.numen.sdk.Positions.literal(cell);
        LlmToolCall run = programCall("my.gt_clear.cell(" + at + ")");
        Round first = round(helper, her, run);
        Round[] second = new Round[1];

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(first.hasSettled(), "the first program has not finished"))
                .thenExecute(() -> {
                    String msg = message(first, run);
                    helper.assertTrue(msg.startsWith("ok · ") && msg.contains("\nline 1 numen.build.place: "), msg);
                    helper.assertTrue(level.getBlockState(cell).isAir(), "the module did not clear the cell");
                    level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
                    // 第一次的收尾事件已经读过;下一轮从空出箱读起,和主人客户端上取走即清一样
                    outbox.forget(her.getUUID());
                })
                .thenExecute(() -> second[0] = round(helper, her, programCall("my.gt_clear.cell(" + at + ")")))
                .thenWaitUntil(() -> helper.assertTrue(second[0].hasSettled() && level.getBlockState(cell).isAir(),
                        "the second program did not clear the cell and end"))
                .thenWaitUntil(() -> {
                    ToolRun list = lua(her, "numen.module.list()");
                    JsonObject mine = module(list, "my.gt_clear");
                    helper.assertTrue(mine != null && mine.get("summary").getAsString().equals("Clearing cells.")
                                    && mine.get("whose").getAsString().equals("yours") && mine.get("runs").getAsInt() == 2
                                    && mine.get("finished").getAsInt() == 2, "the record does not add up: " + mine);
                    JsonObject work = module(list, "numen.work");
                    helper.assertTrue(work != null && work.get("whose").getAsString().equals("built in"), list.reply());
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
            helper.assertTrue(round.data().get("returned").toString().equals("[1,1]"),
                    "the two digs did not hand back one cell each as data: " + receipt);
            helper.assertTrue(level.getBlockState(first).isAir() && level.getBlockState(second).isAir(),
                    "a block handed on as it was is still standing: " + receipt);
            EventOutbox.get(level.getServer()).forget(her.getUUID());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 一只实体原样就是一处地方:{@code numen.scan.entities} 列出的那头牛交给 {@code numen.move.to},她走到牛跟前。 */
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
                numen.move.to(cow, {arrive = "near"})
                return cow.id
                """);
        Round round = round(helper, her, script);

        succeedWhen(helper, () -> {
            helper.assertTrue(round.hasSettled(), "the script has not finished");
            JsonObject receipt = receipt(round, script);
            helper.assertTrue(receipt.get("success").getAsBoolean()
                            && round.data().get("returned").getAsInt() == cow.getId(),
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
            JsonObject got = round.data().getAsJsonObject("returned");
            var farErr = got.getAsJsonArray("far");
            helper.assertTrue("out_of_reach".equals(farErr.get(0).getAsString())
                            && farErr.get(1).getAsString().equals("numen.move.to(" + xyz(far) + ", {arrive = \"dig\"})\n"
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

    /**
     * 整段程序经线上的包送进来:主人的连接是 {@code OwnerLine}(没有客户端),服务端认身体、跑程序;程序里的客户端函数向主人发
     * {@code ClientCallPayload},这里扮他的客户端答({@code ClientEndpoint}),整张回执作为 {@code ProgramResultPayload} 回来。
     * 服务端从包进到包出的路——认主人、量大小、反向请求、答复——都真走一遍,只是没有网线。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_scripts")
    public static void a_program_sent_as_a_packet_runs_and_the_clients_functions_are_asked_over_the_wire(
            GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_wire", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, her, "gametest_wire_owner");
        ModuleSync sync = new ModuleSync();
        ClientEndpoint endpoint = new ClientEndpoint(sync,
                payload -> ClientCallResultPayload.handle((ClientCallResultPayload) payload, owner), Modules::of);
        AtomicReference<RunResult> result = new AtomicReference<>();
        ProgramUplink uplink = new ProgramUplink(sync, payload -> RunProgramPayload.handle((RunProgramPayload) payload,
                owner), Modules::of);
        uplink.run(her.getUUID(), "wire-" + UUID.randomUUID(), """
                local me = numen.status.self()
                local words = numen.api.help("numen.status")
                return me.name .. ":" .. (#words > 0 and "help" or "none")
                """, result::set);

        steps(helper)
                .thenWaitUntil(() -> {
                    for (CustomPacketPayload payload : received(owner)) {
                        if (payload instanceof ClientCallPayload call) {
                            endpoint.handle(call);
                        } else if (payload instanceof ProgramResultPayload answer) {
                            uplink.deliver(answer.programId(), RunResult.fromJson(answer.resultJson()));
                        }
                    }
                    helper.assertTrue(result.get() != null, "the receipt has not come back");
                })
                .thenExecute(() -> {
                    String receipt = ((RunResult.Ended) result.get()).outcome().receipt();
                    JsonObject data = JsonParser.parseString(receipt).getAsJsonObject();
                    helper.assertTrue(data.get("success").getAsBoolean()
                                    && data.get("message").getAsString().endsWith("\nreturned: gametest_wire:help"),
                            "the program did not run over the wire: " + receipt);
                    helper.assertTrue(((RunResult.Ended) result.get()).outcome().calls().size() == 2,
                            "the calls' outcomes did not come back: " + receipt);
                    leave(owner);
                    CompanionFactory.despawn(helper.getLevel().getServer(), her);
                })
                .thenSucceed();
    }

    /** 几百 KB 的模块正文:合法的 Lua,第一行注释说它做什么,后面是填充,最后返回一张表。 */
    private static String bigModule(int lines) {
        return "-- A big one.\nlocal M = {}\n---Give the answer.\nfunction M.answer() return 42 end\n"
                + "-- filler filler filler filler filler filler filler filler filler\n".repeat(lines) + "return M\n";
    }

    /**
     * 带着几百 KB 模块的程序上送:程序加模块正文远超一个上行的包(32767 字节),切成片、过线上的编解码、服务端的收件箱拼回,
     * 交给服务端的入口,程序用到这个模块并跑到最后。走的是回环客户端,和产品里同一批部件。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_scripts")
    public static void a_program_with_a_module_of_several_hundred_kilobytes_goes_up_in_fragments_and_runs(
            GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_big_module", new BlockPos(2, 2, 2), true);
        String module = bigModule(7_000);
        Modules.of(her.getUUID()).save("my.gt_big", module);
        helper.assertTrue(module.length() > 10 * Wire.TO_SERVER.bytes(), "the module is not bigger than ten packets");

        ToolRun run = lua(her, "return my.gt_big.answer()");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("returned: 42"),
                    "the program with the big module did not run: " + run.receipt());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }

    /**
     * 一个客户端函数交回的结果超过一个上行的包:{@code numen.module.show} 读出几百 KB 的模块正文,答复经分片送回服务端,
     * 程序拿到的是完整的正文。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_scripts")
    public static void a_client_function_result_over_one_packet_comes_back_to_the_program_in_fragments(
            GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_big_result", new BlockPos(2, 2, 2), true);
        String module = bigModule(7_000);
        Modules.of(her.getUUID()).save("my.gt_big", module);

        ToolRun run = lua(her, "return #numen.module.show(\"my.gt_big\").code");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("returned: " + module.length()),
                    "the program did not get the whole text back: " + run.receipt());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }

    /**
     * 下行也分片:程序存一个 1.5 MB 的模块,反向请求({@code ClientCallPayload})超过一个下行的包(1 MB),真实的
     * {@code NumenNetwork.sendToPlayer} 把它切成片经主人的连接({@code OwnerLine})发出;这里扮主人的客户端,收件箱拼回、
     * 交给客户端执行体,答复(带着新模块正文,同样超过一个上行的包)经分片上送。模块存进了她的目录,程序跑到最后。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_scripts")
    public static void a_request_over_one_downward_packet_reaches_the_owners_client_in_fragments(
            GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_big_request", new BlockPos(2, 2, 2), false);
        ServerPlayer owner = presentOwner(helper, her, "gametest_big_request_owner");
        String module = bigModule(24_000);
        helper.assertTrue(module.length() > Wire.TO_CLIENT.bytes(), "the module is not bigger than a downward packet");
        ModuleSync sync = new ModuleSync();
        ClientEndpoint endpoint = new ClientEndpoint(sync, payload -> ClientCallResultPayload.handle(
                Fragments.crossed(Wire.TO_SERVER, ClientCallResultPayload.STREAM_CODEC, (ClientCallResultPayload) payload),
                owner), Modules::of);
        ProgramUplink uplink = new ProgramUplink(sync, payload -> RunProgramPayload.handle(
                Fragments.crossed(Wire.TO_SERVER, RunProgramPayload.STREAM_CODEC, (RunProgramPayload) payload), owner),
                Modules::of);
        Fragments.Inbox inbox = new Fragments.Inbox(Wire.TO_CLIENT);
        AtomicReference<RunResult> result = new AtomicReference<>();
        int[] fragments = new int[1];
        uplink.run(her.getUUID(), "big-" + UUID.randomUUID(), "numen.module.save([==[\n" + module
                + "]==], {name = \"my.gt_huge\"})\nreturn \"saved\"", result::set);

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
                            endpoint.handle(call);
                        } else if (payload instanceof ProgramResultPayload answer) {
                            uplink.deliver(answer.programId(), RunResult.fromJson(answer.resultJson()));
                        }
                    }
                    helper.assertTrue(result.get() != null, "the receipt has not come back");
                })
                .thenExecute(() -> {
                    String receipt = ((RunResult.Ended) result.get()).outcome().receipt();
                    helper.assertTrue(fragments[0] > 1, "the request did not come down as fragments: " + fragments[0]);
                    helper.assertTrue(receipt.contains("returned: saved"), "the program did not run to the end: " + receipt);
                    helper.assertTrue(module.equals(Modules.of(her.getUUID()).code("my.gt_huge")),
                            "the module was not saved whole");
                    leave(owner);
                    CompanionFactory.despawn(helper.getLevel().getServer(), her);
                })
                .thenSucceed();
    }

    /**
     * 主人的客户端卡住、不再答复:程序里的客户端函数等到时限({@code ProgramLimits.CLIENT_ANSWER_TICKS} 刻)就以 {@code timeout}
     * 失败交回,{@code pcall} 接得住、程序往下走(服务端函数照常),回执写明哪个函数、等了多久。
     */
    @GameTest(template = "floor16", timeoutTicks = com.dwinovo.numen.program.ProgramLimits.CLIENT_ANSWER_TICKS + 200,
            batch = "numen_scripts")
    public static void a_client_function_the_client_never_answers_times_out_and_the_program_goes_on(
            GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_mute_client", new BlockPos(2, 2, 2), false);
        client(her).silence();
        ToolRun run = lua(her, """
                local ok, err = pcall(numen.module.list)
                local me = numen.status.self()
                return tostring(ok) .. "|" .. err.kind .. "|" .. me.name
                """);
        helper.assertTrue(run.receipt() == null, "the program ended without waiting for the silent client: "
                + run.receipt());

        succeedWhen(helper, () -> {
            helper.assertTrue(run.receipt() != null, "the program is still waiting for the client's answer");
            helper.assertTrue(run.ranToTheEnd() && run.receipt().contains("returned: false|timeout|gametest_mute_client"),
                    "the call did not fail as a timeout with the program going on: " + run.receipt());
            helper.assertTrue(run.receipt().contains("numen.module.list: timeout — your owner's client did not answer "
                            + "numen.module.list within " + com.dwinovo.numen.program.ProgramLimits.CLIENT_ANSWER_TICKS / 20
                            + " seconds"),
                    "the receipt does not say which function the client did not answer: " + run.receipt());
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }

    /**
     * 停止键切断一段已经挖了几格的程序:这一批作废,模型当场读不到它的回执;服务端上的程序当场停下、照常交出回执,晚到的回执作为一条
     * {@code program_stopped} 事件进她的收件箱(客户端的工具口做的),正文就是服务端写的那份回执——写明切断了、已经挖了什么、后面的没挖。
     */
    @GameTest(template = "floor16", timeoutTicks = 300, batch = "numen_scripts")
    public static void a_program_cut_off_by_the_stop_button_leaves_its_receipt_in_her_inbox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_lua_cut", new BlockPos(2, 2, 2), true);
        List<BlockPos> cells = new java.util.ArrayList<>();
        for (int x = 3; x <= 8; x++) {
            BlockPos cell = helper.absolutePos(new BlockPos(x, 2, 2));
            level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
            cells.add(cell);
        }
        StringBuilder code = new StringBuilder();
        cells.forEach(cell -> code.append("numen.work.dig(").append(xyz(cell)).append(")\n"));
        List<String> afterwards = new java.util.ArrayList<>();
        com.dwinovo.numen.agent.tool.CompanionToolPort port = new com.dwinovo.numen.agent.tool.CompanionToolPort(
                her.getUUID(), () -> () -> her.getUUID(), client(her).uplink(), (program, receipt) ->
                        afterwards.add(com.dwinovo.numen.program.RunResult.messageOf(receipt)));
        List<String> handedToTheModel = new java.util.ArrayList<>();
        port.run(List.of(programCall(code.toString())), new com.dwinovo.numen.agent.loop.ToolPort.Sink() {
            @Override
            public void started(LlmToolCall call) {
            }

            @Override
            public void finished(LlmToolCall call, String resultJson) {
                handedToTheModel.add(resultJson);
            }

            @Override
            public void settled() {
            }
        });

        steps(helper)
                .thenWaitUntil(() -> helper.assertTrue(level.getBlockState(cells.get(1)).isAir(),
                        "she has not dug the first two cells"))
                .thenExecute(() -> port.cancel(true))
                .thenWaitUntil(() -> helper.assertTrue(!afterwards.isEmpty(), "the cut-off program's receipt has not come"))
                .thenExecute(() -> {
                    String receipt = afterwards.get(0);
                    helper.assertTrue(handedToTheModel.isEmpty(), "the void batch was handed a result: " + handedToTheModel);
                    helper.assertTrue(receipt.contains("this turn was cut off"), receipt);
                    helper.assertTrue(receipt.matches("(?s).*\nstderr:\nline \\d+ numen\\.work\\.dig: .*"),
                            "the receipt does not say what was dug: " + receipt);
                    helper.assertTrue(level.getBlockState(cells.get(cells.size() - 1)).is(Blocks.STONE),
                            "the cut-off program kept digging");
                    var entry = com.dwinovo.numen.event.NumenEvents.programStopped(0L, "p", receipt, 1L);
                    helper.assertTrue(entry.type().equals(com.dwinovo.numen.agent.inbox.EventTypes.PROGRAM_STOPPED)
                            && entry.text().contains("this turn was cut off") && !entry.urgent(),
                            "the receipt does not become her event: " + entry);
                    CompanionFactory.despawn(level.getServer(), her);
                })
                .thenSucceed();
    }

    private static void sample(String what, ToolRun run) {
        Constants.LOG.info("[numen-sample] {} ({} characters of message) -> {}", what,
                JsonParser.parseString(run.receipt()).getAsJsonObject().get("message").getAsString().length(),
                run.receipt());
    }

    /**
     * 循环里查一百多遍背包:每次调用只返回值,没什么可报告的,所以回执里没有逐次的流水——只有结局一行和她 print 的 stdout;
     * 每次调用的记录照样在数据里(调用数 120)。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_scripts")
    public static void a_loop_of_queries_leaves_no_line_per_call_in_the_receipt(GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_lua_loop", new BlockPos(2, 2, 2), false);
        her.getInventory().add(new ItemStack(Items.COAL, 5));
        ToolRun run = lua(her, """
                local total = 0
                for i = 1, 120 do total = total + numen.inv.count("minecraft:coal") end
                print("coal seen", total)
                """);
        String msg = JsonParser.parseString(run.receipt()).getAsJsonObject().get("message").getAsString();
        helper.assertTrue(run.ranToTheEnd(), msg);
        helper.assertTrue(msg.matches("(?s)ok · 120 calls · \\d+ s\nstdout:\ncoal seen\t600"), msg);
        helper.assertTrue(run.data().get("calls").getAsInt() == 120, "the call count is not in the structured ending");
        sample("120 inventory queries", run);
        CompanionFactory.despawn(helper.getLevel().getServer(), her);
        helper.succeed();
    }

    /**
     * 一段挖矿的程序:挖的那一次往 stderr 写挖了什么、手里的镐用坏了;紧跟着只返回值的查询什么也不写;她 print 的在 stdout。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_scripts")
    public static void a_mining_program_says_what_it_dug_and_what_wore_out_on_stderr(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos stone = helper.absolutePos(new BlockPos(5, 2, 5));
        level.setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer her = spawnAt(helper, "gametest_lua_miner", new BlockPos(3, 2, 5), false);
        ItemStack pick = new ItemStack(Items.STONE_PICKAXE);
        pick.setDamageValue(pick.getMaxDamage() - 1);
        her.getInventory().add(pick);
        ToolRun run = lua(her, """
                numen.work.dig(%s)
                print("cobblestone", numen.inv.count("minecraft:cobblestone"))
                """.formatted(xyz(stone)));

        succeedWhen(helper, () -> {
            helper.assertTrue(run.receipt() != null, "the program has not finished");
            String msg = JsonParser.parseString(run.receipt()).getAsJsonObject().get("message").getAsString();
            helper.assertTrue(run.ranToTheEnd() && msg.startsWith("ok · 2 calls · "), msg);
            helper.assertTrue(level.getBlockState(stone).isAir(), "the stone was not dug: " + msg);
            helper.assertTrue(msg.contains("\nstderr:\nline 1 numen.work.dig: ")
                            && msg.contains("your minecraft:stone_pickaxe broke while it was in your main hand"),
                    "stderr does not say what was dug and that the pickaxe broke: " + msg);
            helper.assertTrue(!msg.contains("numen.inv.count") && msg.contains("\nstdout:\ncobblestone\t"),
                    "the query that only returned a value wrote to stderr, or stdout is missing: " + msg);
            sample("a mining program", run);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * 一个失败的调用即使程序用 pcall 接住了也写进 stderr(同 Unix:失败的程序照样往 stderr 写);没接住的,停在哪一行是结局,stderr 里还是那一条。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_scripts")
    public static void a_failed_call_is_on_stderr_whether_or_not_the_program_caught_it(GameTestHelper helper) {
        NumenPlayer her = spawnAt(helper, "gametest_lua_failing", new BlockPos(2, 2, 2), false);
        ToolRun caught = lua(her, """
                local ok, err = pcall(numen.fight.attack, 999999)
                print(ok, err.kind)
                """);
        String msg = JsonParser.parseString(caught.receipt()).getAsJsonObject().get("message").getAsString();
        helper.assertTrue(caught.ranToTheEnd() && msg.startsWith("ok · 1 call · "), msg);
        helper.assertTrue(msg.contains("\nstderr:\nline 1 numen.fight.attack: not_found — ")
                && msg.contains("\nstdout:\nfalse\tnot_found"), "the caught failure is not on stderr: " + msg);
        sample("a failure the program caught", caught);

        ToolRun uncaught = lua(her, "local n = numen.inv.count(\"minecraft:coal\")\nnumen.fight.attack(999999)\n");
        String stopped = JsonParser.parseString(uncaught.receipt()).getAsJsonObject().get("message").getAsString();
        helper.assertTrue(!uncaught.ranToTheEnd() && stopped.startsWith("The script stopped at line 2 after 2 calls: "
                + "numen.fight.attack: not_found — ") && stopped.contains("\nhint: ")
                && stopped.contains("\nstderr:\nline 2 numen.fight.attack: not_found — "), stopped);
        sample("a failure that stopped the program", uncaught);
        CompanionFactory.despawn(helper.getLevel().getServer(), her);
        helper.succeed();
    }

    /**
     * 程序 return 一个大值:模型读到的只有回执的文字(缩略过的那份,成败在第一行);程序的原值整团的每一格只在服务端进程里(结局对象),
     * 不在回执里、不上网线——下行的包里没有它。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_scripts")
    public static void a_big_returned_value_reaches_the_model_only_as_the_shortened_message(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 5; x < 11; x++) {
            for (int z = 5; z < 11; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 2, z)), Blocks.GOLD_BLOCK.defaultBlockState());
            }
        }
        NumenPlayer her = spawnAt(helper, "gametest_lua_returner", new BlockPos(2, 2, 2), false);
        ToolRun run = lua(her, "return numen.scan.blocks(\"minecraft:gold_block\", {radius = 12})");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.receipt() != null, "the scan has not finished");
            String receipt = run.receipt();
            helper.assertTrue(run.data().getAsJsonArray("returned").get(0).getAsJsonObject()
                            .getAsJsonArray("blocks").size() == 36, "the program lost the raw value: " + run.data());
            String heard = com.dwinovo.numen.agent.llm.ToolOutcome.modelText(receipt);
            helper.assertTrue(heard.startsWith("ok · 1 call · ") && heard.contains("…(36 items in all;")
                            && !receipt.contains("\"data\"") && !receipt.contains("\"returned\""),
                    "the receipt carries more than its shortened message: " + receipt);
            Constants.LOG.info("[numen-sample] a big returned value: the receipt on the wire is {} characters, the model "
                    + "reads {}", receipt.length(), heard.length());
            CompanionFactory.despawn(level.getServer(), her);
        });
    }
    /**
     * 她 print 一团扫描结果:一团里的方块多了,只显示首尾几个并写明一共多少、怎么看更多——不静默地截断;返回的数照实在 returned 里。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_scripts")
    public static void printing_a_big_scan_shortens_it_and_says_how_big_it_was(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x = 5; x < 11; x++) {
            for (int z = 5; z < 11; z++) {
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 2, z)), Blocks.GOLD_BLOCK.defaultBlockState());
            }
        }
        NumenPlayer her = spawnAt(helper, "gametest_lua_scanner", new BlockPos(2, 2, 2), false);
        ToolRun run = lua(her, """
                local clusters = numen.scan.blocks("minecraft:gold_block", {radius = 12})
                print(clusters)
                return clusters[1].count
                """);

        succeedWhen(helper, () -> {
            helper.assertTrue(run.receipt() != null, "the scan has not finished");
            String msg = JsonParser.parseString(run.receipt()).getAsJsonObject().get("message").getAsString();
            helper.assertTrue(run.ranToTheEnd() && msg.contains("\nreturned: 36"), msg);
            helper.assertTrue(msg.contains("…(36 items in all; index one with t[i], or filter in the program before you "
                    + "print)"), "the scan was printed whole or cut without saying so: " + msg);
            helper.assertTrue(msg.length() < 2_500, "the printed scan is " + msg.length() + " characters");
            sample("printing a scan of 36 blocks", run);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }
}
