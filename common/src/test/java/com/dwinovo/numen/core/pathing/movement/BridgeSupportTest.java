package com.dwinovo.numen.core.pathing.movement;

import com.dwinovo.numen.core.pathing.calc.NavContext;
import com.dwinovo.numen.core.pathing.util.ActionCosts;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The plan/execute one-ruler pins for placement support. The water/leaves replan
 * livelock was the two sides judging "can this bridge be placed" with DIFFERENT
 * predicates — the plan's backplace gate said "standable" (water surface, leaf
 * canopy pass), the live maneuver said "placeable-against" (they never pass) —
 * and a deterministic re-search reproduced the impossible plan forever. These
 * tests pin the trichotomy (fluid: never; solid: only if the SHARED predicate
 * accepts the face; air: chain-allowed) and the pillar's fluid-base twin.
 */
@Tag("mc")
class BridgeSupportTest {

    private static boolean booted;

    @BeforeAll
    static void bootMinecraft() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    private static final class FakeView implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(BlockPos p, BlockState s) {
            blocks.put(p.immutable(), s);
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

    private static final class EmptyContainer implements Container {
        @Override public int getContainerSize() { return 0; }
        @Override public boolean isEmpty() { return true; }
        @Override public ItemStack getItem(int slot) { throw new UnsupportedOperationException(); }
        @Override public ItemStack removeItem(int slot, int amount) { throw new UnsupportedOperationException(); }
        @Override public ItemStack removeItemNoUpdate(int slot) { throw new UnsupportedOperationException(); }
        @Override public void setItem(int slot, ItemStack stack) { throw new UnsupportedOperationException(); }
        @Override public void setChanged() { }
        @Override public boolean stillValid(Player player) { return true; }
        @Override public void clearContent() { }
    }

    /** Feet at FROM, bridging one step north: the floor to place is under the dest,
     *  and the world is otherwise empty — no side support exists, so the verdict is
     *  entirely the backplace gate's (the cell under the feet). */
    private static final BlockPos FROM = new BlockPos(0, 64, 0);
    private static final BlockPos FLOOR = new BlockPos(0, 63, -1);   // north = -Z
    private static final BlockPos UNDER_FEET = FROM.below();

    private static double backplaceVerdict(BlockState underFeet) {
        FakeView v = new FakeView();
        if (underFeet != null) {
            v.set(UNDER_FEET, underFeet);
        }
        NavContext ctx = com.dwinovo.numen.core.pathing.calc.TestNavContexts.forView(
                v, new EmptyContainer(), false, LongSets.emptySet());
        return Moves.bridgeSupport(ctx, FROM, FLOOR);
    }

    @Test
    void bridgeFromWaterSurfaceIsImpossible() {
        assumeBooted();
        // The exact water-livelock geometry: body at the surface, water under the
        // feet, open water ahead. The plan must refuse what the maneuver can
        // never click.
        assertTrue(backplaceVerdict(Blocks.WATER.defaultBlockState()) >= ActionCosts.COST_INF);
    }

    @Test
    void bridgeFromSolidGroundBackplacesAtSneakCost() {
        assumeBooted();
        double mult = backplaceVerdict(Blocks.STONE.defaultBlockState());
        assertTrue(mult < ActionCosts.COST_INF, "stone backplace must stay possible");
        assertEquals(ActionCosts.SNEAK_ONE_BLOCK / ActionCosts.WALK_ONE_BLOCK, mult, 1e-9);
    }

    @Test
    void bridgeOverAirChains() {
        assumeBooted();
        // The cell under the feet is the block the PREVIOUS bridge step places —
        // invisible to the static snapshot. The chain must stay priced.
        assertTrue(backplaceVerdict(null) < ActionCosts.COST_INF);
    }

    @Test
    void leavesVerdictMatchesTheSharedPredicate() {
        assumeBooted();
        // One ruler: whatever leaves ARE (sturdy or not), the plan's verdict must
        // equal the shared placeability predicate the live maneuver consults —
        // the two sides may never disagree again.
        FakeView v = new FakeView();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        v.set(UNDER_FEET, leaves);
        boolean placeable = BlockHelper.canPlaceAgainst(v, UNDER_FEET, Direction.NORTH);
        double verdict = backplaceVerdict(leaves);
        assertEquals(placeable, verdict < ActionCosts.COST_INF,
                "plan (" + verdict + ") and shared predicate (" + placeable
                        + ") disagree on leaves");
    }

    @Test
    void pillarBaseFluidTwin() {
        assumeBooted();
        FakeView v = new FakeView();
        v.set(UNDER_FEET, Blocks.WATER.defaultBlockState());
        assertTrue(Moves.pillarBaseIsFluid(v, FROM), "water below the feet vetoes the pillar");
        FakeView solid = new FakeView();
        solid.set(UNDER_FEET, Blocks.STONE.defaultBlockState());
        assertFalse(Moves.pillarBaseIsFluid(solid, FROM), "solid base pillars normally");
        assertFalse(Moves.pillarBaseIsFluid(new FakeView(), FROM),
                "air base is the vertical chain — allowed");
    }

    private static void assumeBooted() {
        org.junit.jupiter.api.Assumptions.assumeTrue(booted,
                "Minecraft bootstrap unavailable — covered nowhere else; do not delete silently");
    }
}
