package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.BlockActionTools;
import com.dwinovo.numen.core.tools.MovementTools;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 同伴行为的游戏内自动化用例(无头 gameTestServer 运行,{@code gradlew :neoforge:runGameTestServer}):
 * 在结构模板圈出的场地里,用真实的生成路径拉起同伴、经真实任务队列下发指令,按 tick 轮询断言
 * 世界状态——退出码 = 失败用例数,可直接进 CI。
 *
 * <p>结构模板以 SNBT 文本存于仓库 {@code neoforge/gameteststructures/}(运行配置经系统属性
 * {@code numen.gametest.structures} 指路),不提交二进制 .nbt。注意两件事:模板必须是
 * gametest 的"打包" SNBT 形态(palette 为字符串、方块表叫 {@code data}——裸结构 NBT 形态
 * 会被 {@code NbtUtils.unpackStructureTemplate} 静默丢弃,一块不放);且模板方块落位在
 * {@code 测试原点+1+rel},而 {@link GameTestHelper#absolutePos} 只加 {@code rel}——引用
 * 模板内 rel y 的格子时要再 +1。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CompanionGameTests {

    static {
        String dir = System.getProperty("numen.gametest.structures");
        if (dir != null) {
            StructureUtils.testStructuresDir = dir;
        }
    }

    /**
     * 冒烟:同伴能在测试世界里存活并走完一段路。验证的是整条链路——假玩家生成
     * (载档→入场→落位)、任务入队、后台 A* 搜索、逐 tick 执行——在无头环境下全通。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_smoke")
    public static void companion_goto(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(2, 2, 2));
        BlockPos target = helper.absolutePos(new BlockPos(13, 2, 13));

        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_scout", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));

        TaskRecord record = (TaskRecord) new MovementTools().moveTo(
                (double) target.getX(), (double) target.getY(), (double) target.getZ(), null,
                TaskDispatch.ctx("gametest-goto", companion));
        TaskDispatch.enqueue(companion, record, reply -> {});

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.blockPosition().distSqr(target) <= 2 * 2,
                    "companion has not reached the goto target");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    // ==================== 真实地形挖掘用例(模板取自实际存档地形)====================

    /** 挖掘批次前置:和平难度 + 正午,排除怪物袭扰与昼夜随机性。 */
    @BeforeBatch(batch = "numen_mine")
    public static void prepareMineBatch(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
    }

    /**
     * 真实云杉林(高树场景):手持铁斧砍 8 根原木。
     *
     * <p>超时按游戏刻给得很宽:无头测试服不限速(数百 tps),而寻路搜索预算是墙钟毫秒——
     * 一次 200ms 的真实搜索在这里折合上百游戏刻,超时必须覆盖"搜索墙钟 × tps"的放大。走完整生产链路——目标索引注册与
     * 查询、复合站位、眼及就地挖掘、探底波段、掉落拾取、背包计数。
     */
    @GameTest(template = "real_spruce_forest", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_spruce_forest(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(1, 15, 1));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_logger", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        companion.getInventory().add(new ItemStack(Items.IRON_AXE));

        TaskRecord record = new BlockActionTools().autoMine(
                List.of("minecraft:spruce_log"), 8, TaskDispatch.ctx("gametest-mine", companion));
        TaskDispatch.dispatchAsync(companion, record, reply -> {});

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.SPRUCE_LOG) >= 8,
                    "companion has not gathered 8 spruce logs");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 真实深板岩矿袋(袋内 26 颗钻石矿):站在顶面,手持铁镐向下挖入,采得 2 颗钻石。
     * 覆盖埋矿的挖入站位语义与索引查询。
     */
    @GameTest(template = "real_diamond_pocket", timeoutTicks = 100000, batch = "numen_mine")
    public static void mine_diamond_pocket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos spawn = helper.absolutePos(new BlockPos(8, 17, 8));
        NumenPlayer companion = CompanionFactory.spawn(level.getServer(), UUID.randomUUID(),
                "gametest_miner", UUID.randomUUID(), level,
                new Vec3(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5));
        companion.getInventory().add(new ItemStack(Items.IRON_PICKAXE));

        TaskRecord record = new BlockActionTools().autoMine(
                List.of("minecraft:deepslate_diamond_ore"), 2, TaskDispatch.ctx("gametest-mine", companion));
        TaskDispatch.dispatchAsync(companion, record, reply -> {});

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) >= 2,
                    "companion has not gathered 2 diamonds");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
