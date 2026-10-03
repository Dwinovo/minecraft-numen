package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.core.scan.NearbyEntities;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Signals;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Query implementations — the business half of the {@code inv recipe} command declared in
 * {@link com.dwinovo.numen.core.tools.inventory.InvCommands}, and of the
 * {@code scan entities} and {@code scan container} commands declared in
 * {@link com.dwinovo.numen.core.tools.perception.ScanCommands}.
 */
public final class QueryExtraOps {

    // ---- scan entities ----

    private static final double MIN_RADIUS = 1.0;
    private static final double MAX_RADIUS = 64.0;

    /** 回执数据里那份实体清单的键:脚本里 {@code numen.scan.entities} 直接返回它。 */
    public static final String ENTITIES = "entities";

    /**
     * 半径内的实体由近及远,一只一行(一个 JSON 对象),按输出预算分页({@link Listing});数据里是同一份清单的全部
     * ({@link #ENTITIES}),脚本拿它循环。翻页现读:实体会走动,编号是运行期编号,它还在世界里时不变。
     *
     * @param filter hostile、passive、player、item 或 all,由参数类型把关
     */
    public String scanNearbyEntities(double radius, String filter, NumenPlayer self, CommandArgs args) {
        radius = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);

        // 倒下、正在消失的(死亡动画那二十来刻)不列:它已经打不着、用不了,列出来脚本会对着一具尸体再打一场
        List<Entity> raw = NearbyEntities.within(self, radius, Entity.class, Entity::isAlive);

        List<ScoredEntity> matched = new ArrayList<>(raw.size());
        for (Entity e : raw) {
            String cat = categorise(e);
            if (!matches(filter, cat)) continue;
            matched.add(new ScoredEntity(e, cat, self.distanceTo(e)));
        }
        matched.sort(Comparator.comparingDouble(s -> s.distance));

        List<String> rows = new ArrayList<>(matched.size());
        com.google.gson.JsonArray all = new com.google.gson.JsonArray();
        for (ScoredEntity s : matched) {
            JsonObject o = com.dwinovo.numen.cli.Shapes.entity(s.entity);
            o.addProperty("category", s.category);
            o.addProperty("distance", Math.round(s.distance * 10.0) / 10.0);
            if (s.entity instanceof net.minecraft.world.entity.item.ItemEntity item) {
                o.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString());
                o.addProperty("count", item.getItem().getCount());
                // 刚掉下来的东西原版要过一小段冷却才让人捡:还剩几刻,捡的库函数据此判断走上去没捡到是不是在等冷却
                o.addProperty("pickup_delay",
                        ((com.dwinovo.numen.core.mixin.ItemEntityAccessor) item).numen$getPickupDelay());
            }
            if (s.entity instanceof LivingEntity le) {
                o.addProperty("hp", le.getHealth());
                o.addProperty("max_hp", le.getMaxHealth());
            }
            String owner = ownerOf(s.entity, self);
            if (owner != null) {
                o.addProperty("owner", owner);
            }
            rows.add(o.toString());
            all.add(o);
        }
        String where = " within " + radius + " blocks (" + filter + ")";
        String head = rows.isEmpty()
                ? "No entities" + where + "."
                : rows.size() + " entit" + (rows.size() == 1 ? "y" : "ies") + where + ", nearest first, one per line:";
        return new Listing(head, rows, "").result(args, Map.of(ENTITIES, all)).toJson();
    }

    /**
     * 这只是谁的:{@code you}(她自己驯服的)、{@code your owner}(她主人的)、别的玩家的名字,名字查不到就是 UUID;
     * 没有主人为 null。主人只从权限层的同一处读({@link Signals#ownerOf}),{@code self_owned} 与这里说的是同一回事。
     */
    private static String ownerOf(Entity e, NumenPlayer self) {
        UUID owner = Signals.ownerOf(e);
        if (owner == null) {
            return null;
        }
        if (owner.equals(self.getUUID())) {
            return "you";
        }
        if (self.isOwnedByPlayer(owner)) {
            return "your owner";
        }
        String name = NumenPlayer.playerName(self.getServer(), owner);
        return name.isEmpty() ? owner.toString() : name;
    }

    private static String categorise(Entity e) {
        if (e instanceof Player) return "player";
        if (e instanceof Monster) return "hostile";
        if (e instanceof net.minecraft.world.entity.item.ItemEntity) return "item";
        return "passive";
    }

    private static boolean matches(String filter, String category) {
        if ("all".equals(filter)) return true;
        return filter.equals(category);
    }

    private record ScoredEntity(Entity entity, String category, double distance) {}

    // ---- inv recipe ----

    /**
     * 做这样东西的每一条配方,一条一个条目,按输出预算分页({@link Listing});结尾是各种工位怎么做。
     *
     */
    public String lookupRecipe(String item_id, NumenPlayer self, CommandArgs args) {
        Item target = ToolArgs.parseItem(item_id);
        if (!(self.level() instanceof ServerLevel level)) {
            return TaskResult.fail("recipe lookup needs a server level.").toJson();
        }
        String name = BuiltInRegistries.ITEM.getKey(target).getPath();

        List<String> recipes = new ArrayList<>();
        for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
            // 每条配方自成一格:整合包里一条坏配方(产出为 null、输入表为 null)
            // 只丢它自己,绝不让它杀掉整个查询。见 RecipeProbe。
            try {
                Recipe<?> r = holder.value();
                if (r instanceof CraftingRecipe cr) {
                    // 产出依赖输入的配方(烟花、镶零件的装备)静态描述答不了;
                    // 1.21.1 没有 PlacementInfo,空输入表的老启发式一并保留。
                    if (cr.isSpecial() || cr.getIngredients().isEmpty()
                            || cr.getIngredients().stream().allMatch(Ingredient::isEmpty)) {
                        continue;
                    }
                    ItemStack result = RecipeProbe.resultOf(cr, level.registryAccess());
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add("[crafting] " + format(cr, result));
                } else if (r instanceof AbstractCookingRecipe cook) {
                    ItemStack result = RecipeProbe.resultOf(cook, level.registryAccess());
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add(formatCooking(cook, result));
                } else if (r instanceof StonecutterRecipe sc) {
                    ItemStack result = RecipeProbe.resultOf(sc, level.registryAccess());
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add("[stonecutter] " + describeIngredient(sc.getIngredients().get(0))
                            + " -> makes " + result.getCount());
                } else if (r instanceof SmithingRecipe sm) {
                    // 锻造不走展示产出,保留空输入 assemble 的既有语义:变换配方
                    // (下界合金升级)照样给出产物,纹饰配方产出(空的)基底、自然排除。
                    ItemStack result = RecipeProbe.probe(() -> sm.assemble(new SmithingRecipeInput(
                            ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY), level.registryAccess()));
                    if (result.isEmpty() || result.getItem() != target) {
                        continue;
                    }
                    recipes.add(formatSmithing(sm, result));
                }
            } catch (RuntimeException broken) {
                com.dwinovo.numen.core.Constants.LOG.debug(
                        "[numen-recipe] 配方 {} 坏了,跳过: {}", holder.id(), broken.toString());
            }
        }

        if (recipes.isEmpty()) {
            return TaskResult.ok("no recipe for " + name + " — it's obtained another way (mine it, or "
                    + "trade), not crafted or smelted.", Map.of(RECIPES, recipes)).toJson();
        }
        return new Listing(recipes.size() + " recipe(s) for " + name + ":", recipes, "To make it —\n"
                + "• [crafting]: numen.inv.craft(<item>, {count = N}) — it lays out the grid and takes the "
                + "result for you (a 3x3 recipe needs a crafting table within reach; 2x2 works "
                + "anywhere).\n"
                + "• [smelting|blasting|smoking]: numen.use.block the furnace, then numen.use.shift the input and "
                + "the fuel — the menu routes each to its slot. Wait, then numen.use.shift the output back "
                + "out.\n"
                + "• [stonecutter]: numen.use.block it, numen.use.shift the input (the menu routes it in), take the "
                + "output. [smithing]: numen.use.block it, numen.use.gui, then numen.use.transfer template + base + "
                + "addition each into its own slot.").result(args, Map.of(RECIPES, recipes)).toJson();
    }

    /** {@code numen.inv.recipe} 返回的那一项:每条配方一段文字。 */
    public static final String RECIPES = "recipes";

    private static String format(CraftingRecipe recipe, ItemStack result) {
        int count = result.getCount();
        if (recipe instanceof ShapedRecipe shaped) {
            int w = shaped.getWidth();
            int h = shaped.getHeight();
            var cells = shaped.getIngredients();   // 1.21.1: NonNullList<Ingredient>, gaps = Ingredient.EMPTY
            StringBuilder sb = new StringBuilder("shaped " + w + "x" + h + ", makes " + count + ":");
            for (int r = 0; r < h; r++) {
                sb.append("\n  ");
                for (int c = 0; c < w; c++) {
                    Ingredient ing = cells.get(r * w + c);
                    sb.append(ing.isEmpty() ? "." : describeIngredient(ing));
                    if (c < w - 1) {
                        sb.append(" | ");
                    }
                }
            }
            return sb.toString();
        }
        // Shapeless: order doesn't matter, place anywhere.
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Ingredient ing : recipe.getIngredients()) {
            if (ing.isEmpty()) continue;
            counts.merge(describeIngredient(ing), 1, Integer::sum);
        }
        String list = counts.entrySet().stream()
                .map(e -> e.getValue() + "x " + e.getKey())
                .collect(Collectors.joining(", "));
        return "shapeless, makes " + count + ": " + list + " (place anywhere in the grid)";
    }

    /** A cooking recipe (one input → one output) labelled by its station. */
    private static String formatCooking(AbstractCookingRecipe recipe, ItemStack result) {
        RecipeType<?> type = recipe.getType();
        String station = type == RecipeType.BLASTING ? "blasting (blast furnace)"
                : type == RecipeType.SMOKING ? "smoking (smoker)"
                : type == RecipeType.CAMPFIRE_COOKING ? "campfire"
                : "smelting (furnace)";
        return "[" + station + "] " + describeIngredient(recipe.getIngredients().get(0)) + " -> makes "
                + result.getCount() + " (" + recipe.getCookingTime() + " ticks)";
    }

    /** A smithing recipe (smithing table). 1.21.1's SmithingRecipe exposes only is*Ingredient(stack)
     *  tests — no ingredient getters — so we can't enumerate the inputs; describe the station + result. */
    private static String formatSmithing(SmithingRecipe recipe, ItemStack result) {
        return "[smithing] (smithing table: template + base + addition) -> makes " + result.getCount();
    }

    /** Name an ingredient: a single item directly, a shared-suffix tag as "planks (any)", else every
     *  member — so a category ingredient doesn't mislead the model into one specific item.
     *  Package-visible: the craft tool names its material shortfalls with the same vocabulary. */
    static String describeIngredient(Ingredient ing) {
        List<String> paths = java.util.Arrays.stream(ing.getItems())   // 1.21.1: getItems() -> ItemStack[]
                .map(s -> BuiltInRegistries.ITEM.getKey(s.getItem()).getPath())
                .distinct()
                .toList();
        if (paths.isEmpty()) {
            return "?";
        }
        if (paths.size() == 1) {
            return paths.get(0);
        }
        String suffix = commonSuffixToken(paths);
        if (suffix != null) {
            return suffix + "(any)";
        }
        return "any[" + String.join("/", paths) + "]";
    }

    private static String commonSuffixToken(List<String> paths) {
        String token = null;
        for (String p : paths) {
            int u = p.lastIndexOf('_');
            String t = u < 0 ? p : p.substring(u + 1);
            if (token == null) {
                token = t;
            } else if (!token.equals(t)) {
                return null;
            }
        }
        return token;
    }

    // ---- scan container ----

    /**
     * 一格方块里装着什么,一行一个条目,按输出预算分页({@link Listing})。
     *
     */
    public String inspectBlockStorage(BlockPos pos, NumenPlayer self, CommandArgs args) {
        BlockState state = self.level().getBlockState(pos);
        String coord = pos.getX() + "," + pos.getY() + "," + pos.getZ();
        if (state.isAir()) {
            return TaskResult.fail(com.dwinovo.numen.agent.script.ErrorKind.NOT_FOUND, "block at " + coord
                    + " is air — nothing to read.", null).toJson();
        }
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        List<String> caps = Services.CAPS.describe(self.level(), pos);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("block", com.dwinovo.numen.cli.Shapes.block(pos, state));
        data.put("storage", caps);
        if (caps.isEmpty()) {
            return TaskResult.ok(id + " at " + coord + " exposes no item/fluid/energy storage "
                    + "(not a machine/tank/battery, or it keeps its state elsewhere). "
                    + "If it has a GUI, right-click it (numen.use.block) then numen.use.gui().", data).toJson();
        }
        return new Listing(id + " at " + coord + ":", caps, "").result(args, data).toJson();
    }
}
