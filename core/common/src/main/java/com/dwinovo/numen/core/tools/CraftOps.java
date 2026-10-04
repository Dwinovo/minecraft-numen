package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;

import com.dwinovo.numen.core.mixin.CraftingMenuAccessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The {@code inv craft} command: one craft in the crafting grid that is open — lay one batch of a named recipe
 * (up to a stack per cell) into the REAL grid via menu clicks and shift-take the result once. Everything runs
 * through the vanilla container path ({@code menu.clicked} on a live {@code CraftingMenu} / {@code InventoryMenu}),
 * so recipe-unlock, stats, ingredient remainders (bucket back from milk) and container-observing mods all see a
 * normal player crafting — items never appear out of thin air.
 *
 * <p>The grid is the one open now: her own 2x2 when nothing else is. Which recipe to use, and finding, opening and
 * closing a crafting table, are the Lua module {@code numen.inv.make}'s; the recipe facts it decides with (the
 * smallest grid a recipe fits, what she is short of) are read here, the same as the craft checks them.
 */
public final class CraftOps {

    /** One call crafts at most this many times per cell: a full stack. */
    private static final int MAX_BATCH = 64;

    /** One grid cell to fill: row-major position in the target grid + what goes there. */
    private record Placement(int gridPos, Ingredient ing) {}

    /** The clickable geometry of an open crafting surface. */
    private record Grid(int w, int h, int[] cells, int result) {}

    /** 合了一次:合出几件、现在背着几件、用掉什么、还回来什么(牛奶桶还回空桶这类),背着的料够不够再合。 */
    @Doc("What one craft made.")
    public record Crafted(@Doc("How many it made.") int crafted,
                          @Doc("How many you carry now.") int carrying,
                          @Doc("What it used, 3x oak_planks.") List<String> used,
                          @Doc("What came back, a bucket from milk.") List<String> gotBack,
                          @Doc("Whether what you carry crafts more of it: call again for the rest.") boolean more) {}

    /** {@code numen.inv.craft}:在开着的格里照 {@code id} 那条配方合一次,至多合到 {@code count} 件。 */
    public static Crafted craft(ResourceLocation id, int want, NumenPlayer self) {
        ServerLevel level = self.serverLevel();
        RecipeHolder<?> found = level.getRecipeManager().byKey(id).orElse(null);
        if (found == null || !(found.value() instanceof CraftingRecipe recipe) || recipe.isSpecial()
                || ingredientsOf(recipe).isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "no crafting recipe " + id,
                    "numen.inv.recipes(item) lists every recipe that makes an item, each with its id");
        }
        @SuppressWarnings("unchecked")
        RecipeHolder<CraftingRecipe> holder = (RecipeHolder<CraftingRecipe>) found;
        ItemStack result = RecipeProbe.resultOf(recipe, level.registryAccess());
        Item target = result.getItem();
        String name = BuiltInRegistries.ITEM.getKey(target).getPath();

        AbstractContainerMenu menu = self.containerMenu;
        Grid grid = findGrid(menu);
        if (grid == null) {
            throw new ApiError(ErrorKind.FAILED, "the open window (" + menu.getClass().getSimpleName() + ") has no "
                    + "crafting grid — numen.gui.close() it to craft in your own 2x2, or numen.use.block a crafting "
                    + "table", null);
        }
        // 先把搁在格子里的收回来,再数料
        sweepGrid(menu, self, grid);
        List<Ingredient> ings = ingredientsOf(recipe);
        Map<Item, Integer> pool = poolOf(menu, self);
        if (feasibleBatch(ings, pool, 1) == 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "not enough materials for " + name + " — missing: "
                    + String.join(", ", missingFor(ings, pool)), null, Map.of("missing", missingFor(ings, pool)));
        }
        if (!fits(recipe, grid.w(), grid.h())) {
            throw new ApiError(ErrorKind.FAILED, name + " needs a " + gridOf(recipe) + "x" + gridOf(recipe) + " grid; "
                    + "the open one is " + grid.w() + "x" + grid.h() + " — numen.use.block a crafting table, then "
                    + "craft again (numen.inv.make finds one and opens it)", null);
        }
        if (!settleCarried(menu, self)) {
            throw new ApiError(ErrorKind.FAILED, "the cursor is holding items and no inventory slot is free to put "
                    + "them down — free a slot first (numen.inv.drop).", null);
        }

        Map<Item, Integer> before = poolOf(menu, self);
        int output = Math.max(1, result.getCount());
        int batch = feasibleBatch(ings, before, Math.min(Math.ceilDiv(want, output), MAX_BATCH));
        // Lay out the batch: per cell, the matching inventory item with the deepest supply.
        Map<Item, Integer> sim = new HashMap<>(before);
        for (Placement pl : placements(recipe, grid.w())) {
            Item pick = pickItem(pl.ing(), sim);
            int cellIdx = pick == null ? -1 : grid.cells()[pl.gridPos()];
            if (cellIdx < 0 || placeIntoCell(menu, self, cellIdx, pick, pl.ing(), batch) < batch) {
                sweepGrid(menu, self, grid);
                throw new ApiError(ErrorKind.FAILED, "couldn't lay " + name + " out in the grid: a cell took fewer "
                        + "items than the recipe needs", null);
            }
            sim.merge(pick, -batch, Integer::sum);
        }
        // 摆完就自己要一次重算,不等 slotsChanged。那是个可被覆写的触发器:把重算推迟到
        // 之后 server tick 的模组覆写的正是它,于是这一刻读到的结果槽还是空的(#110)。
        // 结果槽只有原版那一趟写,这里直接要它算,对原版和那类模组都成立。
        recompute(menu, grid, self, holder);
        if (menu.slots.get(grid.result()).getItem().isEmpty()) {
            sweepGrid(menu, self, grid);
            throw new ApiError(ErrorKind.FAILED, "the laid-out grid doesn't form " + name + " (another mod overrides "
                    + "this grid?)", null);
        }
        int have0 = PlayerInv.count(self.getInventory(), target);
        menu.clicked(grid.result(), 0, ClickType.QUICK_MOVE, self);   // vanilla mass-craft + onTake
        self.swing(InteractionHand.MAIN_HAND);
        sweepGrid(menu, self, grid);
        int crafted = PlayerInv.count(self.getInventory(), target) - have0;
        if (crafted <= 0) {
            throw new ApiError(ErrorKind.FAILED, "crafted nothing — your inventory is full and the result doesn't "
                    + "fit.", null);
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
        return new Crafted(crafted, PlayerInv.count(self.getInventory(), target), used, back,
                feasibleBatch(ings, after, 1) > 0);
    }

    /** {@code numen.inv.craftable}:她背着的料现在合得出的每一条合成配方。 */
    public static List<Recipe> craftable(NumenPlayer self) {
        ServerLevel level = self.serverLevel();
        Map<Item, Integer> pool = poolOf(self.inventoryMenu, self);
        List<Recipe> recipes = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            try {
                CraftingRecipe recipe = holder.value();
                ItemStack result = RecipeProbe.resultOf(recipe, level.registryAccess());
                if (recipe.isSpecial() || result.isEmpty() || !RecipeProbe.usableIngredients(recipe)
                        || ingredientsOf(recipe).isEmpty() || gridOf(recipe) == 0
                        || feasibleBatch(ingredientsOf(recipe), pool, 1) == 0) {
                    continue;
                }
                recipes.add(recipe(holder, recipe, result, self));
            } catch (RuntimeException broken) {
                // 坏一条丢一条,记下 id 方便去上游反馈;绝不让它杀掉整个调用
                com.dwinovo.numen.core.Constants.LOG.debug(
                        "[numen-craft] 配方 {} 坏了,跳过: {}", holder.id(), broken.toString());
            }
        }
        return recipes;
    }

    /**
     * 一条合成配方:编号、合出几件、每格的料、装得下的最小的格,以及照她背着的合一次还缺什么(不缺时没有)。
     * {@code numen.inv.recipes} 与 {@code numen.inv.craftable} 共用这一份。
     */
    static Recipe recipe(RecipeHolder<?> holder, CraftingRecipe recipe, ItemStack result, NumenPlayer self) {
        List<Ingredient> ings = ingredientsOf(recipe);
        Map<Item, Integer> pool = poolOf(self.inventoryMenu, self);
        return new Recipe(holder.id().toString(), Recipe.Station.CRAFTING,
                BuiltInRegistries.ITEM.getKey(result.getItem()).toString(), result.getCount(),
                ings.stream().map(RecipeBook::describeIngredient).toList(), java.util.Optional.of(gridOf(recipe)),
                feasibleBatch(ings, pool, 1) == 0 ? java.util.Optional.of(missingFor(ings, pool))
                        : java.util.Optional.empty(), java.util.Optional.empty());
    }

    /** 这条合成配方装得下的最小的格:2(她自己的)、3(工作台);更大的(模组工位)是 0。 */
    static int gridOf(CraftingRecipe recipe) {
        return fits(recipe, 2, 2) ? 2 : fits(recipe, 3, 3) ? 3 : 0;
    }

    /**
     * 按当前格局重算结果槽。容器从菜单自己的槽位上取({@code Slot.container}),所以
     * 工作台和她自己的 2×2 走同一条;拿不到原版那两种容器的(模组自定义合成台)就不动。
     */
    private static void recompute(AbstractContainerMenu menu, Grid grid, NumenPlayer self,
                                  RecipeHolder<CraftingRecipe> hint) {
        if (!(self.level() instanceof ServerLevel level)) {
            return;
        }
        Container cells = menu.slots.get(grid.cells()[0]).container;
        Container out = menu.slots.get(grid.result()).container;
        if (cells instanceof CraftingContainer craft && out instanceof ResultContainer result) {
            CraftingMenuAccessor.numen$recompute(menu, level, self, craft, result, hint);
        }
    }

    private static TreeSet<Item> union(Map<Item, Integer> a, Map<Item, Integer> b) {
        TreeSet<Item> keys = new TreeSet<>((x, y) -> BuiltInRegistries.ITEM.getKey(x).compareTo(
                BuiltInRegistries.ITEM.getKey(y)));
        keys.addAll(a.keySet());
        keys.addAll(b.keySet());
        return keys;
    }

    // ---- recipe feasibility ----

    /** The recipe's non-empty ingredients — the per-craft shopping list. */
    private static List<Ingredient> ingredientsOf(CraftingRecipe recipe) {
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).toList();
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
            var cells = s.getIngredients();
            for (int r = 0; r < h; r++) {
                for (int c = 0; c < w; c++) {
                    Ingredient ing = cells.get(r * w + c);
                    if (!ing.isEmpty()) {
                        out.add(new Placement(r * gridW + c, ing));
                    }
                }
            }
        } else {
            int pos = 0;
            for (Ingredient ing : recipe.getIngredients()) {
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
        for (ItemStack s : ing.getItems()) {
            int n = pool.getOrDefault(s.getItem(), 0);
            if (n > bestN) {
                best = s.getItem();
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
            String desc = RecipeBook.describeIngredient(ing);
            tally.computeIfAbsent(desc, k -> new int[1])[0]++;
            rep.putIfAbsent(desc, ing);
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, int[]> e : tally.entrySet()) {
            int need = e.getValue()[0];
            int have = 0;
            for (ItemStack s : rep.get(e.getKey()).getItems()) {
                have += pool.getOrDefault(s.getItem(), 0);
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
            int before = placed;
            MenuOps.dripInto(menu, self, src, cellIdx, n - placed);
            placed = menu.slots.get(cellIdx).getItem().getCount();
            if (placed <= before) {
                break;   // 这一轮没放进任何东西(抓空/拒收),别空转
            }
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
}
