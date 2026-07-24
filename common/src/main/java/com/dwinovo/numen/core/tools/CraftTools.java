package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.core.task.PlayerInv;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The {@code craft} tool: the whole craft flow in one call — pick a recipe whose
 * materials the inventory can feed, lay the ingredients into a REAL crafting grid
 * via menu clicks, and shift-take the result. Everything runs through the vanilla
 * container path ({@code menu.clicked} on a live {@code CraftingMenu} /
 * {@code InventoryMenu}), so recipe-unlock, stats, ingredient remainders (bucket
 * back from milk) and container-observing mods all see a normal player crafting —
 * items never appear out of thin air.
 *
 * <p>Grid choice: an already-open grid that fits &gt; the body's own 2x2 &gt; a
 * crafting table within reach (right-clicked open, closed after). No table in
 * reach is a refusal with the nearest table's coordinates (or "place one") — going
 * there is the planner's move, not this tool's.
 */
public final class CraftTools {

    /** Eye-to-block-center reach for using a crafting table without walking. */
    private static final double REACH = 4.5;
    /** "Where IS one" hint scan when no table is in reach (horizontal / vertical). */
    private static final int HINT_H = 16, HINT_V = 6;
    /** Rounds of fill-grid + shift-take; each round crafts up to a full stack per cell. */
    private static final int MAX_ROUNDS = 16;

    /** A crafting recipe candidate with its (input-independent) output count. */
    private record Cand(CraftingRecipe recipe, int outCount) {}

    /** One grid cell to fill: row-major position in the target grid + what goes there. */
    private record Placement(int gridPos, Ingredient ing) {}

    /** The clickable geometry of an open crafting surface. */
    private record Grid(int w, int h, int[] cells, int result) {}

    public String craft(String item_id, Integer count, NumenPlayer self) {
        Item target = ToolArgs.parseItem(item_id);
        int want = count == null ? 1 : Math.clamp(count, 1, 256);
        if (!(self.level() instanceof ServerLevel level)) {
            return TaskResult.fail("crafting needs a server level.").toJson();
        }
        String name = BuiltInRegistries.ITEM.getKey(target).getPath();

        List<Cand> candidates = candidatesFor(level, target);
        if (candidates.isEmpty()) {
            return TaskResult.fail("no crafting recipe makes " + name + " — check lookup_recipe: it may "
                    + "be smelted, stonecut, smithed, mined or traded instead.").toJson();
        }

        // Reclaim anything stranded in an already-open grid before counting materials.
        Grid pre = findGrid(self.containerMenu);
        if (pre != null) {
            sweepGrid(self.containerMenu, self, pre);
        }

        // Materials gate: keep recipes the inventory can feed at least once; remember the
        // closest miss for the refusal message.
        Map<Item, Integer> pool = poolOf(self.containerMenu, self);
        List<Cand> satisfiable = new ArrayList<>();
        List<String> bestMissing = null;
        for (Cand c : candidates) {
            List<Ingredient> ings = ingredientsOf(c.recipe());
            if (feasibleBatch(ings, pool, 1) == 1) {
                satisfiable.add(c);
            } else {
                List<String> missing = missingFor(ings, pool);
                if (bestMissing == null || missing.size() < bestMissing.size()) {
                    bestMissing = missing;
                }
            }
        }
        if (satisfiable.isEmpty()) {
            return TaskResult.fail("not enough materials for " + name + " — missing: "
                    + String.join(", ", bestMissing)
                    + ". Collect or craft those first, then craft again.").toJson();
        }

        // Pick the crafting surface: the open grid if a satisfiable recipe fits it, else the
        // body's own 2x2, else a crafting table within reach.
        AbstractContainerMenu menu = null;
        Grid grid = null;
        Cand chosen = null;
        boolean openedTable = false;
        String station = null;

        Grid cur = findGrid(self.containerMenu);
        if (cur != null) {
            for (Cand c : satisfiable) {
                if (fits(c.recipe(), cur.w(), cur.h())) {
                    menu = self.containerMenu;
                    grid = cur;
                    chosen = c;
                    station = (menu == self.inventoryMenu)
                            ? "Used your own 2x2 grid." : "Used the already-open grid.";
                    break;
                }
            }
        }
        if (chosen == null) {
            for (Cand c : satisfiable) {
                if (fits(c.recipe(), 2, 2)) {
                    chosen = c;
                    break;
                }
            }
            if (chosen != null) {
                if (self.containerMenu != self.inventoryMenu) {
                    self.closeContainer();   // a gridless GUI (chest, furnace) was open — put it away
                }
                menu = self.inventoryMenu;
                grid = findGrid(menu);
                station = "Used your own 2x2 grid.";
            }
        }
        if (chosen == null) {
            for (Cand c : satisfiable) {
                if (fits(c.recipe(), 3, 3)) {
                    chosen = c;
                    break;
                }
            }
            if (chosen == null) {
                return TaskResult.fail(name + "'s recipe needs a grid larger than 3x3 (modded station) — "
                        + "interact_at that station and use inspect_gui + transfer instead.").toJson();
            }
            BlockPos table = nearestTable(level, self, (int) Math.ceil(REACH), 3, REACH);
            if (table == null) {
                BlockPos hintPos = nearestTable(level, self, HINT_H, HINT_V, Double.MAX_VALUE);
                return TaskResult.fail(name + " is a 3x3 recipe — it needs a crafting table within reach "
                        + "(~4 blocks). " + (hintPos != null
                                ? "Nearest one is at " + hintPos.getX() + "," + hintPos.getY() + ","
                                        + hintPos.getZ() + " — goto it, then craft again."
                                : "None within " + HINT_H + " blocks — craft a crafting_table (4 planks, "
                                        + "fits your own 2x2) and place_block it, then craft again.")).toJson();
            }
            InputDriver.lookAt(self, Vec3.atCenterOf(table));
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(table), Direction.UP, table, false);
            self.gameMode.useItemOn(self, level, self.getItemInHand(InteractionHand.MAIN_HAND),
                    InteractionHand.MAIN_HAND, hit);
            Grid opened = findGrid(self.containerMenu);
            if (self.containerMenu == self.inventoryMenu || opened == null
                    || !fits(chosen.recipe(), opened.w(), opened.h())) {
                return TaskResult.fail("right-clicked the crafting table at " + table.getX() + ","
                        + table.getY() + "," + table.getZ()
                        + " but no crafting menu opened (blocked, or another mod overrides it).").toJson();
            }
            self.swing(InteractionHand.MAIN_HAND);
            menu = self.containerMenu;
            grid = opened;
            openedTable = true;
            station = "Used the crafting table at " + table.getX() + "," + table.getY() + ","
                    + table.getZ() + ".";
        }

        try {
            return doCraft(menu, grid, chosen, target, want, name, station, self);
        } finally {
            sweepGrid(menu, self, grid);
            if (openedTable) {
                self.closeContainer();
            }
        }
    }

    // ---- the fill / take loop ----

    private static String doCraft(AbstractContainerMenu menu, Grid grid, Cand chosen, Item target,
                                  int want, String name, String station, NumenPlayer self) {
        if (!settleCarried(menu, self)) {
            return TaskResult.fail("the cursor is holding items and no inventory slot is free to put "
                    + "them down — free a slot first (drop_items).").toJson();
        }
        Map<Item, Integer> before = poolOf(menu, self);
        List<Ingredient> ings = ingredientsOf(chosen.recipe());
        int output = Math.max(1, chosen.outCount());
        int crafted = 0;
        String stopped = null;

        for (int round = 0; round < MAX_ROUNDS && crafted < want; round++) {
            int craftsLeft = Math.ceilDiv(want - crafted, output);
            int batch = feasibleBatch(ings, poolOf(menu, self), Math.min(craftsLeft, 64));
            if (batch <= 0) {
                stopped = "ran out of materials";
                break;
            }
            // Lay out this batch: per cell, the matching inventory item with the deepest supply.
            Map<Item, Integer> sim = new HashMap<>(poolOf(menu, self));
            boolean laidOut = true;
            for (Placement pl : placements(chosen.recipe(), grid.w())) {
                Item pick = pickItem(pl.ing(), sim);
                int cellIdx = pick == null ? -1 : grid.cells()[pl.gridPos()];
                if (cellIdx < 0 || placeIntoCell(menu, self, cellIdx, pick, pl.ing(), batch) < batch) {
                    laidOut = false;
                    break;
                }
                sim.merge(pick, -batch, Integer::sum);
            }
            if (!laidOut) {
                sweepGrid(menu, self, grid);
                stopped = "couldn't lay out the grid (materials changed mid-craft?)";
                break;
            }
            if (menu.slots.get(grid.result()).getItem().isEmpty()) {
                sweepGrid(menu, self, grid);
                stopped = "the laid-out grid doesn't form this recipe (unexpected — mod interference?)";
                break;
            }
            int have0 = PlayerInv.count(self.getInventory(), target);
            menu.clicked(grid.result(), 0, ClickType.QUICK_MOVE, self);   // vanilla mass-craft + onTake
            self.swing(InteractionHand.MAIN_HAND);
            sweepGrid(menu, self, grid);
            int gained = PlayerInv.count(self.getInventory(), target) - have0;
            if (gained <= 0) {
                stopped = "inventory is full — the result doesn't fit";
                break;
            }
            crafted += gained;
        }

        if (crafted <= 0) {
            return TaskResult.fail("crafted nothing — "
                    + (stopped == null ? "unknown reason" : stopped) + ".").toJson();
        }

        // Report material flow as inventory deltas (covers remainders like buckets coming back).
        Map<Item, Integer> after = poolOf(menu, self);
        List<String> used = new ArrayList<>();
        List<String> back = new ArrayList<>();
        for (Item item : new TreeSet<>(union(before, after))) {
            int delta = after.getOrDefault(item, 0) - before.getOrDefault(item, 0);
            String path = BuiltInRegistries.ITEM.getKey(item).getPath();
            if (delta < 0) {
                used.add((-delta) + "x " + path);
            } else if (delta > 0 && item != target) {
                back.add(delta + "x " + path);
            }
        }

        int carrying = PlayerInv.count(self.getInventory(), target);
        StringBuilder msg = new StringBuilder("crafted " + crafted + "x " + name);
        if (crafted < want) {
            msg.append(" (wanted ").append(want).append(" — stopped: ").append(stopped).append(")");
        }
        if (!used.isEmpty()) {
            msg.append(" — used ").append(String.join(", ", used));
        }
        if (!back.isEmpty()) {
            msg.append("; got back ").append(String.join(", ", back));
        }
        msg.append(". ").append(station).append(" Now carrying ").append(carrying).append("x ")
                .append(name).append(".");
        return TaskResult.ok(msg.toString(), Map.of("crafted", crafted, "carrying", carrying)).toJson();
    }

    private static TreeSet<Item> union(Map<Item, Integer> a, Map<Item, Integer> b) {
        TreeSet<Item> keys = new TreeSet<>((x, y) -> BuiltInRegistries.ITEM.getKey(x).compareTo(
                BuiltInRegistries.ITEM.getKey(y)));
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    // ---- recipe lookup / feasibility ----

    private static List<Cand> candidatesFor(ServerLevel level, Item target) {
        List<Cand> out = new ArrayList<>();
        for (RecipeHolder<?> holder : level.recipeAccess().getRecipes()) {
            if (!(holder.value() instanceof CraftingRecipe cr)) {
                continue;
            }
            // A special recipe (firework, map-clone, …) has no static ingredient list — skip.
            // 1.21.2+:静态清单在 PlacementInfo(空格已剔除),不可摆放即特殊配方。
            if (cr.placementInfo().isImpossibleToPlace()
                    || cr.placementInfo().ingredients().isEmpty()) {
                continue;
            }
            ItemStack result;
            try {
                result = cr.assemble(CraftingInput.EMPTY, level.registryAccess());   // shaped/shapeless ignore input
            } catch (RuntimeException inputDependent) {
                continue;
            }
            if (result.isEmpty() || result.getItem() != target) {
                continue;
            }
            out.add(new Cand(cr, result.getCount()));
        }
        return out;
    }

    /** The recipe's non-empty ingredients — the per-craft shopping list.
     *  1.21.2+:PlacementInfo.ingredients() 本身就只含非空格。 */
    private static List<Ingredient> ingredientsOf(CraftingRecipe recipe) {
        return recipe.placementInfo().ingredients();
    }

    private static boolean fits(CraftingRecipe recipe, int w, int h) {
        if (recipe instanceof ShapedRecipe s) {
            return s.getWidth() <= w && s.getHeight() <= h;
        }
        return ingredientsOf(recipe).size() <= w * h;
    }

    /** Where each ingredient goes in a grid of width {@code gridW} (shaped anchors top-left). */
    private static List<Placement> placements(CraftingRecipe recipe, int gridW) {
        List<Placement> out = new ArrayList<>();
        if (recipe instanceof ShapedRecipe s) {
            int w = s.getWidth(), h = s.getHeight();
            var cells = s.getIngredients();   // 1.21.2+: List<Optional<Ingredient>>,空格 = empty
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    Ingredient ing = cells.get(r * w + c).orElse(null);
                    if (ing != null && !ing.isEmpty()) {
                        out.add(new Placement(r * gridW + c, ing));
                    }
                }
            }
        } else {
            int pos = 0;
            for (Ingredient ing : ingredientsOf(recipe)) {
                if (!ing.isEmpty()) {
                    out.add(new Placement(pos++, ing));
                }
            }
        }
        return out;
    }

    /** The matching item with the deepest supply in {@code pool}, or null. */
    private static Item pickItem(Ingredient ing, Map<Item, Integer> pool) {
        Item best = null;
        int bestN = 0;
        for (var holder : ing.items().toList()) {   // 1.21.2+: Holder<Item> 流
            int n = pool.getOrDefault(holder.value(), 0);
            if (n > bestN) {
                best = holder.value();
                bestN = n;
            }
        }
        return best;
    }

    /**
     * The largest per-cell stack size {@code <= wantBatch} the pool can feed for one grid
     * layout (each cell drawing greedily, shared items competing). 0 = can't craft once.
     */
    private static int feasibleBatch(List<Ingredient> ings, Map<Item, Integer> pool0, int wantBatch) {
        int n = Math.max(0, wantBatch);
        while (n > 0) {
            Map<Item, Integer> pool = new HashMap<>(pool0);
            int cap = n;
            boolean ok = true;
            for (Ingredient ing : ings) {
                Item pick = pickItem(ing, pool);
                int have = pick == null ? 0 : pool.getOrDefault(pick, 0);
                int usable = pick == null ? 0 : Math.min(have, new ItemStack(pick).getMaxStackSize());
                if (usable < n) {
                    cap = usable;
                    ok = false;
                    break;
                }
                pool.merge(pick, -n, Integer::sum);
            }
            if (ok) {
                return n;
            }
            n = cap;
        }
        return 0;
    }

    /** "3x iron_ingot (have 1)" lines for every ingredient the pool can't cover once. */
    private static List<String> missingFor(List<Ingredient> ings, Map<Item, Integer> pool) {
        Map<String, int[]> tally = new LinkedHashMap<>();       // desc -> [need]
        Map<String, Ingredient> rep = new LinkedHashMap<>();
        for (Ingredient ing : ings) {
            String desc = QueryExtraTools.describeIngredient(ing);
            tally.computeIfAbsent(desc, k -> new int[1])[0]++;
            rep.putIfAbsent(desc, ing);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : tally.entrySet()) {
            int need = e.getValue()[0];
            int have = 0;
            for (var holder : rep.get(e.getKey()).items().toList()) {
                have += pool.getOrDefault(holder.value(), 0);
            }
            if (have < need) {
                out.add(need + "x " + e.getKey() + " (have " + have + ")");
            }
        }
        return out;
    }

    // ---- menu plumbing ----

    /** Detect a crafting surface generically: CraftingContainer-backed slots + the ResultSlot. */
    private static Grid findGrid(AbstractContainerMenu menu) {
        if (menu == null) {
            return null;
        }
        int w = 0, h = 0, result = -1;
        int[] cells = null;
        for (Slot slot : menu.slots) {
            if (slot instanceof ResultSlot) {
                result = slot.index;
                continue;
            }
            if (slot.container instanceof CraftingContainer cc) {
                if (cells == null) {
                    w = cc.getWidth();
                    h = cc.getHeight();
                    cells = new int[w * h];
                    Arrays.fill(cells, -1);
                }
                int pos = slot.getContainerSlot();
                if (pos >= 0 && pos < cells.length) {
                    cells[pos] = slot.index;
                }
            }
        }
        return (cells == null || result < 0) ? null : new Grid(w, h, cells, result);
    }

    /**
     * The body's spendable materials as seen through {@code menu}: player-side slots only,
     * and only main inventory + hotbar (container slots 0–35) — armor and offhand stay on.
     */
    private static Map<Item, Integer> poolOf(AbstractContainerMenu menu, NumenPlayer self) {
        Map<Item, Integer> pool = new HashMap<>();
        if (menu == null) {
            return pool;
        }
        for (Slot slot : menu.slots) {
            if (slot.container != self.getInventory() || slot.getContainerSlot() >= 36) {
                continue;
            }
            ItemStack s = slot.getItem();
            if (!s.isEmpty()) {
                pool.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return pool;
    }

    /** Put {@code n} of {@code item} into one grid cell via clicks; returns how many landed. */
    private static int placeIntoCell(AbstractContainerMenu menu, NumenPlayer self, int cellIdx,
                                     Item item, Ingredient ing, int n) {
        int placed = 0;
        int guard = 0;
        while (placed < n && guard++ < 40) {
            int src = -1;
            for (Slot slot : menu.slots) {
                if (slot.container != self.getInventory() || slot.getContainerSlot() >= 36) {
                    continue;
                }
                ItemStack s = slot.getItem();
                if (!s.isEmpty() && s.is(item) && ing.test(s)) {
                    src = slot.index;
                    break;
                }
            }
            if (src < 0) {
                break;
            }
            menu.clicked(src, 0, ClickType.PICKUP, self);            // grab a source stack
            int carry = menu.getCarried().getCount();
            if (carry <= 0) {
                break;
            }
            int put = Math.min(carry, n - placed);
            for (int k = 0; k < put; k++) {
                menu.clicked(cellIdx, 1, ClickType.PICKUP, self);    // drop ONE into the cell
            }
            if (!menu.getCarried().isEmpty()) {
                menu.clicked(src, 0, ClickType.PICKUP, self);        // return the remainder
            }
            placed = menu.slots.get(cellIdx).getItem().getCount();
        }
        return placed;
    }

    /** Shift every non-empty grid cell back into the inventory. */
    private static void sweepGrid(AbstractContainerMenu menu, NumenPlayer self, Grid grid) {
        if (menu == null || grid == null) {
            return;
        }
        for (int idx : grid.cells()) {
            if (idx >= 0 && idx < menu.slots.size() && !menu.slots.get(idx).getItem().isEmpty()) {
                menu.clicked(idx, 0, ClickType.QUICK_MOVE, self);
            }
        }
    }

    /** Park a carried stack into a free main-inventory slot; false if none accepts it. */
    private static boolean settleCarried(AbstractContainerMenu menu, NumenPlayer self) {
        if (menu.getCarried().isEmpty()) {
            return true;
        }
        for (Slot slot : menu.slots) {
            if (slot.container != self.getInventory() || slot.getContainerSlot() >= 36) {
                continue;
            }
            if (slot.getItem().isEmpty()) {
                menu.clicked(slot.index, 0, ClickType.PICKUP, self);
                return menu.getCarried().isEmpty();
            }
        }
        return false;
    }

    /** Nearest vanilla crafting table within the box and {@code maxDist} of the eyes, or null. */
    private static BlockPos nearestTable(ServerLevel level, NumenPlayer self, int hr, int vr,
                                         double maxDist) {
        BlockPos base = self.blockPosition();
        Vec3 eye = self.getEyePosition();
        BlockPos best = null;
        double bestD = maxDist * maxDist;
        for (BlockPos p : BlockPos.betweenClosed(base.offset(-hr, -vr, -hr), base.offset(hr, vr, hr))) {
            if (!level.getBlockState(p).is(Blocks.CRAFTING_TABLE)) {
                continue;
            }
            double d = eye.distanceToSqr(Vec3.atCenterOf(p));
            if (d < bestD) {
                bestD = d;
                best = p.immutable();
            }
        }
        return best;
    }
}
