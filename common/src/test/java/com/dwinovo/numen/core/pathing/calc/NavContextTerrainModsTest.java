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
 * {@code PlaceGeometryTest}) for {@link NavContext}'s terrain-modification gate:
 * {@code terrainMods == false} must price EVERY break and place
 * {@link ActionCosts#COST_INF}, unconditionally and before any world read —
 * that is what turns the A* graph into a pure-traversal graph (no dig moves,
 * no bridge/pillar moves are ever generated), i.e. {@code move_to}'s
 * {@code modify_terrain:false}.
 *
 * <p>The gates sit ahead of every {@code view} access, so a {@code null} level
 * never gets dereferenced — which is exactly the property under test: the veto
 * must not depend on what the world contains.
 */
class NavContextTerrainModsTest {

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

    private static NavContext noTerrainMods() {
        return NavContext.forExecution(null, new EmptyContainer(), false);
    }

    @Test
    void terrainModsFalseVetoesEveryPlace() {
        assertEquals(ActionCosts.COST_INF, noTerrainMods().costOfPlacing(ANYWHERE));
    }

    @Test
    void terrainModsFalseVetoesEveryBreak() {
        NavContext ctx = noTerrainMods();
        assertEquals(ActionCosts.COST_INF, ctx.costOfBreaking(ANYWHERE));
        assertEquals(ActionCosts.COST_INF, ctx.costOfBreaking(ANYWHERE, true));
        assertEquals(ActionCosts.COST_INF, ctx.costOfBreaking(ANYWHERE, false));
    }

    @Test
    void terrainModsFlagIsCarried() {
        assertFalse(noTerrainMods().terrainMods);
        // The two-arg factory keeps the established default: modification allowed.
        assertTrue(NavContext.forExecution(null, new EmptyContainer()).terrainMods);
    }
}
