package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 视线:从眼睛到一格方块某一面上的一点,碰没碰上它、碰上的是哪一面、路上隔着哪些硬遮挡与软遮挡;面敞不敞开;
 * "用"一面要面朝眼睛、在交互距离内、没有硬遮挡。
 */
class SightTest {

    private static final int Y = 64;
    private static final BlockPos TARGET = new BlockPos(4, Y, 0);
    /** 站在 (0, Y, 0) 那一格上的眼睛。 */
    private static final Vec3 EYE = new Vec3(0.5, Y + 1.62, 0.5);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static TestWorld withTarget() {
        return new TestWorld().floor(-4, -4, 8, 4, Y - 1).set(TARGET, Blocks.FURNACE.defaultBlockState());
    }

    private static Vec3 westFace(TestWorld world) {
        return Sight.aim(world, TARGET, Direction.WEST);
    }

    @Test
    void anOpenLineHitsTheFaceItAimsAt() {
        TestWorld world = withTarget();
        Sight.Trace trace = Sight.trace(world, EYE, westFace(world), TARGET);
        assertTrue(trace.reached());
        assertEquals(Direction.WEST, trace.face());
        assertTrue(trace.clear(Direction.WEST));
        assertFalse(trace.clear(Direction.NORTH), "碰上的是西面,不是北面");
    }

    @Test
    void aSolidBlockInBetweenIsAHardBlocker() {
        TestWorld world = withTarget().set(2, Y + 1, 0, Blocks.STONE.defaultBlockState())
                .set(2, Y, 0, Blocks.STONE.defaultBlockState());
        Sight.Trace trace = Sight.trace(world, EYE, westFace(world), TARGET);
        assertTrue(trace.reached(), "数到目标为止,硬遮挡之后照样碰上它");
        assertFalse(trace.clearable(Direction.WEST));
        assertFalse(trace.hard().isEmpty());
        assertTrue(trace.soft().isEmpty());
    }

    /** 高草在视线上:软遮挡,清掉就看得见。 */
    @Test
    void tallGrassInTheWayIsASoftBlocker() {
        TestWorld world = withTarget()
                .set(3, Y, 0, Blocks.TALL_GRASS.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER))
                .set(3, Y + 1, 0, Blocks.TALL_GRASS.defaultBlockState()
                        .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
        Sight.Trace grass = Sight.trace(world, EYE, westFace(world), TARGET);
        assertTrue(grass.clearable(Direction.WEST));
        assertFalse(grass.clear(Direction.WEST));
        assertFalse(grass.soft().isEmpty());
        assertTrue(grass.hard().isEmpty());
    }

    /** 软遮挡的判据是放一块别种方块时原版会不会直接顶掉它:草与单层雪会,两层雪、花、石头不会。 */
    @Test
    void softMeansDisplacedByPlacingAnotherBlock() {
        assertTrue(Sight.soft(Blocks.SHORT_GRASS.defaultBlockState()));
        assertTrue(Sight.soft(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1)));
        assertFalse(Sight.soft(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2)));
        assertFalse(Sight.soft(Blocks.POPPY.defaultBlockState()));
        assertFalse(Sight.soft(Blocks.STONE.defaultBlockState()));
    }

    /** 面前一格是整块的硬方块,那一面封着;是空气、高草、下半砖,视线过得去。 */
    @Test
    void aFaceIsOpenUnlessAWholeHardBlockCoversIt() {
        TestWorld world = withTarget().set(TARGET.north(), Blocks.STONE.defaultBlockState())
                .set(TARGET.south(), Blocks.SHORT_GRASS.defaultBlockState())
                .set(TARGET.above(), Blocks.STONE_SLAB.defaultBlockState());
        assertFalse(Sight.open(world, TARGET, Direction.NORTH));
        assertFalse(Sight.open(world, TARGET, Direction.DOWN), "脚下是地板");
        assertTrue(Sight.open(world, TARGET, Direction.SOUTH));
        assertTrue(Sight.open(world, TARGET, Direction.UP));
        assertTrue(Sight.open(world, TARGET, Direction.WEST));
    }

    /** 用一面:敞开、面朝着眼睛、在交互距离内、没有硬遮挡才交出视线。 */
    @Test
    void usingAFaceNeedsItToFaceTheEyeWithinReachUnblocked() {
        TestWorld world = withTarget();
        assertNotNull(Sight.use(world, EYE, 4.5, TARGET, Direction.WEST));
        assertNull(Sight.use(world, EYE, 4.5, TARGET, Direction.EAST), "东面背对着眼睛");
        assertNull(Sight.use(world, EYE, 3.0, TARGET, Direction.WEST), "交互距离只有三格,够不着");
        world.set(2, Y, 0, Blocks.STONE.defaultBlockState()).set(2, Y + 1, 0, Blocks.STONE.defaultBlockState());
        assertNull(Sight.use(world, EYE, 4.5, TARGET, Direction.WEST), "中间隔着石头");
    }

    /** 面前贴着整块硬方块的面不能用,哪怕从一道细缝里斜着看得到它。 */
    @Test
    void aFaceCoveredByAWholeBlockIsNotUsed() {
        TestWorld world = withTarget().set(TARGET.above(), Blocks.GLASS.defaultBlockState());
        Vec3 high = new Vec3(0.5, Y + 3.5, 0.5);
        assertNull(Sight.use(world, high, 6, TARGET, Direction.UP));
    }

    @Test
    void clickableMeansAnOutline() {
        TestWorld world = withTarget().set(0, Y, 3, Blocks.WATER.defaultBlockState());
        assertTrue(Sight.clickable(world, TARGET));
        assertFalse(Sight.clickable(world, new BlockPos(0, Y + 3, 0)), "空气");
        assertFalse(Sight.clickable(world, new BlockPos(0, Y, 3)), "水");
    }
}
