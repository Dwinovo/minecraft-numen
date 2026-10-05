package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 落脚:脚落在多高、踩的是哪一格、脚的高度归到哪一格。预期值是原版碰撞箱的顶面。 */
class FootingTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 在 (0, Y, 0) 摆一块方块,下面垫一块石头。 */
    private static TestWorld on(BlockState state) {
        return new TestWorld().set(0, Y - 1, 0, Blocks.STONE.defaultBlockState()).set(0, Y, 0, state);
    }

    private static double height(TestWorld world, int node) {
        return Footing.height(world, SURVIVAL, 0, node, 0);
    }

    /** 这块方块托住脚的地方在它自己的格里:高度是 Y + top,节点是 Y,踩的就是它。 */
    private static void standsInsideOwnCell(BlockState state, double top) {
        TestWorld world = on(state);
        assertEquals(Y + top, height(world, Y), 1e-9, state + " 上的脚高");
        assertEquals(Y, Footing.supportY(world, SURVIVAL, 0, Y, 0), state + " 上踩的格");
        assertTrue(Double.isNaN(height(world, Y + 1)), state + " 上面一格托不住脚");
    }

    /** 这块方块把脚托进上面一格:节点是 Y + 1,高度 Y + top。 */
    private static void liftsIntoCellAbove(BlockState state, double top) {
        TestWorld world = on(state);
        assertEquals(Y + top, height(world, Y + 1), 1e-9, state + " 上的脚高");
        assertEquals(Y, Footing.supportY(world, SURVIVAL, 0, Y + 1, 0), state + " 上踩的格");
    }

    @Test
    void standingOnAFullBlockPutsTheFeetInTheCellAbove() {
        TestWorld world = new TestWorld().set(0, Y, 0, Blocks.STONE.defaultBlockState());
        assertEquals(Y + 1.0, height(world, Y + 1), 1e-9);
        assertEquals(Y, Footing.supportY(world, SURVIVAL, 0, Y + 1, 0));
        assertEquals(Y + 1, Footing.cellOf(Y + 1.0));
    }

    @Test
    void onSoulSandTheFeetAreInTheSoulSandCell() {
        standsInsideOwnCell(Blocks.SOUL_SAND.defaultBlockState(), 14 / 16.0);
    }

    @Test
    void onFarmlandAndDirtPathTheFeetAreInTheirOwnCell() {
        standsInsideOwnCell(Blocks.FARMLAND.defaultBlockState(), 15 / 16.0);
        standsInsideOwnCell(Blocks.DIRT_PATH.defaultBlockState(), 15 / 16.0);
    }

    @Test
    void onABottomSlabTheFeetAreHalfwayUpItsCell() {
        standsInsideOwnCell(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM), 0.5);
    }

    @Test
    void topAndDoubleSlabsStandLikeFullBlocks() {
        liftsIntoCellAbove(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP), 1.0);
        liftsIntoCellAbove(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.DOUBLE), 1.0);
    }

    @Test
    void everyStairStateStandsLikeAFullBlock() {
        for (BlockState stair : Blocks.OAK_STAIRS.getStateDefinition().getPossibleStates()) {
            liftsIntoCellAbove(stair, 1.0);
        }
    }

    @Test
    void theLowStepInsideABottomStairCannotBeStoodIn() {
        // 楼梯格里下半层托得住脚(0.5),但上半层卡着身体:站不进楼梯格,只能站在楼梯顶上
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            BlockState stair = Blocks.OAK_STAIRS.defaultBlockState()
                    .setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
            TestWorld world = on(stair);
            assertEquals(Y + 0.5, height(world, Y), 1e-9);
            assertFalse(Clearance.fits(world, SURVIVAL, Pose.STANDING, 0, Y + 0.5, 0), "楼梯格里站不进 " + facing);
        }
    }

    @Test
    void snowLayersRaiseTheFeetByTheirCollisionHeight() {
        // 一层雪没有碰撞箱:脚踩在下面的石头上,落在雪那一格的底
        TestWorld one = on(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 1));
        assertEquals(Y, height(one, Y), 1e-9);
        assertEquals(Y - 1, Footing.supportY(one, SURVIVAL, 0, Y, 0));
        for (int layers = 2; layers <= 8; layers++) {
            standsInsideOwnCell(Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers),
                    (layers - 1) * 2 / 16.0);
        }
    }

    @Test
    void aCarpetRaisesTheFeetBySixteenth() {
        standsInsideOwnCell(Blocks.WHITE_CARPET.defaultBlockState(), 1 / 16.0);
    }

    @Test
    void fencesAndWallsHoldTheFeetHalfABlockIntoTheCellAbove() {
        liftsIntoCellAbove(Blocks.OAK_FENCE.defaultBlockState(), 1.5);
        liftsIntoCellAbove(Blocks.COBBLESTONE_WALL.defaultBlockState(), 1.5);
    }

    @Test
    void lowFurnitureCanBeStoodOn() {
        standsInsideOwnCell(Blocks.CHEST.defaultBlockState(), 14 / 16.0);
        standsInsideOwnCell(Blocks.ENCHANTING_TABLE.defaultBlockState(), 12 / 16.0);
        standsInsideOwnCell(Blocks.RED_BED.defaultBlockState(), 9 / 16.0);
        standsInsideOwnCell(Blocks.LANTERN.defaultBlockState(), 9 / 16.0);
        standsInsideOwnCell(Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true), 10 / 16.0);
        standsInsideOwnCell(Blocks.HONEY_BLOCK.defaultBlockState(), 15 / 16.0);
    }

    @Test
    void theBodyStandsInsideACauldronOnItsFloor() {
        TestWorld world = on(Blocks.CAULDRON.defaultBlockState());
        assertEquals(Y + 4 / 16.0, height(world, Y), 1e-9);
        assertTrue(Clearance.fits(world, SURVIVAL, Pose.STANDING, 0, Y + 4 / 16.0, 0), "身体比炼药锅的内膛窄,站得进去");
    }

    @Test
    void trapdoorsStandByTheirRealShape() {
        BlockState bottom = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.BOTTOM);
        standsInsideOwnCell(bottom, 3 / 16.0);
        liftsIntoCellAbove(bottom.setValue(TrapDoorBlock.HALF, Half.TOP), 1.0);
        // 开着的活板门贴在格边,脚底碰不到:脚落在下面的石头上
        TestWorld open = on(bottom.setValue(TrapDoorBlock.OPEN, true));
        assertEquals(Y, height(open, Y), 1e-9);
        assertTrue(Clearance.fits(open, SURVIVAL, Pose.STANDING, 0, Y, 0));
    }

    @Test
    void doorsLadderAndVinesDoNotHoldTheFeetAtTheCentre() {
        for (BlockState thin : new BlockState[] {Blocks.OAK_DOOR.defaultBlockState(), Blocks.LADDER.defaultBlockState(),
                Blocks.VINE.defaultBlockState()}) {
            TestWorld world = on(thin);
            assertEquals(Y, height(world, Y), 1e-9, thin + ":脚落在下面的石头上");
            assertTrue(Clearance.fits(world, SURVIVAL, Pose.STANDING, 0, Y, 0), thin + " 那一格身体放得下");
        }
    }

    @Test
    void scaffoldingHoldsTheBodyOnTopButLetsItThroughInside() {
        TestWorld world = on(Blocks.SCAFFOLDING.defaultBlockState());
        assertEquals(Y + 1.0, height(world, Y + 1), 1e-9, "站在脚手架顶上");
        assertEquals(Y, height(world, Y), 1e-9, "在脚手架里,脚踩在下面的石头上");
        assertTrue(Clearance.fits(world, SURVIVAL, Pose.STANDING, 0, Y, 0), "脚手架里身体放得下");
    }

    @Test
    void powderSnowDoesNotHoldTheBody() {
        TestWorld world = on(Blocks.POWDER_SNOW.defaultBlockState());
        assertTrue(Double.isNaN(height(world, Y + 1)), "细雪顶上托不住");
        assertEquals(Y, height(world, Y), 1e-9, "陷进细雪,踩在下面的石头上");
    }

    @Test
    void anEmptyColumnHoldsNothing() {
        TestWorld world = new TestWorld();
        assertTrue(Double.isNaN(height(world, Y)));
        assertEquals(Integer.MIN_VALUE, Footing.supportY(world, SURVIVAL, 0, Y, 0));
    }

    @Test
    void feetJustBelowACellBoundaryBelongToTheCellAbove() {
        assertEquals(Y + 1, Footing.cellOf(Y + 1 - 1e-7));
        assertEquals(Y, Footing.cellOf(Y + 0.999));
        assertEquals(Y, Footing.cellOf(Y));
    }
}
