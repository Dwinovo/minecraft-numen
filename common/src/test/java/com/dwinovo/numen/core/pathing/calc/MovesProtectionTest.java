package com.dwinovo.numen.core.pathing.calc;

import com.dwinovo.numen.core.pathing.movement.Movement;
import com.dwinovo.numen.core.pathing.movement.Moves;
import com.dwinovo.numen.core.pathing.util.ActionCosts;
import com.dwinovo.numen.core.pathing.util.BlockEntityAware;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end protection pins through the REAL edge generator: {@code Moves.generate}
 * over fake terrain must never price a movement that breaks a protected block
 * (block-entity proxy — a chest) or a sacred cell as anything but
 * {@link ActionCosts#COST_INF}, while the identical unprotected terrain stays
 * routable. Needs Minecraft's registries, so it bootstraps the game headlessly;
 * if that is unavailable in an environment the tests SKIP (assumption), never
 * fail — coverage then rests on the pure-layer tests
 * ({@link NavContextSacredTest}, {@code GoalCompilerTest}).
 */
@Tag("mc")
class MovesProtectionTest {

    private static boolean booted;

    @BeforeAll
    static void bootMinecraft() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;   // environment can't bootstrap — tests skip, not fail
        }
    }

    /** Map-backed world view; answers block-entity presence itself so the
     *  protection check never needs a live level. */
    private static final class FakeView implements BlockGetter, BlockEntityAware {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> blockEntities = new HashSet<>();

        void set(BlockPos p, BlockState s) {
            blocks.put(p.immutable(), s);
        }

        void setChest(BlockPos p) {
            set(p, Blocks.CHEST.defaultBlockState());
            blockEntities.add(p.immutable());
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public boolean hasBlockEntity(BlockPos pos) { return blockEntities.contains(pos); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    /** Zero-slot inventory (same as {@link NavContextForceBreakTest}). */
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

    private static final BlockPos SRC = new BlockPos(0, 64, 0);

    /** Flat stone floor under a 3×3 area around SRC so every lateral move has ground. */
    private static FakeView floored() {
        FakeView v = new FakeView();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                v.set(new BlockPos(SRC.getX() + dx, 63, SRC.getZ() + dz),
                        Blocks.STONE.defaultBlockState());
            }
        }
        return v;
    }

    private static List<Movement> generate(FakeView view, LongSet sacred) {
        return Moves.generate(NavContext.forView(view, new EmptyContainer(), false, sacred), SRC);
    }

    @Test
    void noFiniteMovementBreaksAChest() {
        assumeTrue(booted, "Minecraft bootstrap unavailable — covered by pure-layer tests");
        FakeView v = floored();
        BlockPos chest = SRC.north();          // feet-height obstruction on the north traverse
        v.setChest(chest);
        for (Movement m : generate(v, LongSets.emptySet())) {
            if (m.toBreak.contains(chest)) {
                assertTrue(m.cost >= ActionCosts.COST_INF,
                        m.kind + " prices a chest break as walkable: " + m.cost);
            }
        }
    }

    @Test
    void sacredCellTurnsARoutableBreakInfinite() {
        assumeTrue(booted, "Minecraft bootstrap unavailable — covered by pure-layer tests");
        BlockPos dirt = SRC.north();           // bare-hand-harvestable obstruction

        // Unprotected: the north traverse through dirt must be finitely priced —
        // this pins that the sacred veto (below) is what flips it, not something else.
        FakeView open = floored();
        open.set(dirt, Blocks.DIRT.defaultBlockState());
        boolean finiteDirtBreakExists = generate(open, LongSets.emptySet()).stream()
                .anyMatch(m -> m.toBreak.contains(dirt) && m.cost < ActionCosts.COST_INF);
        assertTrue(finiteDirtBreakExists, "dirt obstruction should be routable when unprotected");

        // Sacred: the very same terrain, with the dirt cell marked as the
        // navigation's own objective — every movement breaking it prices INF.
        FakeView protectedView = floored();
        protectedView.set(dirt, Blocks.DIRT.defaultBlockState());
        LongSet sacred = new LongOpenHashSet();
        sacred.add(dirt.asLong());
        for (Movement m : generate(protectedView, sacred)) {
            if (m.toBreak.contains(dirt)) {
                assertTrue(m.cost >= ActionCosts.COST_INF,
                        m.kind + " prices a sacred-cell break as walkable: " + m.cost);
            }
        }
    }
}
