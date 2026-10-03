package com.dwinovo.numen.core.tools.inventory;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.tools.CraftOps;
import com.dwinovo.numen.core.tools.InventoryOps;
import com.dwinovo.numen.core.tools.QueryExtraOps;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code inv}:背包里的东西——有什么、有几件,配方、能合什么、在开着的格里合一次,吃、丢。
 *
 * <p>都在服务端执行,都不提升成快捷工具。查询与合成当场回;丢是有界短活({@code runSync}),每次都过权限层,可能等主人答复;
 * 吃是长活——咀嚼要时间,受理即回执,吃完发 {@code task_finished}。挑配方、找工作台、走过去、开合关的组合是 Lua 模块
 * {@code numen.inv.make},不在这一组的 Java 里。
 */
public final class InvCommands {

    static final String GROUP = "inv";
    static final String ITEMS = "items";
    static final String COUNT = "count";
    static final String RECIPES = "recipes";
    static final String CRAFTABLE = "craftable";
    static final String CRAFT = "craft";
    static final String EAT = "eat";
    static final String DROP = "drop";

    private static final Param<ResourceLocation> COUNTED = Param.required("item", ArgType.id(),
            "The item to count.");
    private static final Param<ResourceLocation> RECIPE_ITEM = Param.required("item", ArgType.id(),
            "The item you want to make.");
    private static final Param<ResourceLocation> RECIPE_ID = Param.required("recipe", ArgType.id(),
            "The recipe's id.")
            .values("an id from numen.inv.recipes or numen.inv.craftable");
    private static final Param<Integer> CRAFT_COUNT = Param.optional("count", ArgType.integer(1, 4096),
            "How many of the item you want.")
            .whenOmitted("one craft");
    private static final Param<ResourceLocation> FOOD = Param.required("item", ArgType.id(),
            "The food or drink to consume.")
            .values("something you carry");
    private static final Param<ResourceLocation> DROP_ITEM = Param.required("item", ArgType.id(),
            "The item to drop.");
    private static final Param<Integer> DROP_COUNT = Param.optional("count", ArgType.integer(1, 999),
            "How many to drop; more than you carry drops all you have of it.")
            .whenOmitted("drop all you carry of it");

    /** 背包里的一样东西:{@code numen.inv.items} 返回的那一项。 */
    private static final ScriptType STACK = ScriptType.table(
            ScriptType.field("item", ScriptType.STRING, "The item id, minecraft:cobblestone."),
            ScriptType.field("count", ScriptType.INTEGER, "How many you carry, all stacks together."));

    private static final CraftOps CRAFTING = new CraftOps();
    private static final QueryExtraOps RECIPE_BOOK = new QueryExtraOps();
    private static final InventoryOps INVENTORY = new InventoryOps();

    private InvCommands() {}

    /** 回执与别的命令的帮助里提到这一组的动作时写的那一行。 */
    static String line(String action) {
        return GROUP + " " + action;
    }

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Your inventory: what you carry, recipes, crafting, eating, dropping.",
                InvCommands::actions);
    }

    private static void actions(CommandGroup inv) {
        inv.declare(QueryExtraOps.RECIPE);
        inv.server(ITEMS, "What you carry in your backpack, one entry per kind of item.",
                        (src, args) -> src.reply(items(src.companion().getInventory())))
                .returns("items", ScriptType.listOf(STACK))
                .example("for _, s in ipairs(numen.inv.items()) do print(s.item, s.count) end")
                .note("Instant and read-only. Only the backpack (hotbar included): what you wear and hold in the off "
                        + "hand is in <worn>. Empty means an empty table.")
                .seeAlso(line(COUNT));
        inv.server(COUNT, "How many of one item you carry in your backpack.",
                        (src, args) -> src.reply(count(src.companion().getInventory(), args.get(COUNTED))), COUNTED)
                .returns("count", ScriptType.INTEGER)
                .example("if numen.inv.count(\"minecraft:coal\") < 8 then print(\"low on coal\") end")
                .note("Instant and read-only; 0 when you carry none.")
                .seeAlso(line(ITEMS));
        inv.server(RECIPES, "How an item is made, like JEI: every recipe that outputs it, at every station.",
                        InvCommands::recipes, RECIPE_ITEM, Listing.PAGE)
                .returns(QueryExtraOps.RECIPES, ScriptType.listOf(QueryExtraOps.RECIPE.type()))
                .example("for _, r in ipairs(numen.inv.recipes(\"minecraft:diamond_pickaxe\")) do print(r.id, "
                        + "r.station) end")
                .note("Instant and read-only. Every recipe is listed with its id; a long list comes in pages. A "
                        + "crafting recipe says the smallest grid it fits and, when you are short, what you lack.")
                .note("No recipe found means the item is mined or traded, not made: an empty table.")
                .seeAlso(line(CRAFT), line(CRAFTABLE));
        inv.server(CRAFTABLE, "Every crafting recipe you can craft right now from what you carry.",
                        (src, args) -> src.reply(CRAFTING.craftable(src.companion(), args)), Listing.PAGE)
                .returns(QueryExtraOps.RECIPES, ScriptType.listOf(QueryExtraOps.RECIPE.type()))
                .example("for _, r in ipairs(numen.inv.craftable()) do print(r.item, r.grid) end")
                .note("Instant and read-only, like the recipe book: a grid 3 recipe still needs a crafting table "
                        + "open to craft it.")
                .seeAlso(line(CRAFT), line(RECIPES));
        inv.server(CRAFT, "Craft once in the crafting grid you have open: lay one recipe into it and take the "
                        + "result.",
                        InvCommands::craft, RECIPE_ID, CRAFT_COUNT)
                .returns(ScriptType.table(ScriptType.field("crafted", ScriptType.INTEGER, null),
                        ScriptType.field("carrying", ScriptType.INTEGER, "How many you carry now.")))
                .example("numen.inv.craft(\"minecraft:oak_planks\", {count = 8})")
                .example("numen.inv.craft(\"minecraft:stick\", {count = 16})")
                .note("The grid is the one open now: your own 2x2 when nothing else is; a 3x3 recipe needs a "
                        + "crafting table opened with numen.use.block first. It never walks, opens or closes "
                        + "anything; numen.inv.make picks the recipe and the table for you.")
                .note("One call lays up to a stack per cell, so it may craft fewer than count; the result says how "
                        + "many and whether another call can make the rest.")
                .note("Missing materials fail with the exact shortfall (data.missing), touching nothing.")
                .note("Only [crafting] recipes. A furnace is numen.inv.smelt; other stations are numen.use.block, "
                        + "then the numen.gui functions.")
                .seeAlso(line(RECIPES), line(CRAFTABLE));
        inv.server(EAT, "Eat or drink something from your inventory.",
                        InvCommands::eat, FOOD)
                .returns(ScriptType.table(ScriptType.field("item", ScriptType.STRING, null),
                        ScriptType.field("hp", ScriptType.NUMBER, null),
                        ScriptType.field("hunger", ScriptType.INTEGER, null)))
                .example("numen.inv.eat(\"minecraft:cooked_beef\")")
                .note("Background work: the result arrives as a task_finished event.")
                .note("A real timed action: only when the chewing finishes do hunger, saturation and the item's "
                        + "effects (a golden apple's absorption) apply. Health then regenerates from saturation, "
                        + "the same as a real player's.")
                .note("Refused at once, keeping the food, when you don't carry it or it isn't food or drink; "
                        + "fails the same way when you are already full.");
        inv.server(DROP, "Drop items from your inventory on the ground in front of you.",
                        InvCommands::drop, DROP_ITEM, DROP_COUNT)
                .returns(ScriptType.table(ScriptType.field("item", ScriptType.STRING, null),
                        ScriptType.field("dropped", ScriptType.INTEGER, null),
                        ScriptType.field("remaining_in_inventory", ScriptType.INTEGER, null)))
                .example("numen.inv.drop(\"minecraft:cobblestone\", {count = 32})")
                .example("numen.inv.drop(\"rotten_flesh\")")
                .note("Asks your owner first unless their rules allow it; the call waits for the answer.")
                .note("Dropped items despawn after 5 minutes. To store things, numen.inv.store puts them into a "
                        + "chest instead.")
                .note("Returns how many were dropped and how many remain.")
                .seeAlso("use block");
    }

    /** 背包 36 格里的东西按种类合起来,先见先列。 */
    private static String items(Inventory inventory) {
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(PlayerInv.BUILDABLE_SLOTS, inventory.items.size()); i++) {
            ItemStack s = inventory.items.get(i);
            if (!s.isEmpty()) {
                totals.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        List<Map<String, Object>> items = new ArrayList<>();
        List<String> words = new ArrayList<>();
        totals.forEach((item, n) -> {
            String id = BuiltInRegistries.ITEM.getKey(item).toString();
            items.add(Map.of("item", id, "count", n));
            words.add(n + "x " + BuiltInRegistries.ITEM.getKey(item).getPath());
        });
        return TaskResult.ok(items.isEmpty() ? "your backpack is empty." : "carrying " + String.join(", ", words),
                Map.of("items", items)).toJson();
    }

    private static String count(Inventory inventory, ResourceLocation id) {
        int n = PlayerInv.carriedCount(inventory, ToolArgs.parseItem(id.toString()));
        return TaskResult.ok("carrying " + n + "x " + id.getPath(), Map.of("count", n)).toJson();
    }

    private static void recipes(ServerSource src, CommandArgs args) {
        src.reply(RECIPE_BOOK.lookupRecipe(args.get(RECIPE_ITEM).toString(), src.companion(), args));
    }

    private static void craft(ServerSource src, CommandArgs args) {
        src.reply(CRAFTING.craft(args.get(RECIPE_ID), args.get(CRAFT_COUNT), src.companion()));
    }

    /** 长活:咀嚼要时间,一口一口吃到饱可能更久,占着一轮对话不合理;受理即回执,吃完发 task_finished。 */
    private static void eat(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, INVENTORY.eatItem(src, args.get(FOOD).toString()));
    }

    /** 有界短活:丢东西每次都过权限层,可能挂着等主人。 */
    private static void drop(ServerSource src, CommandArgs args) {
        TaskDispatch.runSync(src.companion(),
                INVENTORY.dropItems(src, args.get(DROP_ITEM).toString(), args.get(DROP_COUNT)), src::reply);
    }
}
