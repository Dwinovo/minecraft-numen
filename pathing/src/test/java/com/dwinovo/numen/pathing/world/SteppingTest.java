package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.world.Stepping.Step;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迈步:从一列走进相邻一列,是走过去、要跳还是过不去。场景都摆在 Y - 1 的石头地板上,空地上脚在 Y;
 * 终点节点的脚高一律由 {@link Footing} 给出。
 */
class SteppingTest {

    private static final int Y = 64;
    private static final BlockPos AT = new BlockPos(0, Y, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static TestWorld ground() {
        return new TestWorld().floor(-3, -3, 3, 3, Y - 1);
    }

    /** 从 {@code from} 列、脚在 {@code fromFeet},朝 {@code dir} 走进相邻一列,落在节点 {@code toNode}。 */
    private static Step step(TestWorld world, BlockPos from, double fromFeet, Direction dir, int toNode) {
        int tx = from.getX() + dir.getStepX();
        int tz = from.getZ() + dir.getStepZ();
        double toFeet = Footing.height(world, SURVIVAL, tx, toNode, tz);
        return Stepping.between(world, SURVIVAL, from.getX(), fromFeet, from.getZ(), dir.getStepX(), dir.getStepZ(), toFeet);
    }

    private static BlockState stair(Direction facing) {
        return Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, Half.BOTTOM);
    }

    @Test
    void walkingOntoAStairFromItsFrontNeedsNoJump() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            TestWorld world = ground().set(AT, stair(facing));
            // 朝北的楼梯高的那半在北边:从南面走进来,先上下半层再上上半层,每次半格
            BlockPos front = AT.relative(facing.getOpposite());
            assertEquals(Step.WALK, step(world, front, Y, facing, Y + 1), "朝 " + facing + " 的楼梯从正面走上");
        }
    }

    @Test
    void enteringAStairFromItsBackOrSideNeedsAJump() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            TestWorld world = ground().set(AT, stair(facing));
            BlockPos back = AT.relative(facing);
            assertEquals(Step.JUMP, step(world, back, Y, facing.getOpposite(), Y + 1), "朝 " + facing + " 的楼梯从背面上");
            for (Direction side : new Direction[] {facing.getClockWise(), facing.getCounterClockWise()}) {
                assertEquals(Step.JUMP, step(world, AT.relative(side), Y, side.getOpposite(), Y + 1),
                        "朝 " + facing + " 的楼梯从 " + side + " 侧上");
            }
        }
    }

    @Test
    void walkingDownAStairFromItsTopNeedsNoJump() {
        TestWorld world = ground().set(AT, stair(Direction.NORTH));
        assertEquals(Step.WALK, step(world, AT, Y + 1, Direction.SOUTH, Y));
    }

    @Test
    void aBottomSlabIsWalkedUp() {
        TestWorld world = ground().set(AT, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        assertEquals(Step.WALK, step(world, AT.south(), Y, Direction.NORTH, Y));
    }

    @Test
    void aFullBlockNeedsAJumpAndTwoNeedMore() {
        TestWorld one = ground().set(AT, Blocks.STONE.defaultBlockState());
        assertEquals(Step.JUMP, step(one, AT.south(), Y, Direction.NORTH, Y + 1));
        TestWorld two = ground().set(AT, Blocks.STONE.defaultBlockState()).set(AT.above(), Blocks.STONE.defaultBlockState());
        assertEquals(Step.BLOCKED, step(two, AT.south(), Y, Direction.NORTH, Y + 2));
    }

    @Test
    void fencesAndWallsCannotBeClimbedOrJumped() {
        for (BlockState post : new BlockState[] {Blocks.OAK_FENCE.defaultBlockState(), Blocks.COBBLESTONE_WALL.defaultBlockState()}) {
            TestWorld world = ground().set(AT, post);
            assertEquals(Step.BLOCKED, step(world, AT.south(), Y, Direction.NORTH, Y + 1), post + " 顶上");
        }
    }

    @Test
    void panesAndBarsBlockThePathButTheirTopCanBeJumpedOnto() {
        for (BlockState thin : new BlockState[] {Blocks.GLASS_PANE.defaultBlockState(), Blocks.IRON_BARS.defaultBlockState()}) {
            TestWorld world = ground().set(AT, thin);
            assertEquals(Step.BLOCKED, step(world, AT.south(), Y, Direction.NORTH, Y), thin + " 穿不过去");
            assertEquals(Step.JUMP, step(world, AT.south(), Y, Direction.NORTH, Y + 1), thin + " 顶上跳得上去");
        }
    }

    @Test
    void walkingOffALedgeNeedsNoJump() {
        TestWorld world = ground().set(AT, Blocks.STONE.defaultBlockState());
        assertEquals(Step.WALK, step(world, AT, Y + 1, Direction.SOUTH, Y));
    }

    @Test
    void aJumpUnderALowCeilingBumpsTheHead() {
        BlockPos from = AT.south();
        TestWorld open = ground().set(AT, Blocks.STONE.defaultBlockState());
        assertEquals(Step.JUMP, step(open, from, Y, Direction.NORTH, Y + 1));
        TestWorld low = ground().set(AT, Blocks.STONE.defaultBlockState()).set(from.above(2), Blocks.STONE.defaultBlockState());
        assertEquals(Step.BLOCKED, step(low, from, Y, Direction.NORTH, Y + 1), "起跳时头顶撞上方块");
    }

    @Test
    void honeyHalvesTheJump() {
        BlockPos from = AT.south();
        // 终点是两格高的台,顶面比起点的脚高出一格左右
        TestWorld stone = ground().set(from, Blocks.STONE.defaultBlockState())
                .set(AT, Blocks.STONE.defaultBlockState()).set(AT.above(), Blocks.STONE.defaultBlockState());
        assertEquals(Step.JUMP, step(stone, from, Y + 1, Direction.NORTH, Y + 2));
        TestWorld honey = ground().set(from, Blocks.HONEY_BLOCK.defaultBlockState())
                .set(AT, Blocks.STONE.defaultBlockState()).set(AT.above(), Blocks.STONE.defaultBlockState());
        assertEquals(Step.BLOCKED, step(honey, from, Y + 15 / 16.0, Direction.NORTH, Y + 2), "蜂蜜块上跳不上一格高的台");
    }

    @Test
    void fromSoulSandAFullBlockIsAStepNotAJump() {
        BlockPos from = AT.south();
        TestWorld world = ground().set(from, Blocks.SOUL_SAND.defaultBlockState()).set(AT, Blocks.STONE.defaultBlockState());
        assertEquals(Step.WALK, step(world, from, Y + 14 / 16.0, Direction.NORTH, Y + 1));
    }

    @Test
    void aDiagonalStepCannotCutBetweenTwoCorners() {
        TestWorld open = ground();
        assertEquals(Step.WALK, Stepping.between(open, SURVIVAL, 0, Y, 0, 1, 1, Y));
        TestWorld squeezed = ground();
        for (BlockPos corner : new BlockPos[] {new BlockPos(1, Y, 0), new BlockPos(0, Y, 1)}) {
            squeezed.set(corner, Blocks.STONE.defaultBlockState()).set(corner.above(), Blocks.STONE.defaultBlockState());
        }
        assertEquals(Step.BLOCKED, Stepping.between(squeezed, SURVIVAL, 0, Y, 0, 1, 1, Y));
    }

    /** 在 AT 立一扇朝北的门(上下两半),从南到北穿过门格,两步都能走过去才算穿得过。 */
    private static boolean passesThrough(BlockState lower) {
        TestWorld world = ground().set(AT, lower).set(AT.above(), lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        return step(world, AT.south(), Y, Direction.NORTH, Y) == Step.WALK
                && step(world, AT, Y, Direction.NORTH, Y) == Step.WALK;
    }

    @Test
    void aClosedDoorBlocksAndAnOpenedDoorLetsThrough() {
        BlockState closed = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        assertTrue(!passesThrough(closed), "关着的门挡路");
        assertTrue(passesThrough(Semantics.toggled(closed)), "身体把门打开后穿得过");
    }

    @Test
    void anIronDoorIsPassableExactlyWhenItIsOpen() {
        BlockState closed = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        assertTrue(!passesThrough(closed));
        assertTrue(passesThrough(closed.setValue(DoorBlock.OPEN, true)), "红石开着的铁门照真实状态可过");
    }

    @Test
    void walkingAlongAClosedDoorPanelIsNotBlocked() {
        // 门板贴在格边、与走的方向平行:从东往西横穿门格不碰门板
        BlockState closed = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH);
        TestWorld world = ground().set(AT, closed).set(AT.above(), closed.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        assertEquals(Step.WALK, step(world, AT.east(), Y, Direction.WEST, Y));
        assertEquals(Step.WALK, step(world, AT, Y, Direction.WEST, Y));
    }
}
