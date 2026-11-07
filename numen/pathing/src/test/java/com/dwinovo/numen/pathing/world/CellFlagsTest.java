package com.dwinovo.numen.pathing.world;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 标志字({@link CellFlags})对原版全部方块状态,逐个与精确的几何、语义核对:
 * {@code PASS} 的状态身体站进去放得下、什么语义也没有;{@code WHOLE} 的状态脚踩上去高度正好是它的顶、身体放不进去;
 * 其余几位与语义表一致。标志字说"什么都不用想"的状态,几何上就一定是这样,搜索按标志字做的判断才站得住。
 */
class CellFlagsTest {

    private static final int Y = 64;
    private static final BlockPos AT = new BlockPos(0, Y, 0);
    private static final BodyStats BODY = Vanilla.SURVIVAL;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    @Test
    void everyStateWithPassOrWholeIsExactlyThatInTheGeometry() {
        int pass = 0;
        int whole = 0;
        int total = 0;
        for (BlockState state : Block.BLOCK_STATE_REGISTRY) {
            total++;
            int flags = CellFlags.of(state);
            String name = state.toString();
            assertFalse((flags & CellFlags.PASS) != 0 && (flags & CellFlags.WHOLE) != 0, name);
            assertEquals(0, flags & CellFlags.UNLOADED, name);
            assertEquals(Semantics.wet(state), (flags & CellFlags.WET) != 0, name);
            assertEquals(!state.getFluidState().isEmpty(), (flags & CellFlags.FLUID) != 0, name);
            assertEquals(Semantics.mayClimb(state), (flags & CellFlags.CLIMBISH) != 0, name);
            assertEquals((Semantics.stateMask(state) & (Semantics.bit(Kind.LAVA) | Semantics.bit(Kind.HAZARD))) != 0,
                    (flags & CellFlags.HARMFUL) != 0, name);
            if ((flags & CellFlags.PASS) != 0) {
                pass++;
                // 身体占着这一格(脚和头各一格)照样放得下、站得稳,什么语义也没有
                TestWorld world = new TestWorld().floor(-2, -2, 2, 2, Y - 1).set(AT, state).set(AT.above(), state);
                Stance stance = Stance.at(world, BODY, AT);
                assertTrue(stance != null && stance.grounded() && stance.feetY() == Y && stance.supportY() == Y - 1, name);
                assertEquals(0, Semantics.mask(world, AT), name);
                assertTrue(state.getFluidState().isEmpty(), name);
                assertEquals(1.0, Semantics.speedFactor(world, 0, Y, 0), name);
                assertEquals(1.0, Semantics.jumpFactor(world, 0, Y, 0), name);
            }
            if ((flags & CellFlags.WHOLE) != 0) {
                whole++;
                // 踩上去:脚正好在它的顶上;占着身体的格:放不下
                TestWorld below = new TestWorld().set(AT.below(), state);
                assertEquals((double) Y, Footing.height(below, BODY, 0, Y, 0), name);
                assertEquals(Y - 1, Footing.supportY(below, BODY, 0, Y, 0), name);
                assertFalse(Clearance.fits(new TestWorld().set(AT, state), BODY, 0, Y, 0), name);
                assertFalse(Clearance.fits(new TestWorld().set(AT.above(), state), BODY, 0, Y, 0), name);
                int kinds = Semantics.mask(below, AT.below());
                assertEquals(kinds & ~Semantics.bit(Kind.FALLING), 0, name);
                assertEquals((kinds & Semantics.bit(Kind.FALLING)) != 0, (flags & CellFlags.FALLING) != 0, name);
                assertTrue(state.getFluidState().isEmpty(), name);
                assertEquals(1.0, Semantics.speedFactor(below, 0, Y, 0), name);
                assertEquals(1.0, Semantics.jumpFactor(below, 0, Y, 0), name);
            } else {
                assertEquals(0, flags & CellFlags.FALLING, name);
            }
        }
        // 两类都不是空话:原版有成千上万个状态,空的与整块的各占不少
        assertTrue(total > 20_000, "状态数 " + total);
        assertTrue(pass > 100 && whole > 500, "PASS " + pass + " WHOLE " + whole);
    }

    @Test
    void waterAndTheLikeAreNeverPassOrWhole() {
        for (Block block : new Block[] {Blocks.WATER, Blocks.LAVA, Blocks.LADDER, Blocks.VINE, Blocks.COBWEB, Blocks.FIRE,
                Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.POWDER_SNOW, Blocks.SCAFFOLDING, Blocks.HONEY_BLOCK, Blocks.SOUL_SAND,
                Blocks.OAK_PRESSURE_PLATE, Blocks.FARMLAND, Blocks.BUBBLE_COLUMN, Blocks.SEAGRASS, Blocks.KELP}) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                assertEquals(0, CellFlags.of(state) & (CellFlags.PASS | CellFlags.WHOLE), state.toString());
            }
        }
    }

    @Test
    void airAndStoneAreTheTwoPlainOnes() {
        assertEquals(CellFlags.PASS, CellFlags.of(Blocks.AIR.defaultBlockState()));
        assertEquals(CellFlags.WHOLE, CellFlags.of(Blocks.STONE.defaultBlockState()));
        assertEquals(CellFlags.WHOLE | CellFlags.FALLING, CellFlags.of(Blocks.SAND.defaultBlockState()));
        assertTrue((CellFlags.of(Blocks.WATER.defaultBlockState()) & CellFlags.WET) != 0);
        assertTrue((CellFlags.of(Blocks.LADDER.defaultBlockState()) & CellFlags.CLIMBISH) != 0);
        assertTrue((CellFlags.of(Blocks.LAVA.defaultBlockState()) & CellFlags.HARMFUL) != 0);
    }
}
