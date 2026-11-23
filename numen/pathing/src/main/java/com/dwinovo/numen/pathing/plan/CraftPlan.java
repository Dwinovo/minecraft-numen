package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;

/**
 * 做一件东西要按什么顺序做哪几步:只读配方事实,不在世界里动手。执行(摆料、点结果槽、开工作台)在 {@code drive.Kit}。
 *
 * <p>一条配方是一个 {@link Shape}:产出物品与件数、装得进 2×2 还是 3×3、每格要哪种料(有序配方按宽高摆,无序配方按先后填)。
 * 规划是从目标往下递归:背包里有就不做;没有就找一条做它的配方,逐格挑一种家里有(或先做得出)的料,扣料、加产物;
 * 材料不够或配方不在 2×2/3×3 里就这一条不成,换下一条。步数、递归深度都有上限:这是给"挖一格之前备一件工具"用的,不是
 * 通用的合成规划器(通用的那一条是 {@code numen.inv.make})。
 */
public final class CraftPlan {

    /** 一条配方。 */
    public record Shape(ResourceLocation id, Item result, int count, int width, int height, List<Ingredient> cells, int grid) {

        public Shape {
            cells = List.copyOf(cells);
        }

        /** 无序配方:没有宽高,按 cells 的先后填进格里。 */
        public boolean shapeless() {
            return width <= 0 || height <= 0;
        }
    }

    /** 做一次:这条配方,每格放哪种物品(与 {@link Shape#cells} 等长,空格为 null)。 */
    public record Step(Shape shape, List<Item> items) {

        public Step {
            // 空格是 null(有序配方的空格子),不能用 List.copyOf
            items = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(items));
        }
    }

    /**
     * 一份计划。
     *
     * @param steps 按先后;前面的是料,最后一步才是目标本身
     * @param left  照这份计划做完之后,背包里还剩什么:再规划下一步(比如先做工作台再做工具)时接着用它
     */
    public record Plan(List<Step> steps, Map<Item, Integer> left) {

        public Plan {
            steps = List.copyOf(steps);
            left = Map.copyOf(left);
        }
    }

    /** 一份计划最多几步、递归几层:够原木→木板→木棍→工作台→工具,再多就不是"顺手备一件"了。 */
    private static final int MAX_STEPS = 12;
    private static final int MAX_DEPTH = 6;

    private CraftPlan() {}

    /**
     * 做一件 {@code target} 的计划;背包里(或先做得出)就不必做时交出空步骤的计划,做不出为 {@link Optional#empty()}。
     *
     * @param byResult 物品 → 做它的配方,顺序即优先顺序(调用方按配方 id 排好,结果是确定的)
     * @param carried  背包里此刻的件数(只看主背包)
     */
    public static Optional<Plan> plan(Item target, Map<Item, List<Shape>> byResult, Map<Item, Integer> carried) {
        State state = new State(carried);
        if (!make(target, 1, 0, byResult, state)) {
            return Optional.empty();
        }
        return Optional.of(new Plan(state.steps, state.pool));
    }

    /**
     * 一条配方读成 {@link Shape};读不出(产出空、输入表坏、装不进 3×3)为空。坏配方只丢它自己,不抛给调用方:
     * 整合包里一条坏配方不该让整趟路停下(判据与 {@code RecipeProbe} 一致)。
     */
    public static Optional<Shape> shape(CraftingRecipe recipe, ResourceLocation id, ItemStack result) {
        if (recipe.isSpecial() || result.isEmpty()) {
            return Optional.empty();
        }
        List<Ingredient> ingredients;
        try {
            ingredients = recipe.getIngredients();
        } catch (RuntimeException broken) {
            return Optional.empty();
        }
        if (ingredients == null) {
            return Optional.empty();
        }
        int width = 0;
        int height = 0;
        List<Ingredient> cells;
        if (recipe instanceof ShapedRecipe shaped) {
            width = shaped.getWidth();
            height = shaped.getHeight();
            if (width <= 0 || height <= 0) {
                return Optional.empty();
            }
            List<Ingredient> shapedCells = new ArrayList<>(width * height);
            for (int i = 0; i < width * height; i++) {
                Ingredient cell = i < ingredients.size() ? ingredients.get(i) : Ingredient.EMPTY;
                shapedCells.add(cell == null ? Ingredient.EMPTY : cell);
            }
            cells = shapedCells;
        } else {
            cells = new ArrayList<>();
            for (Ingredient cell : ingredients) {
                if (cell != null && !cell.isEmpty()) {
                    cells.add(cell);
                }
            }
            if (cells.isEmpty()) {
                return Optional.empty();
            }
        }
        int grid = fits(cells, width, height, 2) ? 2 : fits(cells, width, height, 3) ? 3 : 0;
        if (grid == 0) {
            return Optional.empty();
        }
        return Optional.of(new Shape(id, result.getItem(), result.getCount(), width, height, cells, grid));
    }

    /** 递归做:从背包扣、或先做它需要的料;成功时把扣减与步骤写回 {@code state}。 */
    private static boolean make(Item item, int need, int depth, Map<Item, List<Shape>> byResult, State state) {
        int have = state.pool.getOrDefault(item, 0);
        if (have >= need) {
            state.pool.put(item, have - need);
            return true;
        }
        int deficit = need - have;
        if (have > 0) {
            state.pool.remove(item);
        }
        if (depth >= MAX_DEPTH || state.steps.size() >= MAX_STEPS) {
            return false;
        }
        for (Shape shape : byResult.getOrDefault(item, List.of())) {
            if (shape.count() <= 0) {
                continue;
            }
            int crafts = (deficit + shape.count() - 1) / shape.count();
            if (crafts <= 0) {
                continue;
            }
            State branch = new State(state);
            boolean ok = true;
            for (int craft = 0; craft < crafts && ok; craft++) {
                List<Item> chosen = new ArrayList<>(shape.cells().size());
                for (Ingredient cell : shape.cells()) {
                    if (cell.isEmpty()) {
                        chosen.add(null);
                        continue;
                    }
                    Item pick = chooseItem(cell, depth, byResult, branch);
                    if (pick == null) {
                        ok = false;
                        break;
                    }
                    chosen.add(pick);
                }
                if (ok) {
                    branch.steps.add(new Step(shape, chosen));
                    branch.pool.merge(item, shape.count(), Integer::sum);
                    ok = branch.steps.size() <= MAX_STEPS;
                }
            }
            if (ok) {
                // 做出来的这几件里,调用方要的 need 件就用在它那一格:从账上扣掉,不然上面的格子会把同一批料算两遍
                branch.pool.merge(item, -need, Integer::sum);
                state.pool.clear();
                state.pool.putAll(branch.pool);
                state.steps.clear();
                state.steps.addAll(branch.steps);
                return true;
            }
        }
        return false;
    }

    /** 这一格放哪种:家里现有的先挑(件数多的优先),没有就挑一种先做得出它的;都不行为 null。 */
    private static Item chooseItem(Ingredient cell, int depth, Map<Item, List<Shape>> byResult, State state) {
        List<Item> items = new ArrayList<>();
        for (ItemStack stack : cell.getItems()) {
            if (!stack.isEmpty() && !items.contains(stack.getItem())) {
                items.add(stack.getItem());
            }
        }
        items.sort((a, b) -> {
            int byCount = Integer.compare(state.pool.getOrDefault(b, 0), state.pool.getOrDefault(a, 0));
            return byCount != 0 ? byCount : name(a).compareTo(name(b));
        });
        for (Item item : items) {
            State branch = new State(state);
            if (make(item, 1, depth + 1, byResult, branch)) {
                state.pool.clear();
                state.pool.putAll(branch.pool);
                state.steps.clear();
                state.steps.addAll(branch.steps);
                return item;
            }
        }
        return null;
    }

    private static boolean fits(List<Ingredient> cells, int width, int height, int side) {
        return width <= 0 || height <= 0 ? cells.size() <= side * side : width <= side && height <= side;
    }

    private static String name(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).toString();
    }

    /** 规划中的背包与步骤:每个候选分支各拿一份,成的那一份写回。 */
    private static final class State {

        final Map<Item, Integer> pool;
        final List<Step> steps;

        State(Map<Item, Integer> carried) {
            this.pool = new HashMap<>(carried);
            this.steps = new ArrayList<>();
        }

        State(State from) {
            this.pool = new HashMap<>(from.pool);
            this.steps = new ArrayList<>(from.steps);
        }
    }
}
