package com.dwinovo.numen.pathing.search;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.block.SlabBlock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Fixtures.PACE;
import static com.dwinovo.numen.pathing.Fixtures.search;
import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 位置目标的估价是下界:从任何一个节点到目标的估价,不高过 A* 求出的真实最优代价(也就不高过任何一条路线的)。
 * 先逐种走法,再随机小地形。估价到处都是下界,A* 才会在预算内给出最优路线。
 */
class EstimateAdmissibilityTest {

    private static final int Y = 64;
    private static final BlockPos START = new BlockPos(0, Y, 0);
    private static BlockState STONE;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
        STONE = Blocks.STONE.defaultBlockState();
    }

    private static TestWorld field() {
        return new TestWorld().floor(-4, -12, 24, 12, Y - 1);
    }

    private static BlockState slab() {
        return Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
    }

    private static BlockState stair(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
    }

    private static double estimate(BlockPos from, BlockPos to) {
        return Goals.at(to).estimate(from.getX(), from.getY(), from.getZ(), PACE);
    }

    /**
     * 搜出最优路线,断言:每一步的估价(两端节点之间)不高过这一步的真实代价;路线上每个节点到终点的估价不高过剩下各步的
     * 代价之和。返回路线用过的走法。
     */
    private static Set<MoveKind> assertAdmissible(String scene, TestWorld world, CostModel model, BlockPos start, BlockPos goal) {
        SearchResult result = search(world, model, start, Goals.at(goal));
        assertTrue(result.arrived(), scene + ":搜不到 " + result.stop());
        Route route = result.route();
        Set<MoveKind> kinds = EnumSet.noneOf(MoveKind.class);
        List<Route.Leg> legs = route.legs();
        double remaining = route.cost();
        BlockPos at = start;
        for (Route.Leg leg : legs) {
            BlockPos to = leg.maneuver().to();
            kinds.add(leg.maneuver().kind());
            assertTrue(estimate(at, to) <= leg.cost() + 1e-9,
                    scene + ":" + leg.maneuver().kind() + " " + at + "→" + to + " 估价 " + estimate(at, to) + " 高过真实 " + leg.cost());
            assertTrue(estimate(at, goal) <= remaining + 1e-9, scene + ":" + at + " 到终点估价 " + estimate(at, goal) + " 高过剩下的 " + remaining);
            remaining -= leg.cost();
            at = to;
        }
        return kinds;
    }

    private static Set<MoveKind> assertAdmissible(String scene, TestWorld world, BlockPos goal) {
        return assertAdmissible(scene, world, Fixtures.model(RouteSpec.defaults()), START, goal);
    }

    private static Set<MoveKind> assertAdmissible(String scene, TestWorld world, BlockPos start, BlockPos goal) {
        return assertAdmissible(scene, world, Fixtures.model(RouteSpec.defaults()), start, goal);
    }

    /** 各种走法各自摆一处场景:每一步估价都不高过它的真实代价,所有走法都被走到过。 */
    @Test
    void estimateNeverExceedsTheCostOfAnyKindOfMove() {
        Set<MoveKind> seen = EnumSet.noneOf(MoveKind.class);

        seen.addAll(assertAdmissible("平地与斜走", field(), new BlockPos(7, Y, 3)));

        // 整块台阶:一级一级跳上去
        TestWorld blocks = field();
        for (int i = 1; i <= 5; i++) {
            blocks.fill(i, Y, -12, i, Y + i - 1, 12, STONE);
        }
        seen.addAll(assertAdmissible("整块台阶", blocks, Goals.standingOn(blocks, SURVIVAL, new BlockPos(5, Y + 4, 0))));

        // 半砖与整块交替:脚每次只抬半格,节点两步才升一格
        TestWorld slabs = field();
        for (int i = 0; i < 8; i++) {
            int level = i / 2;
            slabs.fill(i + 1, Y, -12, i + 1, Y + level - 1, 12, STONE);
            if (i % 2 == 0) {
                slabs.fill(i + 1, Y + level, -12, i + 1, Y + level, 12, slab());
            } else {
                slabs.fill(i + 1, Y + level, -12, i + 1, Y + level, 12, STONE);
            }
        }
        seen.addAll(assertAdmissible("半砖台阶", slabs, Goals.standingOn(slabs, SURVIVAL, new BlockPos(8, Y + 3, 0))));

        // 楼梯:每格升一级,走着上去不用跳
        TestWorld stairs = field();
        for (int i = 1; i <= 6; i++) {
            stairs.fill(i, Y, -12, i, Y + i - 2, 12, STONE);
            stairs.fill(i, Y + i - 1, -12, i, Y + i - 1, 12, stair(Direction.EAST));
        }
        seen.addAll(assertAdmissible("楼梯", stairs, Goals.standingOn(stairs, SURVIVAL, new BlockPos(6, Y + 5, 0))));

        // 下一级与下落:从三格高的台上下来
        TestWorld tower = field().fill(-1, Y, -12, 0, Y + 2, 12, STONE);
        seen.addAll(assertAdmissible("下台", tower, new BlockPos(-1, Y + 3, 0), new BlockPos(5, Y, 0)));
        TestWorld step = field().fill(-1, Y, -12, 0, Y, 12, STONE);
        seen.addAll(assertAdmissible("下一级", step, new BlockPos(0, Y + 1, 0), new BlockPos(4, Y, 0)));

        // 跑酷:整条横向的空隙
        CostModel leaping = Fixtures.model(RouteSpec.defaults().edit().parkour(true).build());
        TestWorld gap = field().fill(3, Y - 1, -12, 4, Y - 1, 12, Blocks.AIR.defaultBlockState());
        seen.addAll(assertAdmissible("跳空隙", gap, leaping, START, new BlockPos(8, Y, 0)));
        TestWorld wide = field().fill(3, Y - 1, -12, 5, Y - 1, 12, Blocks.AIR.defaultBlockState());
        seen.addAll(assertAdmissible("跳宽空隙", wide, leaping, START, new BlockPos(9, Y, 0)));

        // 梯子:上去、下来
        TestWorld ladder = field().fill(1, Y, -1, 1, Y + 2, 1, STONE);
        for (int y = Y; y < Y + 3; y++) {
            ladder.set(0, y, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.WEST));
        }
        seen.addAll(assertAdmissible("上梯子", ladder, Fixtures.model(RouteSpec.defaults()), new BlockPos(-1, Y, 0), new BlockPos(1, Y + 3, 0)));
        seen.addAll(assertAdmissible("下梯子", ladder, Fixtures.model(RouteSpec.defaults()), new BlockPos(1, Y + 3, 0), new BlockPos(-1, Y, 0)));

        // 游:横渡一池水、潜到池底
        TestWorld pool = new TestWorld().floor(-6, -6, 6, 6, Y - 5)
                .fill(-6, Y - 4, -6, -5, Y - 1, 6, STONE).fill(5, Y - 4, -6, 6, Y - 1, 6, STONE)
                .fill(-4, Y - 4, -6, 4, Y - 1, 6, Blocks.WATER.defaultBlockState());
        seen.addAll(assertAdmissible("游过去", pool, Fixtures.model(RouteSpec.defaults()), new BlockPos(-5, Y, 0), new BlockPos(5, Y, 0)));
        seen.addAll(assertAdmissible("潜下去", pool, Fixtures.model(RouteSpec.defaults()), new BlockPos(-5, Y, 0), new BlockPos(0, Y - 4, 0)));

        // 垫柱与向下挖:许挖许放,身上带着圆石
        CostModel dig = Fixtures.withCobble(com.dwinovo.numen.pathing.Fixtures.natural());
        seen.addAll(assertAdmissible("垫柱", field(), dig, START, new BlockPos(0, Y + 4, 0)));
        seen.addAll(assertAdmissible("向下挖", new TestWorld().ground(Y - 1, STONE), dig, START, new BlockPos(0, Y - 3, 0)));

        assertTrue(seen.containsAll(EnumSet.allOf(MoveKind.class)), "没走到的走法:" + EnumSet.complementOf(EnumSet.copyOf(seen)));
    }

    /** 随机小地形:高低起伏、夹着半砖与空洞,起点到终点的估价不高过最优代价,路线上每个节点也一样。 */
    @Test
    void estimateNeverExceedsTheOptimalCostOnRandomSmallTerrains() {
        Random random = new Random(0x5eed);
        CostModel model = Fixtures.model(RouteSpec.defaults());
        int arrived = 0;
        for (int round = 0; round < 150; round++) {
            int size = 7;
            TestWorld world = new TestWorld();
            for (int x = 0; x < size; x++) {
                for (int z = 0; z < size; z++) {
                    int top = Y - 1 + random.nextInt(4) - 1;
                    world.fill(x, Y - 6, z, x, top, z, STONE);
                    if (random.nextInt(4) == 0) {
                        world.set(x, top + 1, z, slab());
                    }
                    if (random.nextInt(12) == 0) {
                        world.fill(x, Y - 6, z, x, Y + 3, z, Blocks.AIR.defaultBlockState());
                    }
                }
            }
            BlockPos start = standing(world, 0, 0);
            BlockPos goal = standing(world, size - 1, size - 1);
            if (start == null || goal == null) {
                continue;
            }
            SearchResult result = search(world, model, start, Goals.at(goal));
            if (!result.arrived()) {
                continue;
            }
            arrived++;
            double remaining = result.route().cost();
            BlockPos at = start;
            for (Route.Leg leg : result.route().legs()) {
                assertTrue(estimate(at, goal) <= remaining + 1e-9,
                        "第 " + round + " 张:" + at + " 到 " + goal + " 估价 " + estimate(at, goal) + " 高过剩下的 " + remaining);
                remaining -= leg.cost();
                at = leg.maneuver().to();
            }
        }
        assertTrue(arrived >= 30, "随机地形里搜到路线的太少:" + arrived);
    }

    /** 这一列地面上身体站得住的节点,站不住为 null。 */
    private static BlockPos standing(TestWorld world, int x, int z) {
        for (int y = Y + 3; y >= Y - 6; y--) {
            if (!world.getBlockState(new BlockPos(x, y, z)).isAir()) {
                return Goals.standingOn(world, SURVIVAL, new BlockPos(x, y, z));
            }
        }
        return null;
    }
}
