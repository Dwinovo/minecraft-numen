package com.dwinovo.numen.core.pathing.spec;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 格子分类与由它派生的判定的钉桩:水面、流水、藤蔓、下半砖、门、栅栏门、避走进的方块,
 * 以及规格的代价表与位置表怎么改变可穿/可站的结论。需要 MC 注册表,无头引导失败时跳过。
 */
@Tag("mc")
class CellClassTest {

    private static final RouteSpec SPEC = RouteSpec.defaults();
    private static final BlockPos P = new BlockPos(0, 64, 0);

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过格子分类钉桩");
    }

    /** Map 后备的世界视图,缺省全是空气。 */
    private static final class FakeView implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        FakeView set(BlockPos p, BlockState s) {
            blocks.put(p.immutable(), s);
            return this;
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    private static BlockState flowingWater() {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3);
    }

    // ==================== 分类 ====================

    @Test
    void classifiesTheVocabulary() {
        assertEquals(CellClass.OPEN, CellClass.of(Blocks.AIR.defaultBlockState()));
        assertEquals(CellClass.OPEN, CellClass.of(Blocks.SHORT_GRASS.defaultBlockState()));
        assertEquals(CellClass.OPEN, CellClass.of(Blocks.TORCH.defaultBlockState()));
        assertEquals(CellClass.GROUND, CellClass.of(Blocks.STONE.defaultBlockState()));
        assertEquals(CellClass.GROUND, CellClass.of(Blocks.GLASS.defaultBlockState()));
        assertEquals(CellClass.GROUND, CellClass.of(Blocks.CHEST.defaultBlockState()));
        assertEquals(CellClass.GROUND, CellClass.of(Blocks.FARMLAND.defaultBlockState()));
        assertEquals(CellClass.GROUND, CellClass.of(Blocks.SOUL_SAND.defaultBlockState()));
        assertEquals(CellClass.GROUND, CellClass.of(Blocks.AZALEA.defaultBlockState()));
        assertEquals(CellClass.GROUND, CellClass.of(Blocks.OAK_LEAVES.defaultBlockState()));
        assertEquals(CellClass.GROUND, CellClass.of(
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)));
        assertEquals(CellClass.GROUND, CellClass.of(
                Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE)));
        assertEquals(CellClass.GROUND, CellClass.of(
                Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 8)));
        assertEquals(CellClass.SNOW_LAYER, CellClass.of(
                Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2)));
        assertEquals(CellClass.STAIRS, CellClass.of(Blocks.OAK_STAIRS.defaultBlockState()));
        assertEquals(CellClass.BOTTOM_SLAB, CellClass.of(Blocks.STONE_SLAB.defaultBlockState()));
        assertEquals(CellClass.CARPET, CellClass.of(Blocks.WHITE_CARPET.defaultBlockState()));
        assertEquals(CellClass.DOOR, CellClass.of(Blocks.OAK_DOOR.defaultBlockState()));
        assertEquals(CellClass.DOOR, CellClass.of(Blocks.OAK_FENCE_GATE.defaultBlockState()));
        assertEquals(CellClass.OBSTACLE, CellClass.of(Blocks.IRON_DOOR.defaultBlockState()));
        assertEquals(CellClass.LADDER, CellClass.of(Blocks.LADDER.defaultBlockState()));
        assertEquals(CellClass.VINE, CellClass.of(Blocks.VINE.defaultBlockState()));
        assertEquals(CellClass.WATER, CellClass.of(Blocks.WATER.defaultBlockState()));
        assertEquals(CellClass.WATER, CellClass.of(Blocks.SEAGRASS.defaultBlockState()));
        assertEquals(CellClass.FLOWING_WATER, CellClass.of(flowingWater()));
        assertEquals(CellClass.LAVA, CellClass.of(Blocks.LAVA.defaultBlockState()));
        assertEquals(CellClass.HAZARD, CellClass.of(Blocks.MAGMA_BLOCK.defaultBlockState()));
        assertEquals(CellClass.HAZARD, CellClass.of(Blocks.CACTUS.defaultBlockState()));
        assertEquals(CellClass.HAZARD, CellClass.of(Blocks.FIRE.defaultBlockState()));
        assertEquals(CellClass.HAZARD, CellClass.of(Blocks.COBWEB.defaultBlockState()));
        assertEquals(CellClass.HAZARD, CellClass.of(Blocks.SWEET_BERRY_BUSH.defaultBlockState()));
        assertEquals(CellClass.HAZARD, CellClass.of(Blocks.BUBBLE_COLUMN.defaultBlockState()));
        assertEquals(CellClass.OBSTACLE, CellClass.of(Blocks.OAK_FENCE.defaultBlockState()));
        assertEquals(CellClass.OBSTACLE, CellClass.of(Blocks.OAK_TRAPDOOR.defaultBlockState()));
        assertEquals(CellClass.OBSTACLE, CellClass.of(Blocks.SHULKER_BOX.defaultBlockState()));
        assertEquals(CellClass.OBSTACLE, CellClass.of(Blocks.CAULDRON.defaultBlockState()));
        assertEquals(CellClass.OBSTACLE, CellClass.of(Blocks.HONEY_BLOCK.defaultBlockState()));
        assertEquals(CellClass.OBSTACLE, CellClass.of(Blocks.POWDER_SNOW.defaultBlockState()));
    }

    @Test
    void waterloggedSolidsStayWhatTheyAre() {
        BlockState stairs = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);
        assertEquals(CellClass.STAIRS, CellClass.of(stairs));
        assertTrue(CellClass.isWater(stairs));
        assertTrue(CellClass.avoidWalkingInto(stairs));
    }

    // ==================== 水 ====================

    @Test
    void stillWaterIsPassableOnlyAtTheSurfaceAndStandableOnlyUnderWater() {
        FakeView v = new FakeView().set(P, Blocks.WATER.defaultBlockState());
        assertTrue(CellClass.canWalkThrough(v, P, SPEC), "水面格可以游过");
        assertFalse(CellClass.canWalkOn(v, P, SPEC), "上面没水就不是浮着的位置");

        v.set(P.above(), Blocks.WATER.defaultBlockState());
        assertFalse(CellClass.canWalkThrough(v, P, SPEC), "上面还有水,穿过去等于潜水");
        assertTrue(CellClass.canWalkOn(v, P, SPEC), "压在水下的水格可以浮在上面");

        v.set(P.above(), Blocks.LILY_PAD.defaultBlockState());
        assertFalse(CellClass.canWalkThrough(v, P, SPEC));
        assertTrue(CellClass.canWalkOn(v, P, SPEC), "睡莲盖着的水面也算");
    }

    @Test
    void flowingWaterIsNeverPassable() {
        FakeView v = new FakeView().set(P, flowingWater());
        assertFalse(CellClass.canWalkThrough(v, P, SPEC));
        assertTrue(CellClass.isFlowing(v, P.getX(), P.getY(), P.getZ(), v.getBlockState(P)));
        // 池边的源方块也在流
        FakeView edge = new FakeView().set(P, Blocks.WATER.defaultBlockState())
                .set(P.east(), flowingWater());
        assertTrue(CellClass.isFlowing(edge, P.getX(), P.getY(), P.getZ(), edge.getBlockState(P)));
        assertFalse(CellClass.canWalkThrough(edge, P, SPEC));
    }

    @Test
    void forbiddingWaterInTheSpecExcludesIt() {
        FakeView v = new FakeView().set(P, Blocks.WATER.defaultBlockState())
                .set(P.above(), Blocks.WATER.defaultBlockState());
        RouteSpec dry = SPEC.withCellCost(CellClass.WATER, RouteSpec.FORBID);
        assertTrue(CellClass.canWalkOn(v, P, SPEC));
        assertFalse(CellClass.canWalkOn(v, P, dry));
        assertFalse(CellClass.canWalkThrough(v, P.above(), dry));
    }

    // ==================== 藤蔓 / 下半砖 / 岩浆 ====================

    @Test
    void vinesArePassableButOnlyClimbableWhenTheSpecSaysSo() {
        FakeView v = new FakeView().set(P, Blocks.VINE.defaultBlockState());
        assertTrue(CellClass.canWalkThrough(v, P, SPEC));
        assertFalse(CellClass.canWalkOn(v, P, SPEC));
        assertTrue(CellClass.canWalkOn(v, P, SPEC.withClimbVines(true)));
    }

    @Test
    void bottomSlabIsAFloorNotAPassage() {
        BlockState slab = Blocks.STONE_SLAB.defaultBlockState();
        FakeView v = new FakeView().set(P, slab);
        assertTrue(CellClass.isBottomSlab(slab));
        assertFalse(CellClass.isBottomSlab(slab.setValue(SlabBlock.TYPE, SlabType.TOP)));
        assertTrue(CellClass.canWalkOn(v, P, SPEC));
        assertFalse(CellClass.canWalkThrough(v, P, SPEC));
        assertFalse(CellClass.canWalkOn(v, P, SPEC.withCellCost(CellClass.BOTTOM_SLAB, RouteSpec.FORBID)));
    }

    @Test
    void lavaIsExcludedUnlessPricedAndStill() {
        FakeView v = new FakeView().set(P, Blocks.LAVA.defaultBlockState());
        assertFalse(CellClass.canWalkThrough(v, P, SPEC));
        assertFalse(CellClass.canWalkOn(v, P, SPEC));
        RouteSpec priced = SPEC.withCellCost(CellClass.LAVA, 50.0);
        assertFalse(CellClass.canWalkThrough(v, P, priced), "岩浆永远不能穿");
        assertTrue(CellClass.canWalkOn(v, P, priced), "静止岩浆在规格给了代价时可当地面");
    }

    // ==================== 门 / 栅栏门 ====================

    @Test
    void doorsAndGatesArePassableCellsIronDoorIsNot() {
        FakeView v = new FakeView().set(P, Blocks.OAK_DOOR.defaultBlockState())
                .set(P.east(), Blocks.OAK_FENCE_GATE.defaultBlockState())
                .set(P.west(), Blocks.IRON_DOOR.defaultBlockState());
        assertTrue(CellClass.canWalkThrough(v, P, SPEC));
        assertTrue(CellClass.canWalkThrough(v, P.east(), SPEC));
        assertFalse(CellClass.canWalkThrough(v, P.west(), SPEC));
        assertFalse(CellClass.canWalkOn(v, P, SPEC));
        assertFalse(CellClass.fullyPassable(v, P), "门要右键才过,不算完全无阻碍");
    }

    @Test
    void doorPassabilityFollowsTheHingeAxis() {
        // 默认朝北:门板轴 Z。沿 Z 接近要开着才过;沿 X 接近反而关着才不挡
        BlockState closed = Blocks.OAK_DOOR.defaultBlockState();
        BlockState open = closed.setValue(DoorBlock.OPEN, true);
        FakeView v = new FakeView().set(P, closed);
        assertFalse(CellClass.isDoorPassable(v, P, P.south()));
        assertTrue(CellClass.isDoorPassable(v, P, P.east()));
        v.set(P, open);
        assertTrue(CellClass.isDoorPassable(v, P, P.south()));
        assertFalse(CellClass.isDoorPassable(v, P, P.east()));
        assertFalse(CellClass.isDoorPassable(v, P, P), "站在门格里没有「走过」这回事");
        assertTrue(CellClass.isDoorPassable(v, P.north(), P), "不是门就不挡");
        assertEquals(Direction.NORTH, closed.getValue(DoorBlock.FACING));
    }

    @Test
    void gatePassabilityIsJustOpen() {
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        FakeView v = new FakeView().set(P, gate);
        assertFalse(CellClass.isGatePassable(v, P, P.south()));
        v.set(P, gate.setValue(FenceGateBlock.OPEN, true));
        assertTrue(CellClass.isGatePassable(v, P, P.south()));
        assertFalse(CellClass.isGatePassable(v, P, P));
    }

    // ==================== 危险 / 薄层 / 完全无阻碍 ====================

    @Test
    void hazardsAndAnyFluidAreAvoided() {
        assertTrue(CellClass.avoidWalkingInto(Blocks.MAGMA_BLOCK.defaultBlockState()));
        assertTrue(CellClass.avoidWalkingInto(Blocks.CACTUS.defaultBlockState()));
        assertTrue(CellClass.avoidWalkingInto(Blocks.FIRE.defaultBlockState()));
        assertTrue(CellClass.avoidWalkingInto(Blocks.COBWEB.defaultBlockState()));
        assertTrue(CellClass.avoidWalkingInto(Blocks.END_PORTAL.defaultBlockState()));
        assertTrue(CellClass.avoidWalkingInto(Blocks.SWEET_BERRY_BUSH.defaultBlockState()));
        assertTrue(CellClass.avoidWalkingInto(Blocks.BUBBLE_COLUMN.defaultBlockState()));
        assertTrue(CellClass.avoidWalkingInto(Blocks.WATER.defaultBlockState()));
        assertTrue(CellClass.avoidWalkingInto(Blocks.LAVA.defaultBlockState()));
        assertFalse(CellClass.avoidWalkingInto(Blocks.STONE.defaultBlockState()));
        assertFalse(CellClass.avoidWalkingInto(Blocks.AIR.defaultBlockState()));
        assertFalse(CellClass.avoidWalkingInto(Blocks.OAK_FENCE.defaultBlockState()));
        FakeView v = new FakeView().set(P, Blocks.CACTUS.defaultBlockState());
        assertFalse(CellClass.canWalkThrough(v, P, SPEC));
        assertFalse(CellClass.canWalkOn(v, P, SPEC));
    }

    @Test
    void carpetAndThinSnowNeedAFloorUnderneath() {
        FakeView v = new FakeView().set(P, Blocks.WHITE_CARPET.defaultBlockState());
        assertFalse(CellClass.canWalkThrough(v, P, SPEC), "地毯下面是空气,走不过");
        v.set(P.below(), Blocks.STONE.defaultBlockState());
        assertTrue(CellClass.canWalkThrough(v, P, SPEC));
        assertFalse(CellClass.canWalkOn(v, P, SPEC));
        assertTrue(CellClass.fullyPassable(v, P), "地毯薄到不碍事");

        v.set(P, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 2));
        assertTrue(CellClass.canWalkThrough(v, P, SPEC));
        v.set(P, Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
        assertFalse(CellClass.canWalkThrough(v, P, SPEC), "三层雪在两格净空里挤不过");
        assertFalse(CellClass.fullyPassable(v, P));
    }

    @Test
    void fullyPassableIsStricterThanPassable() {
        assertTrue(CellClass.fullyPassable(Blocks.AIR.defaultBlockState()));
        assertTrue(CellClass.fullyPassable(Blocks.SHORT_GRASS.defaultBlockState()));
        assertFalse(CellClass.fullyPassable(Blocks.LADDER.defaultBlockState()));
        assertFalse(CellClass.fullyPassable(Blocks.VINE.defaultBlockState()));
        assertFalse(CellClass.fullyPassable(Blocks.WATER.defaultBlockState()));
        assertFalse(CellClass.fullyPassable(Blocks.OAK_STAIRS.defaultBlockState()));
        assertFalse(CellClass.fullyPassable(Blocks.STONE_SLAB.defaultBlockState()));
    }

    // ==================== 按位置 ====================

    @Test
    void positionColumnsGateStandingAndPassing() {
        FakeView v = new FakeView().set(P.below(), Blocks.STONE.defaultBlockState());
        assertTrue(CellClass.canWalkOn(v, P.below(), SPEC));
        assertTrue(CellClass.canWalkThrough(v, P, SPEC));
        RouteSpec pinned = SPEC.withPositions(PositionCosts.builder()
                .stand(P.below().asLong(), RouteSpec.FORBID)
                .pass(P.asLong(), RouteSpec.FORBID).build());
        assertFalse(CellClass.canWalkOn(v, P.below(), pinned));
        assertFalse(CellClass.canWalkThrough(v, P, pinned));
        assertTrue(CellClass.canWalkThrough(v, P.above(), pinned), "只钉了那一格");
    }
}
