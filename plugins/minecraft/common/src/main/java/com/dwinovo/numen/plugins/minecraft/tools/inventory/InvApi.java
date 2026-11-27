package com.dwinovo.numen.plugins.minecraft.tools.inventory;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.plugins.minecraft.PlayerInv;
import com.dwinovo.numen.plugins.minecraft.task.inventory.DropCompanionTask;
import com.dwinovo.numen.plugins.minecraft.task.inventory.DropItemsTaskRecord;
import com.dwinovo.numen.plugins.minecraft.task.inventory.EatCompanionTask;
import com.dwinovo.numen.plugins.minecraft.task.inventory.EatItemTaskRecord;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.plugins.minecraft.tools.Books;
import com.dwinovo.numen.plugins.minecraft.tools.CraftOps;
import com.dwinovo.numen.plugins.minecraft.tools.ItemNames;
import com.dwinovo.numen.plugins.minecraft.tools.station.Stations;
import com.dwinovo.numen.api.knowledge.InfoPage;
import com.dwinovo.numen.api.knowledge.Recipe;
import com.dwinovo.numen.api.knowledge.RecipePage;
import com.dwinovo.numen.plugins.minecraft.tools.RecipeBook;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Job;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.Rest;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.sdk.StackInfo;
import com.dwinovo.numen.api.sdk.StackRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code minecraft.inv}:背包里的东西——有什么、有几件,配方、能合什么、合一次(在她自己的 2x2 格里,或点名的一张工作台上),吃、丢。
 *
 * <p>都在服务端执行。查询与在自己格里的合成当场回;在工作台上合、丢是有界短活(经 {@link ServerCall#sync}),丢每次都过权限层,可能等主人
 * 答复;吃是占身体的活——咀嚼要时间,收尾才回到程序。挑配方、找工作台的组合是 Lua 模块 {@code minecraft.inv.make},不在这一组的 Java 里。
 */
public final class InvApi {

    private static final int DROP_MAX_COUNT = 999;

    private InvApi() {}

    public static void install(NumenApi numen) {
        numen.api("inv", "Your inventory and what you know about items: what you carry, recipes, uses, item facts, finding items by name, crafting, eating, dropping.", InvApi.class);
    }

    /** 查哪一样。 */
    public record ItemQuery(@Doc("The item.") Item item) {}

    @Fn("How an item is made, like JEI: every recipe in the game's recipe list that outputs it.")
    @Example("for _, r in ipairs(minecraft.inv.recipes(\"minecraft:iron_pickaxe\").recipes) do print(r.id, r.category) end")
    @Note("The recipe list holds the recipes of every mod: crafting, cooking, stonecutting and smithing in full, a mod's "
            + "machine types with their category, inputs and product only. Chance, time, heat, energy and which machine does "
            + "it are not in the game's recipe list, so they are never in the result and never guessed; another plugin that "
            + "knows more (JEI on your owner's client, say) says so in its own guide.")
    @Note("Instant. At most 40 recipes come back, omitted says how many more matched. Empty means the item is mined or "
            + "traded, not made.")
    @Note("To make it: crafting is minecraft.inv.make(item, N) (picks the recipe and the grid) or minecraft.inv.craft(id), with "
            + "{at = cell} for a 3x3 recipe; smelting, blasting, smoking minecraft.inv.smelt(furnace, input, N); stonecutter: "
            + "minecraft.stonecutter.cut(stonecutter, input, item); smithing: minecraft.smithing.forge(smithing_table, template, "
            + "base, addition); a mod's machine: the catalysts (or the machine block of the category) are where it is made; "
            + "minecraft.container.put and minecraft.container.take move items into and out of a machine.")
    @SeeAlso({"minecraft.inv.make", "minecraft.inv.craft", "minecraft.inv.uses"})
    public static RecipePage recipes(ServerCall call, ItemQuery args) {
        return RecipeBook.recipesFor(args.item(), call.her());
    }

    @Fn("What an item can be used for, like JEI's uses page: every recipe in the game's recipe list that takes it as an input.")
    @Note("Same shape and limits as minecraft.inv.recipes; each entry is a recipe with the item among its inputs (a tag input "
            + "such as planks(any) counts when the item is in the tag) and says what it makes. Empty means no recipe takes "
            + "it; it may still be a fuel, food or a tool: minecraft.inv.info says.")
    @SeeAlso({"minecraft.inv.recipes", "minecraft.inv.info"})
    public static RecipePage uses(ServerCall call, ItemQuery args) {
        return RecipeBook.usesOf(args.item(), call.her());
    }

    @Fn("What the game itself knows about an item: stack size, durability, food values, the block it places, its item tags.")
    @Note("It does not say how the item is made or used: that is minecraft.inv.recipes and minecraft.inv.uses. Instant.")
    @SeeAlso({"minecraft.inv.recipes", "minecraft.inv.uses"})
    public static InfoPage info(ServerCall call, ItemQuery args) {
        return new InfoPage(Optional.of(VanillaKnowledge.facts(args.item())), List.of());
    }

    /** 背包 36 格里的东西按"物品与组件都相同"合成一种,先见先列;每一种带着它的记号,原样交回来就点名了这一种。 */
    @Fn("What you carry in your backpack, one entry per kind of item.")
    @Example("for _, s in ipairs(minecraft.inv.items()) do print(s.item, s.count, s.kind) end")
    @Example("for _, s in ipairs(minecraft.inv.items()) do\n  local e = s.details.enchantments\n  if e and "
            + "e[\"minecraft:silk_touch\"] then minecraft.gear.hold(s) end\nend")
    @Note("Instant and read-only. Only the backpack (hotbar included): what you wear and hold in the off hand is in "
            + "<worn>. Empty means an empty table.")
    @Note("Stacks of one item that differ — a shulker box with diamonds in it and an empty one, two different potions — "
            + "are separate entries, told apart by their details. Naming only the item in a call picks the stack in "
            + "your main hand, else the first slot, and the result says which. To use another one, pass its entry "
            + "where the function takes an item you carry: minecraft.gear.hold(minecraft.inv.items()[2]).")
    @Note("details is a table with one named entry per kind of fact, there only when the stack differs from a new "
            + "item in that way; a plain item has an empty table. display = {name, lore}: a custom name, and the "
            + "lines written on the item. enchantments and stored_enchantments (an enchanted book) = level by "
            + "enchantment id. contents = how many by item id, for what a shulker box or a bundle holds. potion = "
            + "{id, effects}: the potion id (minecraft:strong_healing) and the ids of effects added on top. banner "
            + "= {layers}, each {pattern, color} from the bottom up. head = {owner, uuid}. bees = {count}. "
            + "durability = {used, max}, once it has been used. A mod's integration adds entries of its own for "
            + "its items.")
    @Note("An entry names its kind for as long as you carry that kind, wherever its stacks lie. Once you carry none "
            + "of it (a shulker box whose contents changed is another kind), passing the entry fails with not_found.")
    @SeeAlso("minecraft.inv.count")
    public static List<StackInfo> items(ServerCall call) {
        Inventory inventory = call.her().getInventory();
        List<StackInfo> out = new ArrayList<>();
        for (Stacks.Kind kind : Stacks.kinds(inventory.items.subList(0,
                Math.min(PlayerInv.BUILDABLE_SLOTS, inventory.items.size())))) {
            out.add(StackInfo.of(kind, StackLabel.details(call.her(), kind.sample()), call.her().registryAccess()));
        }
        return out;
    }

    /** 数哪一样。 */
    public record Count(@Doc("The item to count.") Item item) {}

    @Fn("How many of one item you carry in your backpack.")
    @Example("if minecraft.inv.count(\"minecraft:coal\") < 8 then print(\"low on coal\") end")
    @Note("Instant and read-only; 0 when you carry none.")
    @SeeAlso("minecraft.inv.items")
    public static int count(ServerCall call, Count args) {
        return PlayerInv.carriedCount(call.her().getInventory(), args.item());
    }

    /** 找哪个名字。 */
    public record Lookup(@Doc("Part of the item's name as your owner sees it in the game, in their language, or of its id: "
            + "iron ingot, 铁锭, rail.") String name) {}

    @Fn("Find items by the name your owner reads in the game, in their language.")
    @Example("for _, m in ipairs(minecraft.inv.lookup(\"铁锭\").items) do print(m.item, m.name) end")
    @Note("Read-only, answered by your owner's client, which holds the language files: it waits for them, fails with "
            + "timeout when they do not answer in time (the same call is safe to repeat), and cannot be answered when "
            + "your owner is offline. Nothing in the world or in any other mod's screens is touched.")
    @Note("Matches the name in your owner's language, else the id, case ignored: whole name first, then names that start "
            + "with it, then names that contain it, then ids. Up to " + ItemNames.MAX_MATCHES + " come back, omitted says "
            + "how many more matched; a longer name narrows it. Use the item ids it returns with the other minecraft.inv "
            + "functions.")
    @SeeAlso({"minecraft.inv.recipes", "minecraft.inv.info"})
    public static ItemNames.Matches lookup(ClientCall call, Lookup args) {
        return ItemNames.find(args.name());
    }

    @Fn("Every crafting recipe you can craft right now from what you carry.")
    @Example("for _, r in ipairs(minecraft.inv.craftable()) do print(r.item, r.grid) end")
    @Note("Instant and read-only, like the recipe book: a grid 3 recipe is crafted at a crafting table, "
            + "minecraft.inv.craft(id, {at = cell}), a grid 2 one in your own grid, minecraft.inv.craft(id); "
            + "minecraft.inv.make(item) picks the recipe and the table for you.")
    @SeeAlso({"minecraft.inv.craft", "minecraft.inv.recipes"})
    public static List<Recipe> craftable(ServerCall call) {
        return CraftOps.craftable(call.her());
    }

    /** 照哪条配方合、要几件、在哪张工作台上。 */
    public record Craft(@Doc("The recipe's id, from minecraft.inv.recipes or minecraft.inv.craftable.") ResourceLocation recipe,
                        @Doc("How many of the item you want (1-4096).") @Omitted("one craft")
                        Optional<Integer> count,
                        @Doc("A crafting table's cell: craft there, in its 3x3 grid.")
                        @Omitted("craft in your own 2x2 grid") Optional<BlockPos> at) {}

    @Fn("Craft once: lay one recipe into a crafting grid and take the result. The grid is your own 2x2, or a "
            + "crafting table's 3x3 when you say which table with at.")
    @Example("minecraft.inv.craft(\"minecraft:oak_planks\", {count = 8})")
    @Example("minecraft.inv.craft(\"minecraft:chest\", {at = {x = 120, y = 64, z = -35}})")
    @Note("Without at it crafts where you stand, in your own grid. With at it walks up to that crafting table "
            + "when it is out of reach, crafts in its grid and closes it; the walk is told in the result. "
            + "minecraft.inv.make picks the recipe and finds the table for you.")
    @Note("One call lays up to a stack per cell, so it may craft fewer than count; more says whether another call "
            + "can make the rest.")
    @Note("Missing materials fail with kind no_material and the exact shortfall (data.missing), touching nothing "
            + "and walking nowhere.")
    @Note("Only [crafting] recipes. A furnace is minecraft.inv.smelt, a stonecutter minecraft.stonecutter.cut, a smithing "
            + "table minecraft.smithing.forge.")
    @SeeAlso({"minecraft.inv.recipes", "minecraft.inv.craftable"})
    public static Pending<CraftOps.Crafted> craft(ServerCall call, Craft args) {
        int want = Math.max(1, args.count().orElse(1));
        if (args.at().isEmpty()) {
            return Pending.of(CraftOps.craft(args.recipe(), want, call.her(), call.her().inventoryMenu));
        }
        // 配方在不在、料够不够,走到工作台之前就判:缺料的那几样在错误的数据里
        CraftOps.ready(args.recipe(), call.her());
        AtomicReference<CraftOps.Crafted> crafted = new AtomicReference<>();
        // 不挑界面的类:背着合成格的界面都认(模组的工作台),认不认得由合成那一步看格子
        return Stations.at(call, args.at().get(), null, "crafting table", List.of(new Deed((menu, her) -> {
            crafted.set(CraftOps.craft(args.recipe(), want, her, menu));
            return "crafted " + crafted.get().crafted() + " with " + args.recipe();
        })), crafted::get);
    }

    /** 吃哪一样。 */
    public record Eat(@Doc("The food or drink to consume: something you carry.") StackRef item) {}

    @Fn("Eat or drink something from your inventory.")
    @Example("minecraft.inv.eat(\"minecraft:cooked_beef\")")
    @Note("A body job: the program waits until she has finished eating.")
    @Note("A real timed action: only when the chewing finishes do hunger, saturation and the item's effects (a golden "
            + "apple's absorption) apply. Health then regenerates from saturation, the same as a real player's.")
    @Note("Refused at once, keeping the food, when you don't carry it or it isn't food or drink; fails the same way "
            + "when you are already full.")
    public static Job<EatCompanionTask.Ate> eat(ServerCall call, Eat args) {
        Stacks.Which which = args.item().in(call.her().getInventory(), call.her().registryAccess());
        return Job.of(new EatItemTaskRecord(call, which, BuiltInRegistries.ITEM.getKey(which.item()).getPath()));
    }

    /** 丢哪一样、丢几件。 */
    public record Drop(@Doc("The item to drop.") StackRef item,
                       @Doc("How many to drop (1-999); more than you carry of that stack drops all of it.")
                       @Omitted("drop all you carry of that stack") Optional<Integer> count) {}

    /** 有界短活:丢东西每次都过权限层,可能挂着等主人。{@code count} 没给就是她带着的全部(至少一件,一件都没有由任务的前置条件如实说)。 */
    @Fn("Drop items from your inventory on the ground in front of you.")
    @Example("minecraft.inv.drop(\"minecraft:cobblestone\", {count = 32})")
    @Example("minecraft.inv.drop(\"rotten_flesh\")")
    @Note("Asks your owner first unless their rules allow it; the call waits for the answer.")
    @Note("Dropped items despawn after 5 minutes. To store things, minecraft.container.put puts them into a chest instead.")
    @Note("Drops one kind of stack: the one in your main hand, else the first slot (minecraft.inv.items lists the kinds, "
            + "say a shulker box with contents and an empty one), or the kind whose entry of minecraft.inv.items() you pass "
            + "as item. The result names the stack the way <inventory> writes it, with what is known about it in "
            + "brackets after the id, and the other stacks of the item that stay.")
    @Note("Returns how many were dropped and how many remain.")
    @SeeAlso("minecraft.use.block")
    public static Pending<DropCompanionTask.Dropped> drop(ServerCall call, Drop args) {
        Stacks.Which which = args.item().in(call.her().getInventory(), call.her().registryAccess());
        int carried = Stacks.pick(call.her().getInventory(), which).map(Stacks.Pick::count).orElse(0);
        int n = args.count().orElse(Math.max(1, carried));
        return call.sync(new DropItemsTaskRecord(call, which, Math.clamp(n, 1, DROP_MAX_COUNT),
                BuiltInRegistries.ITEM.getKey(which.item()).getPath()));
    }

    /** 读哪一种书。 */
    public record Read(@Doc("A book and quill (minecraft:writable_book) or a written book (minecraft:written_book).") Item item) {}

    @Fn("Read the books of one kind you carry: title, author and the text of every page.")
    @Example("for _, b in ipairs(minecraft.inv.read(\"minecraft:written_book\")) do print(b.title, b.author, b.pages[1]) end")
    @Note("Instant and read-only; one entry per book in your inventory (the off hand too), empty when you carry none. A "
            + "book on a lectern is read with minecraft.lectern.read.")
    @SeeAlso({"minecraft.inv.write", "minecraft.inv.items"})
    public static List<Books.Book> read(ServerCall call, Read args) {
        return Books.carried(call.her(), args.item());
    }

    /** 写什么。 */
    public record Write(@Doc("The pages, at most 100 of at most 1024 characters.") @Rest List<String> pages,
                        @Doc("Sign the book with this title (up to 32 characters): it becomes a written book and can no "
                                + "longer be edited.") @Omitted("keep it a book and quill") Optional<String> title) {}

    @Fn("Write pages into a book and quill you carry, and optionally sign it.")
    @Example("minecraft.inv.write({\"Day 1: I found iron.\", \"Day 2: I built a house.\"})")
    @Example("minecraft.inv.write({\"My story\"}, {title = \"Diary\"})")
    @Note("Replaces what the book held. A book and quill in your backpack is taken into your hand first, like minecraft.gear.hold, "
            + "and the reply says so. Signing makes a written book under your name; the book is then read with minecraft.inv.read.")
    @Note("Fails when you carry no book and quill (`minecraft.inv.make(\"minecraft:writable_book\")` crafts one) or when Minecraft "
            + "did not take the text.")
    @SeeAlso({"minecraft.inv.read", "minecraft.gear.hold"})
    public static Pending<Books.Book> write(ServerCall call, Write args) {
        return Books.write(call.her(), args.pages(), args.title().orElse(null));
    }
}
