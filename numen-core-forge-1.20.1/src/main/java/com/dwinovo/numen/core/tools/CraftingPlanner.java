package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.core.net.TaskResultPayload;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.dwinovo.numen.core.task.ReservationGuard;

/** Recursive dependency planner whose output is directly executable by craft_items. */
public final class CraftingPlanner {

    public static final int MAX_DEPTH = 5;
    public static final int MAX_STEPS = 80;

    public enum Station {
        PLAYER_CRAFTING("player_2x2"), CRAFTING_TABLE("crafting_table"),
        FURNACE("furnace"), BLAST_FURNACE("blast_furnace"), SMOKER("smoker"),
        CAMPFIRE("campfire"), STONECUTTER("stonecutter");

        private final String id;
        Station(String id) { this.id = id; }
        public String id() { return id; }
        public static Station parse(String raw) {
            for (Station station : values()) if (station.id.equals(raw)) return station;
            throw new IllegalArgumentException("unknown crafting station: " + raw);
        }
    }

    /** ingredientSlots preserves shaped gaps with Items.AIR; shapeless/cooking are compact. */
    public record Step(ResourceLocation recipeId, Station station, String detail,
                       Item output, int outputCount, int batches, List<Item> ingredientSlots) {
        public Step {
            ingredientSlots = List.copyOf(ingredientSlots);
        }
        public int produces() { return outputCount * batches; }
    }

    public record Plan(Item target, int requested, int alreadyHave, int availableAfterPlan,
                       List<Step> steps, Map<Item, Integer> missing, int maxDepth) {
        public Plan {
            steps = List.copyOf(steps);
            missing = Map.copyOf(missing);
        }
        public boolean craftable() { return missing.isEmpty(); }

        public JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("target", id(target));
            root.addProperty("requested", requested);
            root.addProperty("already_have", alreadyHave);
            root.addProperty("available_after_plan", availableAfterPlan);
            root.addProperty("craftable_with_current_inventory", craftable());
            root.add("missing_base_materials", itemMap(missing));
            JsonArray arr = new JsonArray();
            for (Step step : steps) arr.add(stepJson(step));
            root.add("steps", arr);
            root.addProperty("max_depth", maxDepth);
            root.addProperty("tip", craftable()
                    ? "Call craft_items to execute this plan with real recipe consumption and workstation checks."
                    : "Gather missing_base_materials, then call plan_crafting or craft_items again.");
            return root;
        }
    }

    private final ServerLevel level;
    private final Map<Item, Integer> inventory;
    private final Map<Item, Integer> virtualStock = new HashMap<>();
    private final Map<Item, Integer> missing = new LinkedHashMap<>();
    private final List<Step> steps = new ArrayList<>();
    private final Set<Item> visiting = new LinkedHashSet<>();
    private final int maxDepth;

    private CraftingPlanner(ServerLevel level, Map<Item, Integer> inventory, int maxDepth) {
        this.level = level;
        this.inventory = inventory;
        this.maxDepth = Math.max(1, Math.min(MAX_DEPTH, maxDepth));
        this.virtualStock.putAll(inventory);
    }

    /** Plan a target count, allowing existing target items to satisfy it (plan_crafting semantics). */
    public static Plan create(String itemId, int count, int maxDepth, NumenPlayer self) {
        return create(ToolArgs.parseItem(itemId), count, maxDepth, self, false);
    }

    /** Plan N additional target items; existing target stock is ignored for the top-level goal. */
    public static Plan createAdditional(Item target, int count, int maxDepth, NumenPlayer self) {
        return create(target, count, maxDepth, self, true, List.of(), "");
    }

    public static Plan createAdditional(Item target, int count, int maxDepth, NumenPlayer self,
                                        List<ReservationGuard.Reservation> reservations, String purpose) {
        return create(target, count, maxDepth, self, true, reservations, purpose);
    }

    private static Plan create(Item target, int count, int maxDepth, NumenPlayer self,
                               boolean additional) {
        return create(target, count, maxDepth, self, additional, List.of(), "");
    }

    private static Plan create(Item target, int count, int maxDepth, NumenPlayer self,
                               boolean additional, List<ReservationGuard.Reservation> reservations,
                               String purpose) {
        if (!(self.level instanceof ServerLevel level)) {
            throw new IllegalArgumentException("crafting plan needs a server level");
        }
        int wanted = Math.max(1, Math.min(999, count));
        Map<Item, Integer> inventory = inventory(self);
        java.util.HashSet<String> adjustedReservations = new java.util.HashSet<>();
        for (ReservationGuard.Reservation reservation : reservations) {
            if (!adjustedReservations.add(reservation.item().toLowerCase(java.util.Locale.ROOT))) continue;
            ResourceLocation id = ResourceLocation.tryParse(reservation.item());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;
            Item protectedItem = BuiltInRegistries.ITEM.get(id);
            int protectedCount = ReservationGuard.protectedCount(reservations, reservation.item(), purpose);
            if (protectedCount > 0) inventory.computeIfPresent(protectedItem,
                    (ignored, have) -> Math.max(0, have - protectedCount));
        }
        int already = inventory.getOrDefault(target, 0);
        if (additional) inventory.put(target, 0);
        CraftingPlanner planner = new CraftingPlanner(level, inventory, maxDepth);
        planner.require(target, wanted, 0);
        return new Plan(target, wanted, already, planner.virtualStock.getOrDefault(target, 0),
                planner.steps, planner.missing, planner.maxDepth);
    }

    public static String planJson(String itemId, int count, int maxDepth, NumenPlayer self) {
        try {
            Plan plan = create(itemId, count, maxDepth, self);
            String result = TaskResult.ok(plan.toJson().toString()).toJson();
            if (result.length() <= TaskResultPayload.MAX_RESULT_JSON_LENGTH) return result;
            return TaskResult.ok("crafting plan is too large for full step details",
                    Map.of("target", id(plan.target()), "requested", plan.requested(),
                            "craftable", plan.craftable(), "step_count", plan.steps().size(),
                            "missing_material_types", plan.missing().size())).toJson();
        } catch (RuntimeException ex) {
            return TaskResult.fail(ex.getMessage()).toJson();
        }
    }

    private void require(Item item, int count, int depth) {
        int have = virtualStock.getOrDefault(item, 0);
        if (have >= count) {
            virtualStock.put(item, have - count);
            return;
        }
        if (have > 0) {
            count -= have;
            virtualStock.put(item, 0);
        }
        if (depth >= maxDepth || steps.size() >= MAX_STEPS || visiting.contains(item)) {
            missing.merge(item, count, Integer::sum);
            return;
        }

        Choice choice = findRecipe(item);
        if (choice == null) {
            missing.merge(item, count, Integer::sum);
            return;
        }

        visiting.add(item);
        int batches = (int) Math.ceil(count / (double) choice.outputCount);
        Map<Item, Integer> inputs = new LinkedHashMap<>();
        for (Item ingredient : choice.ingredientSlots) {
            if (ingredient != Items.AIR) inputs.merge(ingredient, 1, Integer::sum);
        }
        for (Map.Entry<Item, Integer> input : inputs.entrySet()) {
            require(input.getKey(), input.getValue() * batches, depth + 1);
        }
        visiting.remove(item);

        steps.add(new Step(choice.recipe.getId(), choice.station, choice.detail,
                item, choice.outputCount, batches, choice.ingredientSlots));
        virtualStock.merge(item, batches * choice.outputCount - count, Integer::sum);
    }

    private Choice findRecipe(Item target) {
        Choice best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty() || result.getItem() != target) continue;
            Choice choice = toChoice(recipe, result);
            if (choice == null || choice.ingredientSlots.stream().allMatch(i -> i == Items.AIR)) continue;
            int score = recipeAvailabilityScore(choice);
            if (best == null || score > bestScore
                    || (score == bestScore && stationPriority(choice.station) < stationPriority(best.station))) {
                best = choice;
                bestScore = score;
            }
        }
        return best;
    }

    /** Prefer a recipe whose selected alternatives are already present; avoids bamboo sticks over planks. */
    private int recipeAvailabilityScore(Choice choice) {
        Map<Item,Integer> need = new LinkedHashMap<>();
        for (Item item : choice.ingredientSlots) if (item != Items.AIR) need.merge(item, 1, Integer::sum);
        int covered = 0, missingTypes = 0;
        for (var e : need.entrySet()) {
            int have = ingredientPotential(e.getKey(), 0, new LinkedHashSet<>());
            covered += Math.min(have, e.getValue());
            if (have < e.getValue()) missingTypes++;
        }
        return covered * 10_000 - missingTypes * 100 - need.values().stream().mapToInt(Integer::intValue).sum();
    }

    private Choice toChoice(Recipe<?> recipe, ItemStack result) {
        Station station;
        String detail;
        if (recipe instanceof CraftingRecipe crafting) {
            if (crafting.isSpecial() || crafting.getIngredients().isEmpty()) return null;
            station = crafting.canCraftInDimensions(2, 2)
                    ? Station.PLAYER_CRAFTING : Station.CRAFTING_TABLE;
            detail = crafting instanceof ShapedRecipe shaped
                    ? "shaped " + shaped.getWidth() + "x" + shaped.getHeight() : "shapeless";
        } else if (recipe instanceof CampfireCookingRecipe) {
            station = Station.CAMPFIRE;
            detail = "campfire";
        } else if (recipe instanceof AbstractCookingRecipe cooking) {
            station = cookingStation(cooking);
            detail = station.id();
        } else if (recipe instanceof StonecutterRecipe) {
            station = Station.STONECUTTER;
            detail = "stonecutter";
        } else {
            return null;
        }

        List<Item> slots = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) {
                slots.add(Items.AIR);
                continue;
            }
            Item picked = pickIngredient(ingredient);
            if (picked == null) return null;
            slots.add(picked);
        }
        return new Choice(recipe, station, detail, Math.max(1, result.getCount()), slots);
    }

    private Item pickIngredient(Ingredient ingredient) {
        ItemStack[] stacks = ingredient.getItems();
        if (stacks.length == 0) return null;
        Item best = stacks[0].getItem();
        int bestHave = ingredientPotential(best, 0, new LinkedHashSet<>());
        for (ItemStack stack : stacks) {
            Item item = stack.getItem();
            int have = ingredientPotential(item, 0, new LinkedHashSet<>());
            if (have > bestHave) {
                best = item;
                bestHave = have;
            }
        }
        return best;
    }

    /** Score ingredients reachable from current stock through a short dependency chain. */
    private int ingredientPotential(Item target, int depth, Set<Item> seen) {
        int have = virtualStock.getOrDefault(target, 0);
        if (have > 0) return 100_000 + have;
        if (depth >= 3 || !seen.add(target)) return 0;
        int best = 0;
        for (Recipe<?> recipe : level.getRecipeManager().getRecipes()) {
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty() || result.getItem() != target || recipe.getIngredients().isEmpty()) continue;
            int score = 0; boolean possible = true;
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient.isEmpty()) continue;
                int alternative = 0;
                for (ItemStack stack : ingredient.getItems()) alternative = Math.max(alternative,
                        ingredientPotential(stack.getItem(), depth + 1, new LinkedHashSet<>(seen)));
                if (alternative == 0) possible = false;
                score += alternative;
            }
            if (possible) best = Math.max(best, 10_000 + score / Math.max(1, recipe.getIngredients().size()));
        }
        return best;
    }

    private static Station cookingStation(AbstractCookingRecipe recipe) {
        String type = recipe.getType().toString();
        if (type.contains("blasting")) return Station.BLAST_FURNACE;
        if (type.contains("smoking")) return Station.SMOKER;
        return Station.FURNACE;
    }

    private static int stationPriority(Station station) {
        return switch (station) {
            case PLAYER_CRAFTING -> 0;
            case CRAFTING_TABLE -> 1;
            case FURNACE, BLAST_FURNACE, SMOKER -> 2;
            case STONECUTTER -> 3;
            case CAMPFIRE -> 4;
        };
    }

    private static Map<Item, Integer> inventory(NumenPlayer self) {
        Map<Item, Integer> out = new HashMap<>();
        for (ItemStack stack : self.getInventory().items) {
            if (!stack.isEmpty()) out.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return out;
    }

    private static JsonObject stepJson(Step step) {
        JsonObject o = new JsonObject();
        o.addProperty("recipe_id", step.recipeId().toString());
        o.addProperty("station", step.station().id());
        o.addProperty("detail", step.detail());
        o.addProperty("output", id(step.output()));
        o.addProperty("output_per_batch", step.outputCount());
        o.addProperty("batches", step.batches());
        o.addProperty("produces", step.produces());
        JsonArray layout = new JsonArray();
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (Item ingredient : step.ingredientSlots()) {
            layout.add(ingredient == Items.AIR ? "." : id(ingredient));
            if (ingredient != Items.AIR) totals.merge(ingredient, step.batches(), Integer::sum);
        }
        o.add("ingredient_layout", layout);
        o.add("inputs", itemMap(totals));
        return o;
    }

    private static JsonObject itemMap(Map<Item, Integer> map) {
        JsonObject o = new JsonObject();
        for (Map.Entry<Item, Integer> e : map.entrySet()) o.addProperty(id(e.getKey()), e.getValue());
        return o;
    }

    public static String id(Item item) { return BuiltInRegistries.ITEM.getKey(item).toString(); }

    private record Choice(Recipe<?> recipe, Station station, String detail,
                          int outputCount, List<Item> ingredientSlots) {}
}
