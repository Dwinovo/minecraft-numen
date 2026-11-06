package com.dwinovo.numen.task.move;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.world.Kinematics;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.SearchResult;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 走到物品旁边是走到捡得到它的地方:贴着台沿的物品坐标取整落在悬空的格里,从它脚下那一格就捡得到。 */
class ItemReachTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 一块物品静止在台沿:中心 x = 10.977,盒子宽 0.25,底在 y = -57,取整的格是 (10, -57)。 */
    private static final AABB LIP = new AABB(10.977 - 0.125, -57, 0.5 - 0.125, 10.977 + 0.125, -57 + 0.25, 0.5 + 0.125);

    private static Stance ground(int feetCellY) {
        return new Stance(Stance.Kind.GROUND, feetCellY, feetCellY - 1);
    }

    @Test
    void theCellBelowAnItemOnALedgeReachesIt() {
        ItemReach reach = new ItemReach(LIP);
        assertTrue(reach.contains(10, -58, 0, ground(-58)), "物品所在格的脚下那一格站得进去,捡得到");
        assertFalse(reach.contains(8, -58, 0, ground(-58)), "再远一格捡不到");
    }

    /**
     * 物品落在坑里它那一格的远侧(中心离邻格中心 1.375 到 1.5,格中心碰不上:碰得上的上限是 0.3 + 1 + 0.125 = 1.425):从邻格朝它走到被挡住,
     * 身体偏出格中心 {@link ItemReach#LEAN},偏出 0 到 0.5 的全都够得着;隔得再远一格够不着。
     */
    @Test
    void anItemAnywhereInAPocketIsReachedFromTheNeighbourByWalkingToIt() {
        for (double offset : new double[] {0.375, 0.45, 0.49, 0.5}) {
            AABB item = new AABB(0.375, -58, 1 + offset - 0.125, 0.625, -57.75, 1 + offset + 0.125);
            ItemReach reach = new ItemReach(item);
            assertTrue(reach.contains(0, -58, 0, ground(-58)), "物品偏出 " + offset + ",邻格朝它走够得着");
            assertFalse(reach.contains(0, -58, -1, ground(-58)), "偏出 " + offset + ",再隔一格够不着");
        }
    }

    @Test
    void theBodyReachesTheItemByTheVanillaPickupRule() {
        ItemReach reach = new ItemReach(new AABB(0.375, -58, 1.875, 0.625, -57.75, 2.125));
        assertFalse(reach.reaches(new AABB(0.2, -58, 0.2, 0.8, -56.2, 0.8)), "格中心碰不上");
        assertTrue(reach.reaches(new AABB(0.2, -58, 0.4, 0.8, -56.2, 1.0)), "朝它走到被挡住(偏出 LEAN)就碰上");
    }

    @Test
    void theEstimateIsZeroWhereItIsReachedAndNeverMoreThanTheWayThere() {
        ItemReach reach = new ItemReach(LIP);
        Kinematics.Pace pace = new Kinematics.Pace(4, 3);
        assertEquals(0, reach.estimate(10, -58, 0, pace), 1e-9);
        double far = reach.estimate(20, -58, 0, pace);
        assertTrue(far > 0 && far <= pace.sprint() * (20 - 10), "估价不高过走过去的格数");
    }

    /**
     * 掉在正下方十五格石头里一个小空腔底的东西:终点格与高度范围让估价知道要往下挖。捡得到的格水平上有三格宽,搜索在这一片里
     * 找最便宜的下法,展开几千个节点;终点格与高度范围交 null 时是二十几万。
     */
    @Test
    void anItemAtTheBottomOfACavityDeepBelowIsDugDownToWithoutSpreadingOverTheGround() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        TestWorld ground = new TestWorld().ground(63, stone).loadedWithin(6)
                .fill(0, 48, 0, 0, 49, 0, Blocks.AIR.defaultBlockState());
        CostModel model = CostModel.of(Fixtures.natural(), Fixtures.carrying(0, new ItemStack(Items.IRON_PICKAXE)),
                Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, Threats.NONE);
        AABB item = new AABB(0.375, 48, 0.375, 0.625, 48.25, 0.625);
        SearchResult result = Fixtures.search(ground, model, new BlockPos(0, 64, 0), new ItemReach(item), NavRequest.DEFAULT_BUDGET);
        assertTrue(result.arrived(), result.stop() + ",展开了 " + result.expanded());
        assertTrue(result.expanded() < 10000, "展开了 " + result.expanded());
    }
}
