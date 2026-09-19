package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/** {@code interact_at} 对着水面:舀水、放船。 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class InteractGameTests {

    /**
     * 桶对水右键:准星式右键的完整管线钉桩。准星射线不含流体(与原版一致),
     * 水面永远点不中——桶的取水逻辑住在 Item.use 里、自带 SOURCE_ONLY 射线,
     * 靠的是"方块没吃掉点击就落到物品自用"那步兜底。守住它:没有兜底时
     * 对水右键永远空手,工具却报成功。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_interact")
    public static void interact_bucket_scoops_aimed_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 沉进地板的一格水:四邻就是地板块,天然围住;地表水盆的沿会挡住
        // 下探的视线(她的眼睛只比水面高一格半,射线在沿上就切进石头了)
        BlockPos water = helper.absolutePos(new BlockPos(5, 1, 5));
        level.setBlockAndUpdate(water, Blocks.WATER.defaultBlockState());

        NumenPlayer companion = spawnAt(helper, "gametest_scooper", new BlockPos(3, 2, 5), false);
        companion.getInventory().add(new ItemStack(Items.BUCKET));
        // 等两刻让身体落稳(生成那一刻还没过物理,onGround 为假)再派活
        String[] receipt = {"(no reply yet)"};
        helper.runAfterDelay(2, () -> {
            TaskRecord record = new BlockActionOps().interactAt("right",
                    water.getX(), water.getY(), water.getZ(), null, "minecraft:bucket",
                    TaskDispatch.ctx("gametest-scoop", companion));
            TaskDispatch.runSync(companion, record, reply -> receipt[0] = reply);
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(companion.getInventory().countItem(Items.WATER_BUCKET) == 1,
                    "the bucket did not scoop the aimed water — tool reply: " + receipt[0]);
            helper.assertTrue(!level.getBlockState(water).getFluidState().isSource(),
                    "the aimed water source is still there");
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }

    /**
     * 船对水右键:BoatItem 的行为同样住在 Item.use 里(Fluid.ANY 自射线),
     * 生成位与身体重叠还会被原版 noCollision 静默拒绝——所以她站在岸上、
     * 瞄几格外的池心。守的是同一步兜底 + "放出去的船真的存在"。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_interact")
    public static void interact_boat_places_on_aimed_water(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // 3x3 水池,外圈一格石堤
        for (int x = 6; x <= 10; x++) {
            for (int z = 6; z <= 10; z++) {
                boolean rim = x == 6 || x == 10 || z == 6 || z == 10;
                level.setBlockAndUpdate(helper.absolutePos(new BlockPos(x, 2, z)),
                        rim ? Blocks.STONE.defaultBlockState() : Blocks.WATER.defaultBlockState());
            }
        }
        NumenPlayer companion = spawnAt(helper, "gametest_sailor", new BlockPos(5, 2, 8), false);
        companion.getInventory().add(new ItemStack(Items.OAK_BOAT));
        // 瞄远列而不是池心:视线在下降途中提前碰到水面,命中点比瞄点近一截;
        // 瞄池心时船的碰撞箱(宽 1.375)会搭在石堤上被 noCollision 拒绝——
        // 真玩家放船也是往远处的水面看,不盯着脚边的岸沿。
        BlockPos aim = helper.absolutePos(new BlockPos(9, 2, 8));
        // 等两刻让身体落稳(生成那一刻还没过物理,onGround 为假)再派活
        String[] receipt = {"(no reply yet)"};
        helper.runAfterDelay(2, () -> {
            TaskRecord record = new BlockActionOps().interactAt("right",
                    aim.getX(), aim.getY(), aim.getZ(), null, "minecraft:oak_boat",
                    TaskDispatch.ctx("gametest-boat", companion));
            TaskDispatch.runSync(companion, record, reply -> receipt[0] = reply);
        });

        helper.succeedWhen(() -> {
            var boats = level.getEntitiesOfClass(net.minecraft.world.entity.vehicle.Boat.class,
                    new net.minecraft.world.phys.AABB(
                            helper.absolutePos(new BlockPos(6, 1, 6)).getCenter(),
                            helper.absolutePos(new BlockPos(10, 4, 10)).getCenter()));
            helper.assertTrue(!boats.isEmpty(),
                    "no boat appeared on the aimed water — tool reply: " + receipt[0]);
            CompanionFactory.despawn(level.getServer(), companion);
        });
    }
}
