package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.Checks;
import com.dwinovo.numen.task.build.BuildTaskRecord;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.task.TaskDispatch;
import com.dwinovo.numen.api.task.TaskRecord;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/** 能力画像:生存与创造各走各的道(移动、挖掘、建造、拿东西)。 */
public class ModeGameTests {

    // ==================== 能力画像用例(生存 / 创造 分道)====================

    /** 画像批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_mode")
    public static void prepareModeBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 生存缺料拒工:空背包 + 消耗记账 → 开工前盘料失败,回执逐项报缺。 */
    @GameTest(template = "floor20", timeoutTicks = 200, batch = "numen_mode")
    public static void survival_build_missing_materials(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_broke", new BlockPos(2, 2, 2), false);
        List<BuildTaskRecord.Target> targets = new ArrayList<>();
        for (BlockPos rel : boxCells(new BlockPos(8, 2, 8), 3, 1, 3, false)) {
            targets.add(new BuildTaskRecord.Target(Blocks.COBBLESTONE, Items.COBBLESTONE,
                    helper.absolutePos(rel), "cobblestone"));
        }
        var ctx = TaskDispatch.ctx("gametest-sbuild-broke", companion);
        // 盘料是受理之前的准备:料不齐,这次调用当场回错误,活不进槽、没有任务编号
        BuildTaskRecord record = buildJob(ctx.toolCallId(), ctx.deadline(3600L), targets, true);
        java.util.concurrent.atomic.AtomicReference<com.dwinovo.numen.api.task.TaskResult> refused =
                new java.util.concurrent.atomic.AtomicReference<>();
        boolean[] accepted = new boolean[1];
        TaskDispatch.setTask(companion, record, null, refused::set, () -> accepted[0] = true);
        succeedWhen(helper, () -> {
            com.dwinovo.numen.api.task.TaskResult result = refused.get();
            Checks.assertTrue(helper, result != null || accepted[0], "the build has not replied");
            Checks.assertTrue(helper, result != null && !result.success() && result.message().contains("not enough materials"),
                    "expected an itemized missing-materials refusal, got: " + result);
            Checks.assertTrue(helper, !accepted[0] && record.getResult() == null
                            && com.dwinovo.numen.api.task.CompanionTickDispatcher.currentTaskFor(companion.getUUID()) == null,
                    "a build without materials was accepted instead of refused at once: " + result);
            for (BuildTaskRecord.Target t : targets) {
                Checks.assertTrue(helper, !level.getBlockState(t.pos()).is(Blocks.COBBLESTONE),
                        "must not build anything without materials");
            }
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 空手创造爬井:1×1 竖井,四面是十几格厚的基岩(横着挖出去比往上垫贵得多),
     * 背包全空——出路是免耗材画像自动补脚手架泥土后原地垫柱。守两件事:
     * 规划器敢想放置路线(hasThrowaway 画像位)+ 执行层自动补料与垫柱动作,垫的是出厂清单上的第一种。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_mode")
    public static void creative_pillar_out_empty_handed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int y = 2; y <= 4; y++) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    level.setBlockAndUpdate(helper.absolutePos(new BlockPos(7 + dx, y, 7 + dz)),
                            Blocks.BEDROCK.defaultBlockState());
                }
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_climber", new BlockPos(7, 2, 7), true);
        // 这条量的是免耗材的垫柱,不是飞出去:创造模式的身体许飞,飞比垫柱便宜,所以关掉飞行的能力
        companion.getAbilities().mayfly = false;
        BlockPos target = helper.absolutePos(new BlockPos(14, 2, 14));
        TaskRecord<?> record = lua(companion, "numen.move.walk(" + xyz(target) + ")").task();
        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, companion.blockPosition().distSqr(target) <= 2 * 2,
                    "empty-handed creative companion has not pillared out");
            Checks.assertTrue(helper, com.dwinovo.numen.nav.ThrowawayBlocks.factory().contains(
                            level.getBlockState(helper.absolutePos(new BlockPos(7, 2, 7))).getBlock().asItem()),
                    "the pillar is not the throwaway blocks she conjured: "
                            + level.getBlockState(helper.absolutePos(new BlockPos(7, 2, 7))));
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 游戏模式切换之后,{@code numen.status.self} 报的模式与挖掘的做法是同一份:切到创造,她报 creative,空手挖黑曜石一下就碎、什么都
     * 不掉;切回生存,她报 survival,空手挖另一块黑曜石当场被拒(手里没有收得到它的工具),那一块原样。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_mode")
    public static void status_and_digging_follow_a_game_mode_switch(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos first = helper.absolutePos(new BlockPos(8, 2, 5));
        BlockPos second = helper.absolutePos(new BlockPos(8, 2, 7));
        level.setBlockAndUpdate(first, Blocks.OBSIDIAN.defaultBlockState());
        level.setBlockAndUpdate(second, Blocks.OBSIDIAN.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_mode_switch", new BlockPos(6, 2, 6), false);
        ToolRun[] runs = new ToolRun[2];

        succeedWhen(helper, () -> {
            if (runs[0] == null) {
                companion.setGameMode(GameType.CREATIVE);
                runs[0] = lua(companion, "local mode = numen.status.self().game_mode\n"
                        + "local r = numen.work.dig(" + xyz(first) + ")\n"
                        + "return {mode = mode, dug = r.dug, drops = #r.drops}");
            }
            Checks.assertTrue(helper, runs[0].done(), "the creative dig has not finished");
            com.google.gson.JsonObject creative = runs[0].data().getAsJsonObject("returned");
            Checks.assertTrue(helper, creative.get("mode").getAsString().equals("creative")
                            && creative.get("dug").getAsInt() == 1 && creative.get("drops").getAsInt() == 0
                            && level.getBlockState(first).isAir(),
                    "in creative the status and the dig disagree: " + runs[0].receipt());
            Checks.assertTrue(helper, runs[0].receipt().contains("Nothing dropped."),
                    "the creative dig does not say nothing dropped: " + runs[0].receipt());
            if (runs[1] == null) {
                companion.setGameMode(GameType.SURVIVAL);
                runs[1] = lua(companion, "local mode = numen.status.self().game_mode\n"
                        + "local ok, err = pcall(numen.work.dig, " + xyz(second) + ")\n"
                        + "return {mode = mode, dug = ok, why = ok and \"\" or err.message}");
            }
            Checks.assertTrue(helper, runs[1].done(), "the survival dig has not finished");
            com.google.gson.JsonObject survival = runs[1].data().getAsJsonObject("returned");
            Checks.assertTrue(helper, survival.get("mode").getAsString().equals("survival")
                            && !survival.get("dug").getAsBoolean()
                            && survival.get("why").getAsString().contains("my tools can't harvest")
                            && level.getBlockState(second).is(Blocks.OBSIDIAN),
                    "in survival the status and the dig disagree: " + runs[1].receipt());
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /** 创造取物:numen.creative.give 凭空取 100 钻石入背包(创造物品栏 GUI 的假体)。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_mode")
    public static void creative_take_items(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer companion = spawnAt(helper, "gametest_conjure", new BlockPos(2, 2, 2), true);
        ToolRun reply = lua(companion, "numen.creative.give(\"minecraft:diamond\", {count = 100})");
        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, reply.succeeded(),
                    "creative give should succeed in creative, got: " + reply.reply());
            Checks.assertTrue(helper, companion.getInventory().countItem(Items.DIAMOND) == 100,
                    "expected 100 diamonds in inventory");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
