package com.dwinovo.numen.pathing.search;

import java.util.List;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.EditedView;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 埋深:埋在石头里的目标,从各处进去至少要挖开几格、按挖一格的最低价计;绕得开的不算;不许改地形时不算;沿搜出的路线,
 * 估价加埋深从不超过这条路剩下的价钱。地面在 {@code Y - 1}。
 */
class BurialTest {

    private static final int Y = 64;
    private static final BlockPos START = new BlockPos(0, Y, 0);
    private static BlockState STONE;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        STONE = Blocks.STONE.defaultBlockState();
    }

    /** 一块十二格厚的石头,顶面在 {@code Y - 1}。 */
    private static TestWorld slab() {
        return new TestWorld().fill(-8, Y - 12, -8, 8, Y - 1, 8, STONE);
    }

    /** 许挖许放,背包里一把铁镐。 */
    private static CostModel natural() {
        return CostModel.of(Fixtures.natural(), Fixtures.carrying(0, new ItemStack(Items.IRON_PICKAXE)),
                TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
    }

    private static Burial burial(TestWorld world, CostModel model, Goal goal) {
        return Burial.of(world, model, goal, START, Fixtures.BUDGET);
    }

    /**
     * 要站的那一格在石头里六格深:从正上方的地面下去,脚下新占的六格都要挖开;在地面上横着挪不要钱;在石头里横着挪一列,
     * 身体占着的两格都要挖开;在竖井里已经下到一半,只剩下面几格。
     */
    @Test
    void aCellBuriedInStoneCostsEveryCellInTheWay() {
        CostModel model = natural();
        double one = model.digFloor(STONE);
        Burial burial = burial(slab(), model, Goals.at(new BlockPos(0, Y - 6, 0)));
        assertEquals(6 * one, burial.floor(0, Y, 0), 1e-9);
        assertEquals(6 * one, burial.floor(3, Y, 0), 1e-9, "地面上横着挪不要钱");
        assertEquals(2 * one, burial.floor(1, Y - 6, 0), 1e-9, "石头里横着一列挖两格");
        assertEquals(3 * one, burial.floor(0, Y - 3, 0), 1e-9, "竖井下到一半");
    }

    /** 同样深的一格,旁边有一道通到地面的缝:从缝里走下去不用挖,那一格的埋深就是零。 */
    @Test
    void aWayInThroughTheOpenCostsNothing() {
        TestWorld world = slab().fill(1, Y - 6, 0, 1, Y - 1, 0, Blocks.AIR.defaultBlockState())
                .fill(0, Y - 6, 0, 0, Y - 5, 0, Blocks.AIR.defaultBlockState());
        Burial burial = burial(world, natural(), Goals.at(new BlockPos(0, Y - 6, 0)));
        assertEquals(0, burial.floor(0, Y, 0));
    }

    /** 规格不许改地形时路上不挖,不算埋深。 */
    @Test
    void noBurialWhenTheSpecMayNotDig() {
        CostModel none = natural().withSpec(RouteSpec.defaults());
        Burial burial = burial(slab(), none, Goals.at(new BlockPos(0, Y - 6, 0)));
        assertEquals(0, burial.floor(0, Y, 0));
    }

    /**
     * 挖正下方十格深处的一格、挖藏在一块石头里的一格:沿搜出来的路线,每一个节点上目标的估价加埋深,都不超过这条路从那里
     * 往后还要付的价钱(连同到了之后的到达价)——它是下界,不会把更便宜的路挤掉。
     */
    @Test
    void theEstimateWithBurialNeverExceedsWhatTheRouteStillCosts() {
        TestWorld world = slab().fill(4, Y, -4, 12, Y + 5, 4, STONE);
        CostModel model = natural();
        for (Goal goal : List.of(Goals.dig(new BlockPos(0, Y - 11, 0), Vanilla.SURVIVAL),
                Goals.dig(new BlockPos(9, Y + 1, 0), Vanilla.SURVIVAL))) {
            SearchResult result = Fixtures.search(world, model, START, goal);
            assertTrue(result.arrived(), goal + " " + result.stop());
            Route route = result.route();
            Burial burial = burial(world, model, goal);
            List<BlockPos> nodes = route.nodes();
            BlockPos end = route.end();
            List<Route.Leg> legs = route.legs();
            var there = EditedView.after(world, legs.get(legs.size() - 1).maneuver().edits());
            double left = goal.arrival(there, end.getX(), end.getY(), end.getZ(), route.endStance());
            for (int i = nodes.size() - 1; i >= 0; i--) {
                if (i < nodes.size() - 1) {
                    left += legs.get(i).cost();
                }
                BlockPos n = nodes.get(i);
                double estimate = goal.estimate(n.getX(), n.getY(), n.getZ()) + burial.floor(n.getX(), n.getY(), n.getZ());
                assertTrue(estimate <= left + 1e-9, goal + " 在 " + n + " 估 " + estimate + ",还要付 " + left);
            }
        }
    }
}
