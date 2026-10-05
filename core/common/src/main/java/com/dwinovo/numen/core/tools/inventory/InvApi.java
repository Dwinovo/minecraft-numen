package com.dwinovo.numen.core.tools.inventory;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.task.inventory.DropCompanionTask;
import com.dwinovo.numen.core.task.inventory.DropItemsTaskRecord;
import com.dwinovo.numen.core.task.inventory.EatCompanionTask;
import com.dwinovo.numen.core.task.inventory.EatItemTaskRecord;
import com.dwinovo.numen.core.tools.CraftOps;
import com.dwinovo.numen.core.tools.Recipe;
import com.dwinovo.numen.core.tools.RecipeBook;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Job;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code numen.inv}:背包里的东西——有什么、有几件,配方、能合什么、在开着的格里合一次,吃、丢。
 *
 * <p>都在服务端执行。查询与合成当场回;丢是有界短活(经 {@link ServerCall#sync}),每次都过权限层,可能等主人答复;吃是占身体的活——咀嚼要
 * 时间,收尾才回到程序。挑配方、找工作台、走过去、开合关的组合是 Lua 模块 {@code numen.inv.make},不在这一组的 Java 里。
 */
public final class InvApi {

    private static final int DROP_MAX_COUNT = 999;

    private InvApi() {}

    public static void install(NumenApi numen) {
        numen.api("inv", "Your inventory: what you carry, recipes, crafting, eating, dropping.", InvApi.class);
    }

    /** 背包里的一样东西。 */
    public record Stack(@Doc("The item id, minecraft:cobblestone.") String item,
                        @Doc("How many you carry, all stacks together.") int count) {}

    /** 背包 36 格里的东西按种类合起来,先见先列。 */
    @Fn("What you carry in your backpack, one entry per kind of item.")
    @Example("for _, s in ipairs(numen.inv.items()) do print(s.item, s.count) end")
    @Note("Instant and read-only. Only the backpack (hotbar included): what you wear and hold in the off hand is in "
            + "<worn>. Empty means an empty table.")
    @SeeAlso("numen.inv.count")
    public static List<Stack> items(ServerCall call) {
        Inventory inventory = call.her().getInventory();
        Map<Item, Integer> totals = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(PlayerInv.BUILDABLE_SLOTS, inventory.items.size()); i++) {
            ItemStack s = inventory.items.get(i);
            if (!s.isEmpty()) {
                totals.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        List<Stack> out = new ArrayList<>();
        totals.forEach((item, n) -> out.add(new Stack(BuiltInRegistries.ITEM.getKey(item).toString(), n)));
        return out;
    }

    /** 数哪一样。 */
    public record Count(@Doc("The item to count.") Item item) {}

    @Fn("How many of one item you carry in your backpack.")
    @Example("if numen.inv.count(\"minecraft:coal\") < 8 then print(\"low on coal\") end")
    @Note("Instant and read-only; 0 when you carry none.")
    @SeeAlso("numen.inv.items")
    public static int count(ServerCall call, Count args) {
        return PlayerInv.carriedCount(call.her().getInventory(), args.item());
    }

    /** 要做哪一样。 */
    public record Recipes(@Doc("The item you want to make.") Item item) {}

    @Fn("How an item is made, like JEI: every recipe that outputs it, at every station.")
    @Example("for _, r in ipairs(numen.inv.recipes(\"minecraft:diamond_pickaxe\")) do print(r.id, r.station) end")
    @Note("Instant and read-only. Every recipe comes with its id; a crafting recipe says the smallest grid it fits "
            + "and, when you are short, what you lack.")
    @Note("No recipe found means the item is mined or traded, not made: an empty table.")
    @Note("To make it — crafting: numen.inv.make(<item>, N) picks the recipe and the grid (a crafting table for a "
            + "3x3 one) and crafts; numen.inv.craft(<id>) crafts once in the grid you have open. Smelting, blasting, "
            + "smoking: numen.inv.smelt(<furnace>, <input>, N) loads the furnace, waits and takes the output. "
            + "Stonecutter: numen.use.block it, numen.gui.put the input (the menu routes it in), take the output. "
            + "Smithing: numen.use.block it, numen.gui.view, then numen.gui.move template + base + addition each into "
            + "its own slot.")
    @SeeAlso({"numen.inv.craft", "numen.inv.craftable"})
    public static List<Recipe> recipes(ServerCall call, Recipes args) {
        return RecipeBook.recipesFor(args.item(), call.her());
    }

    @Fn("Every crafting recipe you can craft right now from what you carry.")
    @Example("for _, r in ipairs(numen.inv.craftable()) do print(r.item, r.grid) end")
    @Note("Instant and read-only, like the recipe book: a grid 3 recipe still needs a crafting table open to craft "
            + "it. numen.inv.craft(id) crafts one in the open grid; numen.inv.make(item) picks the recipe and the "
            + "table for you.")
    @SeeAlso({"numen.inv.craft", "numen.inv.recipes"})
    public static List<Recipe> craftable(ServerCall call) {
        return CraftOps.craftable(call.her());
    }

    /** 照哪条配方合、要几件。 */
    public record Craft(@Doc("The recipe's id, from numen.inv.recipes or numen.inv.craftable.") ResourceLocation recipe,
                        @Doc("How many of the item you want (1-4096).") @Omitted("one craft")
                        Optional<Integer> count) {}

    @Fn("Craft once in the crafting grid you have open: lay one recipe into it and take the result.")
    @Example("numen.inv.craft(\"minecraft:oak_planks\", {count = 8})")
    @Example("numen.inv.craft(\"minecraft:stick\", {count = 16})")
    @Note("The grid is the one open now: your own 2x2 when nothing else is; a 3x3 recipe needs a crafting table opened "
            + "with numen.use.block first. It never walks, opens or closes anything; numen.inv.make picks the recipe "
            + "and the table for you.")
    @Note("One call lays up to a stack per cell, so it may craft fewer than count; more says whether another call "
            + "can make the rest.")
    @Note("Missing materials fail with kind no_material and the exact shortfall (data.missing), touching nothing.")
    @Note("Only [crafting] recipes. A furnace is numen.inv.smelt; other stations are numen.use.block, then the numen.gui "
            + "functions.")
    @SeeAlso({"numen.inv.recipes", "numen.inv.craftable"})
    public static CraftOps.Crafted craft(ServerCall call, Craft args) {
        return CraftOps.craft(args.recipe(), Math.max(1, args.count().orElse(1)), call.her());
    }

    /** 吃哪一样。 */
    public record Eat(@Doc("The food or drink to consume: something you carry.") Item item) {}

    @Fn("Eat or drink something from your inventory.")
    @Example("numen.inv.eat(\"minecraft:cooked_beef\")")
    @Note("A body job: the program waits until she has finished eating.")
    @Note("A real timed action: only when the chewing finishes do hunger, saturation and the item's effects (a golden "
            + "apple's absorption) apply. Health then regenerates from saturation, the same as a real player's.")
    @Note("Refused at once, keeping the food, when you don't carry it or it isn't food or drink; fails the same way "
            + "when you are already full.")
    public static Job<EatCompanionTask.Ate> eat(ServerCall call, Eat args) {
        return Job.of(new EatItemTaskRecord(call, args.item(), BuiltInRegistries.ITEM.getKey(args.item()).getPath()));
    }

    /** 丢哪一样、丢几件。 */
    public record Drop(@Doc("The item to drop.") Item item,
                       @Doc("How many to drop (1-999); more than you carry drops all you have of it.")
                       @Omitted("drop all you carry of it") Optional<Integer> count) {}

    /** 有界短活:丢东西每次都过权限层,可能挂着等主人。{@code count} 没给就是她带着的全部(至少一件,一件都没有由任务的前置条件如实说)。 */
    @Fn("Drop items from your inventory on the ground in front of you.")
    @Example("numen.inv.drop(\"minecraft:cobblestone\", {count = 32})")
    @Example("numen.inv.drop(\"rotten_flesh\")")
    @Note("Asks your owner first unless their rules allow it; the call waits for the answer.")
    @Note("Dropped items despawn after 5 minutes. To store things, numen.inv.store puts them into a chest instead.")
    @Note("Returns how many were dropped and how many remain.")
    @SeeAlso("numen.use.block")
    public static Pending<DropCompanionTask.Dropped> drop(ServerCall call, Drop args) {
        Item item = args.item();
        int carried = PlayerInv.count(call.her().getInventory(), item);
        int n = args.count().orElse(Math.max(1, carried));
        return call.sync(new DropItemsTaskRecord(call, item, Math.clamp(n, 1, DROP_MAX_COUNT),
                BuiltInRegistries.ITEM.getKey(item).getPath()));
    }
}
