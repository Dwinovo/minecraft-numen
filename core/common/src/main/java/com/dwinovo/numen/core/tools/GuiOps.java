package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 打开的界面:{@code numen.gui.view} 读它、{@code numen.gui.close} 关它,{@code numen.use.block} 的右键打开了一个界面时也交回它
 * ({@link #window})。界面是一个 Window:每个槽一条,方法写在内置模块 {@code numen.gui} 里({@code w:put}、{@code w:take}……)。
 */
public final class GuiOps {

    private static final com.dwinovo.numen.agent.script.ScriptType STRING = com.dwinovo.numen.agent.script.ScriptType.STRING;
    private static final com.dwinovo.numen.agent.script.ScriptType INTEGER = com.dwinovo.numen.agent.script.ScriptType.INTEGER;

    /** 打开的界面:脚本拿到的那张表,带着 {@code numen.gui} 里的方法。 */
    public static final com.dwinovo.numen.agent.script.ScriptType.Class WINDOW =
            new com.dwinovo.numen.agent.script.ScriptType.Class("Window",
                    "The window you have open (your own inventory menu when nothing else is): every container and "
                            + "crafting-grid slot, and your own filled ones.", null, List.of(
                    com.dwinovo.numen.agent.script.ScriptType.field("menu", STRING,
                            "InventoryMenu when it is your own inventory."),
                    com.dwinovo.numen.agent.script.ScriptType.field("slots",
                            com.dwinovo.numen.agent.script.ScriptType.listOf(com.dwinovo.numen.agent.script.ScriptType.table(
                                    com.dwinovo.numen.agent.script.ScriptType.field("index", INTEGER,
                                            "What numen.gui.move and numen.gui.quick take."),
                                    com.dwinovo.numen.agent.script.ScriptType.field("side",
                                            com.dwinovo.numen.agent.script.ScriptType.choice(List.of("container", "you",
                                                    "grid", "result")), null),
                                    com.dwinovo.numen.agent.script.ScriptType.optional("item", STRING,
                                            "Empty slots have none."),
                                    com.dwinovo.numen.agent.script.ScriptType.optional("count", INTEGER, null),
                                    com.dwinovo.numen.agent.script.ScriptType.optional("output",
                                            com.dwinovo.numen.agent.script.ScriptType.BOOLEAN,
                                            "A slot you only take from."))), null),
                    com.dwinovo.numen.agent.script.ScriptType.optional("cursor", STRING, "What the cursor holds."),
                    com.dwinovo.numen.agent.script.ScriptType.field("data",
                            com.dwinovo.numen.agent.script.ScriptType.listOf(INTEGER),
                            "The menu's numbers: progress, fuel, energy (meaning is GUI-specific)."),
                    com.dwinovo.numen.agent.script.ScriptType.optional("block",
                            com.dwinovo.numen.cli.Shapes.BLOCK.type(), "The block whose window it is, when a click on "
                                    + "it opened it."))).methodsIn("numen.gui");

    /**
     * 打开的界面读成两样:给脚本的那张表({@link #WINDOW}),与回执里的那几段话(抬头与合成格的图、每个槽一行、结尾的光标与机器读数)。
     */
    public record View(java.util.Map<String, Object> data, String header, List<String> slots, String footer) {}

    /** 她此刻打开的界面;没开别的就是她自己的背包界面(带 2x2 合成格)。 */
    public static View window(NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        com.google.gson.JsonArray slotData = new com.google.gson.JsonArray();
        // With no block menu open, containerMenu IS your own InventoryMenu — which carries the 2x2
        // crafting grid. Surface it so the model can craft small recipes without a table.
        boolean ownInventory = menu == self.inventoryMenu;
        List<String> container = new ArrayList<>();
        List<String> mine = new ArrayList<>();
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
                slotData.add(slotJson(i, "result", it, true));
                continue;   // shown as part of the crafting-grid section, not the generic dump
            }
            if (slot.container instanceof CraftingContainer cc) {
                slotData.add(slotJson(i, "grid", it, false));
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
            String line = "  " + i + ": " + describe(it) + (output ? " [output]" : "");
            if (playerSide) {
                if (!it.isEmpty()) {
                    mine.add(line);   // only your filled slots — the items you can move in
                    slotData.add(slotJson(i, "you", it, false));
                }
            } else {
                container.add(line);  // all container slots, empty included (placement targets)
                slotData.add(slotJson(i, "container", it, output));
            }
        }
        // Data slots = the menu's OTHER synced channel, parallel to the item slots: the ints a real
        // screen reads to draw progress / fuel / energy bars. Read them generically (no per-menu
        // special-casing) — meaning is GUI-specific, the model/skill interprets (e.g. a furnace's are
        // [litTime, litDuration, cookProgress, cookTotal], so cook% = cookProgress/cookTotal).
        String dataLine = "";
        List<DataSlot> data = ((com.dwinovo.numen.mixin.MenuDataSlotsAccessor) (Object) menu).numen$dataSlots();
        com.google.gson.JsonArray numbers = new com.google.gson.JsonArray();
        data.forEach(d -> numbers.add(d.get()));
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
        // inv recipe overlays cell-for-cell (a smaller recipe goes in the TOP-LEFT — same as here).
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
        List<String> slots = new ArrayList<>(container.isEmpty() ? List.of("  (none)") : container);
        slots.add("your inventory (non-empty):");
        slots.addAll(mine.isEmpty() ? List.of("  (empty)") : mine);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("menu", ownInventory ? "InventoryMenu" : menu.getClass().getSimpleName());
        out.put("slots", slotData);
        if (!menu.getCarried().isEmpty()) {
            out.put("cursor", describe(menu.getCarried()));
        }
        out.put("data", numbers);
        return new View(out, header + gridSection + "container slots:", slots,
                "cursor: " + describe(menu.getCarried()) + "\n"
                        + dataLine
                        + "tip: numen.gui.put(item) and numen.gui.take(item) move items of a kind in or out; "
                        + "numen.gui.quick(slot) sends a whole stack to the other section; numen.gui.move(from, to) "
                        + "(with {count = N} for part of it) puts it into a specific slot.");
    }

    /** {@code numen.gui.view}:读打开的界面,槽多的模组界面按输出预算分页({@link Listing})。 */
    public String inspectGui(NumenPlayer self, CommandArgs args) {
        View view = window(self);
        return new Listing(view.header(), view.slots(), view.footer()).result(args, view.data()).toJson();
    }

    /** 一个槽的那张表。 */
    private static com.google.gson.JsonObject slotJson(int index, String side, ItemStack it, boolean output) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("index", index);
        o.addProperty("side", side);
        if (!it.isEmpty()) {
            o.addProperty("item", BuiltInRegistries.ITEM.getKey(it.getItem()).toString());
            o.addProperty("count", it.getCount());
        }
        if (output) {
            o.addProperty("output", true);
        }
        return o;
    }

    private static String describe(ItemStack stack) {
        return stack.isEmpty()
                ? "-"
                : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath() + " x" + stack.getCount();
    }

    public String closeGui(NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null || menu == self.inventoryMenu) {
            // The InventoryMenu (your own 2x2 grid + inventory) is always open — nothing to close.
            // If you left items in the 2x2 crafting grid, shift them back out.
            return TaskResult.ok("no block GUI was open (your own inventory menu is always available).").toJson();
        }
        self.closeContainer();
        return TaskResult.ok("closed the GUI.").toJson();
    }
}
