package com.dwinovo.numen.pathing.search;

import java.util.List;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Kinematics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 竖直方向的额外代价下界:要升降几个节点、每个至少多花多少,按这一带的方块、身体与规格算。目标的高度范围是 {@code Y}。
 * 每种走法真实代价不低于它的下降,由 {@link EstimateConsistencyTest} 逐种走法核对;这里看数是怎么来的。
 */
class VerticalTest {

    private static final int Y = 64;
    private static final Goal GOAL = Goals.at(new BlockPos(0, Y, 0));
    private static final double SPRINT = Kinematics.sprintTicksPerBlock(SURVIVAL);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static Vertical of(TestWorld world, CostModel model) {
        return Vertical.of(world, model, GOAL);
    }

    private static CostModel model(RouteSpec spec) {
        return Fixtures.model(spec);
    }

    /** 整块的地:往上每个节点至少是一次起跳的工夫加起跳罚分,减去水平上已经付了的一个疾跑一格。 */
    @Test
    void eachNodeUpCostsAJumpOnFullBlocks() {
        TestWorld world = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        RouteSpec spec = RouteSpec.defaults();
        double jump = Kinematics.JUMP_DELAY + spec.jumpPenalty() - SPRINT;
        Vertical vertical = of(world, model(spec));
        assertEquals(jump, vertical.floor(Y - 1), 1e-9);
        assertEquals(5 * jump, vertical.floor(Y - 5), 1e-9);
        assertEquals(0, vertical.floor(Y), "就在目标那一层");
    }

    /** 这一层有半砖:脚只抬半格也升一个节点,不用跳,从这一层往上不另收钱;没有半砖的层照收。 */
    @Test
    void stepsOfHalfABlockCostNothingToGoUpFromTheirOwnLevel() {
        TestWorld world = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        for (int y = Y - 3; y < Y; y++) {
            world.set(2, y, 0, Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        }
        RouteSpec spec = RouteSpec.defaults();
        double jump = Kinematics.JUMP_DELAY + spec.jumpPenalty() - SPRINT;
        Vertical vertical = of(world, model(spec));
        assertEquals(0, vertical.floor(Y - 3), "三层都有半砖");
        assertEquals(jump, vertical.floor(Y - 4), 1e-9, "再往下一层没有,照收");
    }

    /** 许跑酷:跑酷落在高一级上,空中的工夫由跨过的几格水平距离付了,只剩起跳罚分。 */
    @Test
    void parkourLeavesOnlyTheJumpPenaltyPerLevel() {
        TestWorld world = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        RouteSpec spec = RouteSpec.defaults().edit().parkour(true).build();
        assertEquals(3 * spec.jumpPenalty(), of(world, model(spec)).floor(Y - 3), 1e-9);
    }

    /** 往下落:一次落脚是走出边沿加落下去的工夫减去水平底价;上限之内落得深的更划算,超过上限要分成几次。 */
    @Test
    void droppingIsPricedPerLandingAndCappedByTheFallLimit() {
        TestWorld world = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        Vertical vertical = of(world, model(RouteSpec.defaults()));
        double once = Kinematics.walkOffEdgeTicks(SURVIVAL) + Kinematics.fallLandingTicks(SURVIVAL, 1) - SPRINT;
        assertEquals(once, vertical.floor(Y + 1), 1e-9);
        double deepest = Kinematics.walkOffEdgeTicks(SURVIVAL) + Kinematics.fallLandingTicks(SURVIVAL, 3) - SPRINT;
        assertEquals(deepest, vertical.floor(Y + 3), 1e-9, "上限 3 以内一次落完");
        assertTrue(vertical.floor(Y + 3) < 3 * vertical.floor(Y + 1), "落得深的更划算");
        assertEquals(deepest + once, vertical.floor(Y + 4), 1e-9, "超过上限的分成两次");
        assertEquals(2 * deepest, vertical.floor(Y + 6), 1e-9);
    }

    /** 这一层有水:落进水里不摔,落脚没有上限,落多高都只算一次。水在别的层不算。 */
    @Test
    void waterOnALevelRemovesTheFallLimitForLandingsThere() {
        TestWorld dry = new TestWorld().floor(-4, -4, 4, 4, Y - 5);
        TestWorld wet = new TestWorld().floor(-4, -4, 4, 4, Y - 5).fill(-4, Y - 4, -4, 4, Y - 1, 4, Blocks.WATER.defaultBlockState());
        CostModel model = model(RouteSpec.defaults());
        Goal deep = Goals.level(Y - 4);
        double plunge = Kinematics.walkOffEdgeTicks(SURVIVAL) + Kinematics.fallLandingTicks(SURVIVAL, 13) - SPRINT;
        assertTrue(Vertical.of(wet, model, deep).floor(Y + 12) <= plunge + Vertical.of(wet, model, deep).floor(Y - 1) + 1e-9);
        assertTrue(Vertical.of(wet, model, deep).floor(Y + 12) < Vertical.of(dry, model, deep).floor(Y + 12));
    }

    /** 身上带着水桶:摔不起的坠落可以倒水接住,没有上限。 */
    @Test
    void aWaterBucketRemovesTheFallLimitToo() {
        TestWorld world = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        CostModel carrying = CostModel.of(RouteSpec.defaults(), Fixtures.carrying(0, new ItemStack(Items.WATER_BUCKET)),
                Fixtures.ALLOW_ALL, Fixtures.NO_MATERIALS, com.dwinovo.numen.pathing.plan.Threats.NONE);
        assertTrue(of(world, carrying).floor(Y + 12) < of(world, model(RouteSpec.defaults())).floor(Y + 12));
    }

    /** 落脚那一层有土径这样顶面离格底不到一格的矮块:落一个节点的落差不足一格,落脚更便宜。 */
    @Test
    void lowBlocksMakeAOneNodeDropCheaper() {
        TestWorld full = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        TestWorld paths = new TestWorld().floor(-4, -4, 4, 4, Y - 1).set(2, Y, 0, Blocks.DIRT_PATH.defaultBlockState());
        CostModel model = model(RouteSpec.defaults());
        assertTrue(of(paths, model).floor(Y + 1) < of(full, model).floor(Y + 1));
    }

    /** 没有高度范围的目标(离某处多远)不加竖直的额外代价。 */
    @Test
    void goalsWithoutLevelsAddNothing() {
        TestWorld world = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        Vertical vertical = Vertical.of(world, model(RouteSpec.defaults()), Goals.within(Goals.column(0, 0), 3, 5));
        assertEquals(0, vertical.floor(Y - 10));
        assertEquals(0, vertical.floor(Y + 10));
    }

    /** 目标有一段高度范围:范围之内不用升降,范围以下按升、以上按降算到离得最近的一层。 */
    @Test
    void aRangeOfLevelsIsMeasuredFromItsNearestEnd() {
        TestWorld world = new TestWorld().floor(-4, -4, 4, 4, Y - 1);
        Goal range = Goals.anyOf(List.of(Goals.level(Y), Goals.level(Y + 4)));
        Vertical vertical = Vertical.of(world, model(RouteSpec.defaults()), range);
        assertEquals(0, vertical.floor(Y + 2));
        assertEquals(of(world, model(RouteSpec.defaults())).floor(Y - 1), vertical.floor(Y - 1), 1e-9);
        assertEquals(of(world, model(RouteSpec.defaults())).floor(Y + 1), vertical.floor(Y + 5), 1e-9);
    }

    /**
     * 表是问到哪一层才填到哪一层,填出来的数与问的先后无关:各带(16 层)里的落脚、上一带顶上的栅栏压到下一带最低一层的落脚,
     * 隔着几带从上往下问、从下往上问、单独问一层,同一层答出来的数逐位相同。
     */
    @Test
    void theAnswerDoesNotDependOnTheOrderOfTheQuestions() {
        TestWorld world = new TestWorld().floor(-4, -4, 4, 4, Y - 1)
                .set(1, 15, 0, Blocks.OAK_FENCE.defaultBlockState())
                .set(1, 30, 1, Blocks.DIRT_PATH.defaultBlockState())
                .set(2, 47, 0, Blocks.WATER.defaultBlockState())
                .set(2, 80, 2, Blocks.LADDER.defaultBlockState())
                .set(3, 111, 0, Blocks.OAK_FENCE.defaultBlockState());
        CostModel model = model(RouteSpec.defaults());
        Vertical down = of(world, model);
        Vertical up = of(world, model);
        int[] levels = {-20, -1, 0, 15, 16, 17, 31, 32, 48, 60, 100, 112, 113, 140};
        double[] asked = new double[levels.length];
        for (int i = levels.length - 1; i >= 0; i--) {
            asked[i] = down.floor(levels[i]);
        }
        for (int i = 0; i < levels.length; i++) {
            assertEquals(Double.doubleToLongBits(asked[i]), Double.doubleToLongBits(up.floor(levels[i])), "第 " + levels[i] + " 层");
            assertEquals(Double.doubleToLongBits(asked[i]), Double.doubleToLongBits(of(world, model).floor(levels[i])), "单独问第 " + levels[i] + " 层");
        }
    }
}
