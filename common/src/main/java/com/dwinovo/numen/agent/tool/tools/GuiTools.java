package com.dwinovo.numen.agent.tool.tools;

import com.dwinovo.numen.agent.tool.api.NumenAction;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * GUI tools authored on the {@link NumenAction} surface — read the open container
 * menu and close it. Behaviour is identical to the hand-written {@code NumenTool}
 * classes they replace; only the wiring (auto-derived schema, reflective invoke,
 * entity injected by type) changed.
 */
public final class GuiTools {

    @NumenAction(name = "inspect_gui", timeoutTicks = 20, description =
            "Look at the GUI you currently have open. After interact_at right-clicks a chest / "
                    + "furnace / machine it shows that container; with NO container open it shows YOUR own "
                    + "inventory menu (which includes a 2x2 crafting grid), so you can craft small recipes "
                    + "without a table. Lists every slot — index, side, item + count, [output] mark — plus "
                    + "the cursor and any machine progress. If a crafting grid is open it draws the grid as a "
                    + "2D map of slot numbers (handy for hand-loading a modded grid). Use it to choose "
                    + "transfer slot indices and to verify a transfer. No arguments.")
    public String inspectGui(NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null) {
            return TaskResult.fail("no GUI open.").toJson();
        }
        // With no block menu open, containerMenu IS your own InventoryMenu — which carries the 2x2
        // crafting grid. Surface it so the model can craft small recipes without a table.
        boolean ownInventory = menu == self.inventoryMenu;
        StringBuilder container = new StringBuilder();
        StringBuilder mine = new StringBuilder();
        // Crafting grid (if any). Detect generically: a slot backed by a CraftingContainer IS a grid
        // cell (vanilla 2x2/3x3 AND modded NxM), the ResultSlot IS the output. We lay the cells out in
        // 2D with their click-able slot numbers so the model can drop the recipe ascii straight onto it
        // — no "row-major + stride + gaps" arithmetic, which is exactly where it kept misplacing.
        int gridW = 0, gridH = 0, resultIndex = -1;
        Slot[] gridCells = null;   // indexed by position-in-container (row-major)
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            boolean playerSide = slot.container == self.getInventory();
            ItemStack it = slot.getItem();
            if (slot instanceof ResultSlot) {
                resultIndex = slot.index;
                continue;   // shown as part of the crafting-grid section, not the generic dump
            }
            if (slot.container instanceof CraftingContainer cc) {
                if (gridCells == null) {
                    gridW = cc.getWidth();
                    gridH = cc.getHeight();
                    gridCells = new Slot[gridW * gridH];
                }
                int pos = slot.getContainerSlot();
                if (pos >= 0 && pos < gridCells.length) {
                    gridCells[pos] = slot;
                }
                continue;
            }
            // Output-only = a non-empty machine slot that won't take its own item back (result slot).
            boolean output = !playerSide && !it.isEmpty() && !slot.mayPlace(it);
            String line = "  " + i + ": " + describe(it) + (output ? " [output]" : "") + "\n";
            if (playerSide) {
                if (!it.isEmpty()) {
                    mine.append(line);   // only your filled slots — the items you can move in
                }
            } else {
                container.append(line);  // all container slots, empty included (placement targets)
            }
        }
        // Data slots = the menu's OTHER synced channel, parallel to the item slots: the ints a real
        // screen reads to draw progress / fuel / energy bars. Read them generically (no per-menu
        // special-casing) — meaning is GUI-specific, the model/skill interprets (e.g. a furnace's are
        // [litTime, litDuration, cookProgress, cookTotal], so cook% = cookProgress/cookTotal).
        String dataLine = "";
        List<DataSlot> data = ((com.dwinovo.numen.mixin.MenuDataSlotsAccessor) (Object) menu).numen$dataSlots();
        if (!data.isEmpty()) {
            StringBuilder d = new StringBuilder("data values (machine state — progress/fuel/energy/…, "
                    + "meaning is GUI-specific): [");
            for (int i = 0; i < data.size(); i++) {
                if (i > 0) d.append(", ");
                d.append(data.get(i).get());
            }
            dataLine = d.append("]\n").toString();
        }

        // Render the crafting grid as a 2D map of click-able slot numbers, so the recipe ascii from
        // lookup_recipe overlays cell-for-cell (a smaller recipe goes in the TOP-LEFT — same as here).
        String gridSection = "";
        if (gridCells != null) {
            StringBuilder g = new StringBuilder("crafting grid " + gridW + "x" + gridH
                    + " — put each recipe ingredient into the slot at the SAME position (a recipe "
                    + "smaller than the grid goes in the top-left); take the result from slot "
                    + resultIndex + ":\n");
            for (int r = 0; r < gridH; r++) {
                g.append("  ");
                for (int c = 0; c < gridW; c++) {
                    Slot cell = gridCells[r * gridW + c];
                    ItemStack it = cell == null ? ItemStack.EMPTY : cell.getItem();
                    int idx = cell == null ? -1 : cell.index;
                    g.append("slot ").append(idx).append("=").append(describe(it));
                    if (c < gridW - 1) {
                        g.append("  |  ");
                    }
                }
                g.append("\n");
            }
            gridSection = g.toString();
        }

        String header = ownInventory
                ? "GUI: InventoryMenu (YOUR own inventory — includes the 2x2 crafting grid below)\n"
                : "GUI: " + menu.getClass().getSimpleName() + "\n";
        return TaskResult.ok(header
                + gridSection
                + "container slots:\n" + (container.length() == 0 ? "  (none)\n" : container)
                + "your inventory (non-empty):\n" + (mine.length() == 0 ? "  (empty)\n" : mine)
                + "cursor: " + describe(menu.getCarried()) + "\n"
                + dataLine
                + "tip: transfer {from} (no `to`) routes a whole stack to the other section; add `to`"
                + " + `count` for an exact move into a specific slot.").toJson();
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty()
                ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }

    @NumenAction(name = "close_gui", timeoutTicks = 20, description =
            "Close the container GUI you currently have open — do this when you've finished moving "
                    + "items. No arguments.")
    public String closeGui(NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null || menu == self.inventoryMenu) {
            // The InventoryMenu (your own 2x2 grid + inventory) is always open — nothing to close.
            // If you left items in the 2x2 crafting grid, transfer them back out.
            return TaskResult.ok("no block GUI was open (your own inventory menu is always available).").toJson();
        }
        self.closeContainer();
        return TaskResult.ok("closed the GUI.").toJson();
    }
}
