package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.FakeWorld;

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
        assertTrue(FakeWorld.boot(), "Minecraft 引导不可用");
    }

    private static FakeWorld withTarget() {
        return new FakeWorld().floor(-4, -4, 8, 4, Y - 1).set(TARGET, Blocks.FURNACE.defaultBlockState());
    }

    private static Vec3 westFace(FakeWorld world) {
        return Sight.aim(world, TARGET, Direction.WEST);
    }

    @Test
    void anOpenLineHitsTheFaceItAimsAt() {
        FakeWorld world = withTarget();
        Sight.Trace trace = Sight.trace(world, EYE, westFace(world), TARGET);
        assertTrue(trace.reached());
        assertEquals(Direction.WEST, trace.face());
        assertTrue(trace.clear(Direction.WEST));
        assertFalse(trace.clear(Direction.NORTH), "碰上的是西面,不是北面");
    }

    @Test
    void aSolidBlockInBetweenIsAHardBlocker() {
        FakeWorld world = withTarget().set(2, Y + 1, 0, Blocks.STONE.defaultBlockState())
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
        FakeWorld world = withTarget()
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
        FakeWorld world = withTarget().set(TARGET.north(), Blocks.STONE.defaultBlockState())
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
        FakeWorld world = withTarget();
        assertNotNull(Sight.use(world, EYE, 4.5, TARGET, Direction.WEST));
        assertNull(Sight.use(world, EYE, 4.5, TARGET, Direction.EAST), "东面背对着眼睛");
        assertNull(Sight.use(world, EYE, 3.0, TARGET, Direction.WEST), "交互距离只有三格,够不着");
        world.set(2, Y, 0, Blocks.STONE.defaultBlockState()).set(2, Y + 1, 0, Blocks.STONE.defaultBlockState());
        assertNull(Sight.use(world, EYE, 4.5, TARGET, Direction.WEST), "中间隔着石头");
    }

    /** 面前贴着整块硬方块的面不能用,哪怕从一道细缝里斜着看得到它。 */
    @Test
    void aFaceCoveredByAWholeBlockIsNotUsed() {
        FakeWorld world = withTarget().set(TARGET.above(), Blocks.GLASS.defaultBlockState());
        Vec3 high = new Vec3(0.5, Y + 3.5, 0.5);
        assertNull(Sight.use(world, high, 6, TARGET, Direction.UP));
    }

    /** 整块目标自己算碰没碰上、碰哪一面,与原版 {@code VoxelShape.clip} 对同一条射线一致(远近、从格里射出、贴着面都有)。 */
    @Test
    void aWholeBlockTargetIsHitWhereVanillaClipHitsIt() {
        FakeWorld world = new FakeWorld().set(TARGET, Blocks.STONE.defaultBlockState());
        java.util.Random random = new java.util.Random(20261101);
        int hits = 0;
        for (int i = 0; i < 20000; i++) {
            Vec3 eye = new Vec3(TARGET.getX() + random.nextDouble() * 8 - 4, TARGET.getY() + random.nextDouble() * 8 - 4,
                    TARGET.getZ() + random.nextDouble() * 8 - 4);
            Vec3 point = i % 4 == 0
                    ? new Vec3(TARGET.getX() + 0.5 + Math.round(random.nextDouble() * 2 - 1) * 0.48, TARGET.getY() + 0.5, TARGET.getZ() + random.nextDouble())
                    : new Vec3(TARGET.getX() + random.nextDouble(), TARGET.getY() + random.nextDouble(), TARGET.getZ() + random.nextDouble());
            // 射线打到瞄点再往前伸 0.1
            double dx = point.x - eye.x;
            double dy = point.y - eye.y;
            double dz = point.z - eye.z;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            Vec3 through = new Vec3(point.x + dx / length * 0.1, point.y + dy / length * 0.1, point.z + dz / length * 0.1);
            net.minecraft.world.phys.BlockHitResult vanilla = net.minecraft.world.phys.shapes.Shapes.block().clip(eye, through, TARGET);
            Sight.Trace trace = Sight.trace(world, eye, point, TARGET);
            assertEquals(vanilla != null, trace.reached(), eye + " -> " + point);
            assertEquals(vanilla == null ? null : vanilla.getDirection(), trace.face(), eye + " -> " + point);
            hits += trace.reached() ? 1 : 0;
        }
        assertTrue(hits > 5000, "射线大多真射中目标,不是只比了没碰上:" + hits);
    }

    @Test
    void clickableMeansAnOutline() {
        FakeWorld world = withTarget().set(0, Y, 3, Blocks.WATER.defaultBlockState());
        assertTrue(Sight.clickable(world, TARGET));
        assertFalse(Sight.clickable(world, new BlockPos(0, Y + 3, 0)), "空气");
        assertFalse(Sight.clickable(world, new BlockPos(0, Y, 3)), "水");
    }
}
