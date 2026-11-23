package com.dwinovo.numen.tools.inventory;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.PlayerInv;
import com.dwinovo.numen.task.inventory.DropCompanionTask;
import com.dwinovo.numen.task.inventory.DropItemsTaskRecord;
import com.dwinovo.numen.task.inventory.EatCompanionTask;
import com.dwinovo.numen.task.inventory.EatItemTaskRecord;
import com.dwinovo.numen.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.tools.Books;
import com.dwinovo.numen.tools.CraftOps;
import com.dwinovo.numen.tools.ItemNames;
import com.dwinovo.numen.tools.station.Stations;
import com.dwinovo.numen.api.knowledge.Recipe;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code numen.inv}:背包里的东西——有什么、有几件,配方、能合什么、合一次(在她自己的 2x2 格里,或点名的一张工作台上),吃、丢。
 *
 * <p>都在服务端执行。查询与在自己格里的合成当场回;在工作台上合、丢是有界短活(经 {@link ServerCall#sync}),丢每次都过权限层,可能等主人
 * 答复;吃是占身体的活——咀嚼要时间,收尾才回到程序。挑配方、找工作台的组合是 Lua 模块 {@code numen.inv.make},不在这一组的 Java 里。
 */
public final class InvApi {

    private static final int DROP_MAX_COUNT = 999;

    private InvApi() {}

    public static void install(NumenApi numen) {
        numen.api("inv", "Your inventory and what you know about items: what you carry, recipes, uses, item facts, finding items by name, crafting, eating, dropping.", InvApi.class);
        VanillaKnowledge.install(numen);
    }

    /** 背包里的一种东西。 */
    public record Stack(@Doc("The item id, minecraft:cobblestone.") String item,
                        @Doc("How many you carry of this kind, all its stacks together.") int count,
                        @Doc("What tells this kind apart from the same item without it: a shulker box's contents, a "
                                + "custom name, enchantments, a potion's effect. Absent for a plain item.")
                        Optional<String> detail) {

        /** 一叠(或合起来的一种)东西在模型眼里的样子:物品 id、件数,区别由 {@link StackLabel#detail} 说。背包清单与窗口里的各项同用。 */
        public static Stack of(ItemStack sample, int count) {
            return of(sample, count, StackLabel.detail(sample));
        }

        /** 同上,描述由调用方给({@link StackLabel#details} 排过重的)。 */
        public static Stack of(ItemStack sample, int count, String detail) {
            return new Stack(BuiltInRegistries.ITEM.getKey(sample.getItem()).toString(), count,
                    detail.isEmpty() ? Optional.empty() : Optional.of(detail));
        }
    }

    /** 背包 36 格里的东西按"物品与组件都相同"合成一种,先见先列。 */
    @Fn("What you carry in your backpack, one entry per kind of item.")
    @Example("for _, s in ipairs(numen.inv.items()) do print(s.item, s.count, s.detail) end")
    @Note("Instant and read-only. Only the backpack (hotbar included): what you wear and hold in the off hand is in "
            + "<worn>. Empty means an empty table.")
    @Note("Stacks of one item that differ — a shulker box with diamonds in it and an empty one, two different potions — "
            + "are separate entries, each with a detail. Naming only the item in a call picks the stack in your main "
            + "hand, else the first slot, and the result says which. To use another one, move it into your main hand's "
            + "slot first: numen.gui.view lists every slot with its detail, numen.gui.move puts a stack there.")
    @SeeAlso("numen.inv.count")
    public static List<Stack> items(ServerCall call) {
        Inventory inventory = call.her().getInventory();
        List<Stacks.Kind> kinds = Stacks.kinds(inventory.items.subList(0,
                Math.min(PlayerInv.BUILDABLE_SLOTS, inventory.items.size())));
        List<String> details = StackLabel.details(kinds.stream().map(Stacks.Kind::sample).toList());
        List<Stack> out = new ArrayList<>();
        for (int i = 0; i < kinds.size(); i++) {
            out.add(Stack.of(kinds.get(i).sample(), kinds.get(i).count(), details.get(i)));
        }
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

    /** 找哪个名字。 */
    public record Lookup(@Doc("Part of the item's name as your owner sees it in the game, in their language, or of its id: "
            + "iron ingot, 铁锭, rail.") String name) {}

    @Fn("Find items by the name your owner reads in the game, in their language.")
    @Example("for _, m in ipairs(numen.inv.lookup(\"铁锭\").items) do print(m.item, m.name) end")
    @Note("Read-only, answered by your owner's client, which holds the language files: it waits for them, fails with "
            + "timeout when they do not answer in time (the same call is safe to repeat), and cannot be answered when "
            + "your owner is offline. Nothing in the world or in any other mod's screens is touched.")
    @Note("Matches the name in your owner's language, else the id, case ignored: whole name first, then names that start "
            + "with it, then names that contain it, then ids. Up to " + ItemNames.MAX_MATCHES + " come back, omitted says "
            + "how many more matched; a longer name narrows it. Use the item ids it returns with the other numen.inv "
            + "functions.")
    @SeeAlso({"numen.inv.recipes", "numen.inv.info"})
    public static ItemNames.Matches lookup(ClientCall call, Lookup args) {
        return ItemNames.find(args.name());
    }

    @Fn("Every crafting recipe you can craft right now from what you carry.")
    @Example("for _, r in ipairs(numen.inv.craftable()) do print(r.item, r.grid) end")
    @Note("Instant and read-only, like the recipe book: a grid 3 recipe is crafted at a crafting table, "
            + "numen.inv.craft(id, {at = cell}), a grid 2 one in your own grid, numen.inv.craft(id); "
            + "numen.inv.make(item) picks the recipe and the table for you.")
    @SeeAlso({"numen.inv.craft", "numen.inv.recipes"})
    public static List<Recipe> craftable(ServerCall call) {
        return CraftOps.craftable(call.her());
    }

    /** 照哪条配方合、要几件、在哪张工作台上。 */
    public record Craft(@Doc("The recipe's id, from numen.inv.recipes or numen.inv.craftable.") ResourceLocation recipe,
                        @Doc("How many of the item you want (1-4096).") @Omitted("one craft")
                        Optional<Integer> count,
                        @Doc("A crafting table's cell: craft there, in its 3x3 grid.")
                        @Omitted("craft in your own 2x2 grid") Optional<BlockPos> at) {}

    @Fn("Craft once: lay one recipe into a crafting grid and take the result. The grid is your own 2x2, or a "
            + "crafting table's 3x3 when you say which table with at.")
    @Example("numen.inv.craft(\"minecraft:oak_planks\", {count = 8})")
    @Example("numen.inv.craft(\"minecraft:chest\", {at = {x = 120, y = 64, z = -35}})")
    @Note("Without at it crafts where you stand, in your own grid. With at it walks up to that crafting table "
            + "when it is out of reach, crafts in its grid and closes it; the walk is told in the result. "
            + "numen.inv.make picks the recipe and finds the table for you.")
    @Note("One call lays up to a stack per cell, so it may craft fewer than count; more says whether another call "
            + "can make the rest.")
    @Note("Missing materials fail with kind no_material and the exact shortfall (data.missing), touching nothing "
            + "and walking nowhere.")
    @Note("Only [crafting] recipes. A furnace is numen.inv.smelt, a stonecutter numen.stonecutter.cut, a smithing "
            + "table numen.smithing.forge.")
    @SeeAlso({"numen.inv.recipes", "numen.inv.craftable"})
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
                       @Doc("How many to drop (1-999); more than you carry of that stack drops all of it.")
                       @Omitted("drop all you carry of that stack") Optional<Integer> count) {}

    /** 有界短活:丢东西每次都过权限层,可能挂着等主人。{@code count} 没给就是她带着的全部(至少一件,一件都没有由任务的前置条件如实说)。 */
    @Fn("Drop items from your inventory on the ground in front of you.")
    @Example("numen.inv.drop(\"minecraft:cobblestone\", {count = 32})")
    @Example("numen.inv.drop(\"rotten_flesh\")")
    @Note("Asks your owner first unless their rules allow it; the call waits for the answer.")
    @Note("Dropped items despawn after 5 minutes. To store things, numen.container.put puts them into a chest instead.")
    @Note("Drops one kind of stack: the one in your main hand, else the first slot (numen.inv.items lists the kinds, "
            + "say a shulker box with contents and an empty one). The result names the stack with its detail and the other "
            + "stacks of the item that stay.")
    @Note("Returns how many were dropped and how many remain.")
    @SeeAlso("numen.use.block")
    public static Pending<DropCompanionTask.Dropped> drop(ServerCall call, Drop args) {
        Item item = args.item();
        int carried = Stacks.pick(call.her().getInventory(), item).map(Stacks.Pick::count).orElse(0);
        int n = args.count().orElse(Math.max(1, carried));
        return call.sync(new DropItemsTaskRecord(call, item, Math.clamp(n, 1, DROP_MAX_COUNT),
                BuiltInRegistries.ITEM.getKey(item).getPath()));
    }

    /** 读哪一种书。 */
    public record Read(@Doc("A book and quill (minecraft:writable_book) or a written book (minecraft:written_book).") Item item) {}

    @Fn("Read the books of one kind you carry: title, author and the text of every page.")
    @Example("for _, b in ipairs(numen.inv.read(\"minecraft:written_book\")) do print(b.title, b.author, b.pages[1]) end")
    @Note("Instant and read-only; one entry per book in your inventory (the off hand too), empty when you carry none. A "
            + "book on a lectern is read with numen.lectern.read.")
    @SeeAlso({"numen.inv.write", "numen.inv.items"})
    public static List<Books.Book> read(ServerCall call, Read args) {
        return Books.carried(call.her(), args.item());
    }

    /** 写什么。 */
    public record Write(@Doc("The pages, at most 100 of at most 1024 characters.") @Rest List<String> pages,
                        @Doc("Sign the book with this title (up to 32 characters): it becomes a written book and can no "
                                + "longer be edited.") @Omitted("keep it a book and quill") Optional<String> title) {}

    @Fn("Write pages into a book and quill you carry, and optionally sign it.")
    @Example("numen.inv.write({\"Day 1: I found iron.\", \"Day 2: I built a house.\"})")
    @Example("numen.inv.write({\"My story\"}, {title = \"Diary\"})")
    @Note("Replaces what the book held. A book and quill in your backpack is taken into your hand first, like numen.gear.hold, "
            + "and the reply says so. Signing makes a written book under your name; the book is then read with numen.inv.read.")
    @Note("Fails when you carry no book and quill (`numen.inv.make(\"minecraft:writable_book\")` crafts one) or when Minecraft "
            + "did not take the text.")
    @SeeAlso({"numen.inv.read", "numen.gear.hold"})
    public static Pending<Books.Book> write(ServerCall call, Write args) {
        return Books.write(call.her(), args.pages(), args.title().orElse(null));
    }
}
