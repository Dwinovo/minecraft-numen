package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 净空:站立(1.8)或潜行(1.5)的身体放不放得下,头顶的方块按真实碰撞箱判。 */
class ClearanceTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 石头地板上,脚在 Y;头顶那一格(Y + 1)摆 {@code overhead}。 */
    private static TestWorld under(BlockState overhead) {
        return new TestWorld().set(0, Y - 1, 0, Blocks.STONE.defaultBlockState()).set(0, Y + 1, 0, overhead);
    }

    private static boolean stands(TestWorld world) {
        return Clearance.fits(world, SURVIVAL, Pose.STANDING, 0, Y, 0);
    }

    private static boolean crouches(TestWorld world) {
        return Clearance.fits(world, SURVIVAL, Pose.CROUCHING, 0, Y, 0);
    }

    @Test
    void aTwoHighGapFitsAStandingBody() {
        TestWorld world = under(Blocks.AIR.defaultBlockState()).set(0, Y + 2, 0, Blocks.STONE.defaultBlockState());
        assertTrue(stands(world));
    }

    @Test
    void aOneAndAHalfHighGapFitsOnlyACrouchingBody() {
        TestWorld world = under(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        assertFalse(stands(world), "站着头顶撞上半砖");
        assertTrue(crouches(world), "潜行 1.5 正好贴着上半砖的底");
    }

    @Test
    void aBodyCannotStandUnderASouthFacingRoofStair() {
        BlockState roof = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(StairBlock.FACING, Direction.SOUTH).setValue(StairBlock.HALF, Half.TOP);
        assertFalse(stands(under(roof)));
    }

    @Test
    void noStairStateLeavesRoomOverheadForAStandingBody() {
        for (BlockState stair : Blocks.OAK_STAIRS.getStateDefinition().getPossibleStates()) {
            assertFalse(stands(under(stair)), "头顶压着 " + stair);
        }
    }

    @Test
    void aBottomSlabOverheadBlocksTheBody() {
        assertFalse(stands(under(Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM))));
    }

    @Test
    void trapdoorsOverheadAreJudgedByTheirRealShape() {
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.defaultBlockState();
        // 关着的上半活板门从 13/16 起,1.8 高的身体头顶到 12.8/16,碰不到
        assertTrue(stands(under(trapdoor.setValue(TrapDoorBlock.HALF, Half.TOP))));
        assertFalse(stands(under(trapdoor.setValue(TrapDoorBlock.HALF, Half.BOTTOM))));
        assertTrue(stands(under(trapdoor.setValue(TrapDoorBlock.HALF, Half.BOTTOM).setValue(TrapDoorBlock.OPEN, true))),
                "开着的活板门贴在格边");
    }

    @Test
    void aFenceBelowReachesHalfABlockIntoTheBody() {
        // 脚所在格下面是栅栏(高 1.5):脚在格底时身体插进栅栏上伸的那半格
        TestWorld world = new TestWorld().set(0, Y - 1, 0, Blocks.OAK_FENCE.defaultBlockState());
        assertFalse(Clearance.fits(world, SURVIVAL, Pose.STANDING, 0, Y, 0));
        assertTrue(Clearance.fits(world, SURVIVAL, Pose.STANDING, 0, Y + 0.5, 0));
    }

    @Test
    void closedGatesBlockAndOpenGatesLetThrough() {
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState();
        TestWorld closed = new TestWorld().set(0, Y - 1, 0, Blocks.STONE.defaultBlockState()).set(0, Y, 0, gate);
        assertFalse(stands(closed));
        TestWorld open = new TestWorld().set(0, Y - 1, 0, Blocks.STONE.defaultBlockState())
                .set(0, Y, 0, gate.setValue(FenceGateBlock.OPEN, true));
        assertTrue(stands(open));
    }

    @Test
    void panesAndBarsTakeTheMiddleOfTheirCell() {
        for (BlockState thin : new BlockState[] {Blocks.GLASS_PANE.defaultBlockState(), Blocks.IRON_BARS.defaultBlockState()}) {
            TestWorld world = new TestWorld().set(0, Y - 1, 0, Blocks.STONE.defaultBlockState()).set(0, Y, 0, thin);
            assertFalse(stands(world), thin + " 占着格子中间");
        }
    }

    @Test
    void aBodyStandsOnACarpetButNotSunkIntoIt() {
        TestWorld carpet = new TestWorld().set(0, Y - 1, 0, Blocks.STONE.defaultBlockState())
                .set(0, Y, 0, Blocks.WHITE_CARPET.defaultBlockState());
        assertTrue(Clearance.fits(carpet, SURVIVAL, Pose.STANDING, 0, Y + 1 / 16.0, 0));
        assertFalse(Clearance.fits(carpet, SURVIVAL, Pose.STANDING, 0, Y, 0), "脚不能陷进地毯里");
    }
}
