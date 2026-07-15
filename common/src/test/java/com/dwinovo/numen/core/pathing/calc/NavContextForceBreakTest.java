package com.dwinovo.numen.core.pathing.calc;

import com.dwinovo.numen.core.pathing.util.ActionCosts;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless tests (no {@code Level}, no bootstrapped game — same style as
 * {@code PlaceGeometryTest}) for {@link NavContext}'s force-break gate:
 * {@code move_to}'s {@code modify_terrain}. The block-and-inventory-dependent
 * decision itself is the pure {@link NavContext#grindVetoed} — a break that
 * would harvest NOTHING (drop-gated block, no adequate tool) is refused unless
 * forced — so the rule is testable without a world; the flag-carriage tests
 * pin the factory defaults ({@code false}: normal breaking).
 */
class NavContextForceBreakTest {

    /** Zero-slot inventory: NavContext's scaffold scan and tool cache never touch a
     *  real ItemStack (no registry bootstrap needed). */
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

    private static final BlockPos ANYWHERE = new BlockPos(3, 64, -7);

    @Test
    void noDropGrindIsVetoedByDefault() {
        // Stone with no pickaxe anywhere in the inventory: drop-gated, not harvestable.
        assertTrue(NavContext.grindVetoed(false, true, false));
    }

    @Test
    void forceAllowsTheNoDropGrind() {
        assertFalse(NavContext.grindVetoed(true, true, false));
    }

    @Test
    void harvestableBreaksAreNeverVetoed() {
        // The right tool is carried — drop-gated or not, the break is a normal dig.
        assertFalse(NavContext.grindVetoed(false, true, true));
        assertFalse(NavContext.grindVetoed(true, true, true));
    }

    @Test
    void nonDropGatedBreaksAreNeverVetoed() {
        // Dirt/logs bare-handed: slow but drops anyway — allowed in both modes.
        assertFalse(NavContext.grindVetoed(false, false, false));
        assertFalse(NavContext.grindVetoed(true, false, false));
    }

    @Test
    void placingNeedsScaffoldRegardlessOfForce() {
        // The no-scaffold veto sits ahead of every world read, so a null Level is safe.
        assertEquals(ActionCosts.COST_INF,
                NavContext.forExecution(null, new EmptyContainer(), false).costOfPlacing(ANYWHERE));
        assertEquals(ActionCosts.COST_INF,
                NavContext.forExecution(null, new EmptyContainer(), true).costOfPlacing(ANYWHERE));
    }

    @Test
    void forceBreakFlagIsCarried() {
        assertTrue(NavContext.forExecution(null, new EmptyContainer(), true).forceBreak);
        assertFalse(NavContext.forExecution(null, new EmptyContainer(), false).forceBreak);
        // The two-arg factory defaults to normal breaking — no forced no-drop grinds.
        assertFalse(NavContext.forExecution(null, new EmptyContainer()).forceBreak);
    }
}
