package com.dwinovo.numen.core.pathing.calc;

import com.dwinovo.numen.core.pathing.util.ActionCosts;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests (no {@code Level}, no bootstrapped game — same style as
 * {@link NavContextForceBreakTest}) for the {@link NavContext#sacred} set: the
 * navigation's OWN objective (the crafting table it travels to use, the ore its
 * task will mine) may be neither broken nor buried by the route itself. Both
 * vetoes sit ahead of every world read, so a null Level exercises them.
 */
class NavContextSacredTest {

    /** Zero-slot inventory (see {@link NavContextForceBreakTest}). */
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

    private static final BlockPos TARGET = new BlockPos(10, 64, -3);

    private static NavContext withSacred(boolean forceBreak, BlockPos... cells) {
        LongSet sacred = new LongOpenHashSet();
        for (BlockPos c : cells) {
            sacred.add(c.asLong());
        }
        return NavContext.forExecution(null, new EmptyContainer(), forceBreak, sacred);
    }

    @Test
    void sacredCellCannotBeBroken() {
        assertEquals(ActionCosts.COST_INF,
                withSacred(false, TARGET).costOfBreaking(TARGET));
    }

    @Test
    void forceBreakDoesNotPierceSacred() {
        // modify_terrain widens what may be ground through, never what may be griefed.
        assertEquals(ActionCosts.COST_INF,
                withSacred(true, TARGET).costOfBreaking(TARGET));
        assertEquals(ActionCosts.COST_INF,
                withSacred(true, TARGET).costOfBreaking(TARGET, true));
    }

    @Test
    void sacredCellCannotBePlacedInto() {
        assertEquals(ActionCosts.COST_INF,
                withSacred(false, TARGET).costOfPlacing(TARGET));
    }

    @Test
    void cellAboveSacredCannotBeCapped() {
        // Placing on top of the target buries its use face — burying is consuming.
        assertEquals(ActionCosts.COST_INF,
                withSacred(false, TARGET).costOfPlacing(TARGET.above()));
    }

    @Test
    void legacyFactoriesCarryAnEmptySacredSet() {
        assertTrue(NavContext.forExecution(null, new EmptyContainer()).sacred.isEmpty());
        assertTrue(NavContext.forExecution(null, new EmptyContainer(), true).sacred.isEmpty());
    }

    @Test
    void deniedPlaceCellCannotBePlacedInto() {
        // Executor-proven NO_SUPPORT feedback: the next search may not plan the same
        // impossible scaffold (the rim-standing replan loop breaker).
        LongSet denied = new LongOpenHashSet();
        denied.add(TARGET.asLong());
        NavContext ctx = NavContext.forExecution(null, new EmptyContainer(), false,
                LongSets.emptySet(), denied);
        assertEquals(ActionCosts.COST_INF, ctx.costOfPlacing(TARGET));
    }

    @Test
    void legacyFactoriesCarryAnEmptyDeniedSet() {
        assertTrue(NavContext.forExecution(null, new EmptyContainer(), true).deniedPlace.isEmpty());
        assertTrue(NavContext.forExecution(null, new EmptyContainer(), true,
                LongSets.emptySet()).deniedPlace.isEmpty());
    }
}
