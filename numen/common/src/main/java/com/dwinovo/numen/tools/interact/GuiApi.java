package com.dwinovo.numen.tools.interact;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.task.inventory.GuiItemsTaskRecord;
import com.dwinovo.numen.tools.ContainerOps;
import com.dwinovo.numen.tools.GuiOps;
import com.dwinovo.numen.tools.menu.MenuKinds;
import com.dwinovo.numen.tools.menu.Pick;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import net.minecraft.world.item.Item;

import java.util.Optional;

/**
 * {@code numen.gui}:她打开的那个界面(一个 Window)。看它、按种类放进去拿出来、一格挪到另一格、整叠挪到另一边、关掉它。打开它是
 * {@code numen.use.block}(右键一口箱子、一台机器),它交回的就是这个 Window;Window 的方法({@code w:put}、{@code w:take}……)写在内置
 * 模块 {@code numen.gui} 里,调的就是这一组的函数。
 *
 * <p>一次一步:{@code move} 放到指定的一格,{@code quick} 像按住 Shift 点它、整叠挪到另一边;{@code put}/{@code take} 按种类搬,
 * 搬够为止。从容器里拿东西的那一步和别的身体动作一样,可能要等主人点头。
 */
public final class GuiApi {

    /** 按种类搬,一刻一步,一个界面至多几十格:期限按格数宽宽地给。 */
    private static final long ITEMS_TICKS = 60 * 20;

    private GuiApi() {}

    public static void install(NumenApi numen) {
        numen.codec(Pick.class, Pick.CODEC);
        numen.api("gui", "The window you have open (numen.use.block on a chest or a machine opens one): read it, put "
                + "items in and take them out, move a stack, choose an option, close it.", GuiApi.class);
    }

    @Fn("Look at the window you have open, or at your own inventory menu when none is.")
    @Example("for _, s in ipairs(numen.gui.view().slots) do print(s.index, s.item, s.count) end")
    @Note("Instant and read-only. Lists every container and crafting-grid slot (index, side, item and count, output "
            + "mark), your own filled ones, the cursor and the menu's numbers (progress, fuel, energy).")
    @Note("A window with options says them in plain terms in `screen`, options numbered from 1: an enchanting table's three "
            + "options (levels asked, the hint it shows), a stonecutter's recipes, a loom's patterns, a villager's "
            + "trades (what they ask for and give, uses and limit, locked), a beacon's pyramid and effects, an anvil's "
            + "cost, a lectern's page and text, a crafter's open slots.")
    @Note("With nothing open it shows YOUR inventory menu, whose 2x2 grid crafts small recipes without a table. In a "
            + "crafting grid a recipe smaller than the grid goes in the top-left; the result slot is side result.")
    @SeeAlso({"numen.gui.put", "numen.gui.take", "numen.gui.move", "numen.gui.quick", "numen.gui.choose", "numen.gui.close"})
    public static GuiOps.Window view(ServerCall call) {
        return GuiOps.window(call.her());
    }

    /** 搬哪一样、搬几件。 */
    public record Items(@Doc("The item, minecraft:coal.") Item item,
                        @Doc("How many to move.") @Omitted("move all of them") @Positional Optional<Integer> count) {}

    @Fn("Put items of one kind from your inventory into the window you have open: all of them, or count.")
    @Example("numen.gui.put(\"minecraft:raw_iron\")")
    @Example("numen.gui.put(\"minecraft:coal\", 8)")
    @Note("The window decides where each stack lands, like a shift-click: raw iron into a furnace's input, coal into "
            + "its fuel slot, anything into a chest's free slots. Part of a stack goes into a free slot of the same "
            + "kind.")
    @Note("Returns how many went in; stops when the window has no room left. None at all fails: not_found when you "
            + "carry none, failed when there is no room.")
    @SeeAlso({"numen.gui.take", "numen.gui.view"})
    public static Pending<Integer> put(ServerCall call, Items args) {
        return items(call, args, true);
    }

    @Fn("Take items of one kind out of the window you have open into your inventory: all of them, or count.")
    @Example("numen.gui.take(\"minecraft:iron_ingot\")")
    @Example("numen.gui.take(\"minecraft:bread\", 3)")
    @Note("Returns how many came out; stops when your inventory is full. Taking out of someone's container may ask "
            + "your owner first; the call waits for the answer.")
    @SeeAlso({"numen.gui.put", "numen.gui.view"})
    public static Pending<Integer> take(ServerCall call, Items args) {
        return items(call, args, false);
    }

    private static Pending<Integer> items(ServerCall call, Items args, boolean put) {
        return call.sync(new GuiItemsTaskRecord(call, call.her().level().getGameTime() + ITEMS_TICKS, args.item(),
                args.count().orElse(null), put));
    }

    /** 从哪一格挪到哪一格、挪几个。 */
    public record Move(@Doc("The slot to take the items from: an index from numen.gui.view().") int from,
                       @Doc("The slot to put them in: an empty slot takes them, the same item merges, a different item "
                               + "swaps places with them.") int to,
                       @Doc("How many to move; needs an empty slot or the same item there.")
                       @Omitted("move the whole stack") Optional<Integer> count) {}

    /** 有界短活:点击一刻就完,从容器里拿东西的那一步可能挂着等主人。 */
    @Fn("Move items from one slot of the window you have open to another: move, merge or swap.")
    @Example("numen.gui.move(38, 1, {count = 1})")
    @Example("numen.gui.move(12, 40)")
    @Note("One move per call; read slot indices with `numen.gui.view()` first.")
    @Note("Taking something out of a container may ask your owner first; the call waits for the answer.")
    @SeeAlso({"numen.gui.view", "numen.gui.quick"})
    public static Pending<Void> move(ServerCall call, Move args) {
        return call.sync(ContainerOps.transfer(call, new ContainerOps.Move(args.from(), args.to(),
                args.count().orElse(null))));
    }

    /** 点哪一格。 */
    public record Quick(@Doc("The slot to shift-click: an index from numen.gui.view().") int from) {}

    @Fn("Shift-click a slot of the window you have open: its whole stack goes to the other side.")
    @Example("numen.gui.quick(5)")
    @Note("The window picks where it lands, like a real shift-click; on a crafting result it takes the result, "
            + "crafting again while the grid still holds enough.")
    @Note("Taking something out of a container may ask your owner first; the call waits for the answer.")
    @SeeAlso({"numen.gui.view", "numen.gui.move"})
    public static Pending<Void> quick(ServerCall call, Quick args) {
        return call.sync(ContainerOps.transfer(call, new ContainerOps.Move(args.from(), null, null)));
    }

    @Fn("Close the window you have open, once you have finished with it. Returns true when it closed one, false when "
            + "none was open.")
    @Example("numen.gui.close()")
    @Note("Instant. Your own inventory menu is always there; with nothing else open there is nothing to close.")
    @SeeAlso("numen.gui.view")
    public static boolean close(ServerCall call) {
        return GuiOps.close(call.her());
    }

    /** 选哪一项。 */
    public record Choose(@Doc("The option's number from numen.gui.view().screen, counted from 1; for a beacon the "
            + "primary effect by name, \"haste\".") Pick option,
                         @Doc("A beacon's secondary effect by name: regeneration (4 levels), or the same as the primary.")
                         @Omitted("no secondary effect") @Positional Optional<Pick> second) {}

    @Fn("Choose an option in the window you have open: an enchanting option, a stonecutter recipe, a loom pattern, a "
            + "trade, a beacon's effects.")
    @Example("numen.gui.choose(2)")
    @Example("numen.gui.choose(\"haste\", \"regeneration\")")
    @Note("Numbers are the ones numen.gui.view().screen lists (from 1). It only chooses: a trade fills its payment from "
            + "your inventory and shows the result in window slot 2, an enchant changes the item in the table, a "
            + "stonecutter or loom fills its result slot. Taking the result is numen.gui.take or numen.gui.quick.")
    @Note("Minecraft decides whether it works (levels and lapis for an enchant, materials and uses for a trade, a payment "
            + "item for a beacon) and does nothing when not; the call then fails saying what it needs and what you have.")
    @Note("Setting someone's beacon or trading with their villager may ask your owner first; the call waits for the answer. "
            + "Returns the window as it is now; what changed is told in the reply.")
    @SeeAlso({"numen.gui.view", "numen.gui.take", "numen.gui.quick"})
    public static Pending<GuiOps.Window> choose(ServerCall call, Choose args) {
        return MenuKinds.choose(call, args.option(), args.second().orElse(null));
    }

    /** 起什么名。 */
    public record Rename(@Doc("The new name, up to 50 characters.") String name) {}

    @Fn("Give the item in the anvil you have open a new name.")
    @Example("numen.gui.rename(\"Excalibur\")")
    @Note("Put the item in the anvil's first slot first. The result slot shows it renamed; taking it (numen.gui.quick on "
            + "that slot) costs the levels numen.gui.view().screen.cost says.")
    @SeeAlso({"numen.gui.view", "numen.gui.quick"})
    public static Pending<GuiOps.Window> rename(ServerCall call, Rename args) {
        return MenuKinds.rename(call, args.name());
    }

    /** 翻到哪一页。 */
    public record Page(@Doc("The page to turn to, counted from 1.") int page) {}

    @Fn("Turn the lectern you have open to a page of its book.")
    @Example("numen.gui.page(3)")
    @Note("The book's pages and the one it is open at are in numen.gui.view().screen. Taking the book off is "
            + "numen.gui.take(\"minecraft:written_book\"). Turning the page of someone's lectern may ask your owner first.")
    @SeeAlso({"numen.gui.view", "numen.gui.take"})
    public static Pending<GuiOps.Window> page(ServerCall call, Page args) {
        return MenuKinds.page(call, args.page());
    }

    /** 开关哪一格。 */
    public record Toggle(@Doc("The crafter's grid slot, 0 to 8 as numen.gui.view lists it.") int slot) {}

    @Fn("Switch a slot of the crafter you have open on or off: a switched-off slot takes nothing.")
    @Example("numen.gui.toggle(4)")
    @Note("Which slots are open is in numen.gui.view().screen.slots. Switching a slot of someone's crafter may ask your "
            + "owner first.")
    @SeeAlso({"numen.gui.view"})
    public static Pending<GuiOps.Window> toggle(ServerCall call, Toggle args) {
        return MenuKinds.toggle(call, args.slot());
    }

    @Fn("Press E: open the inventory of the horse, donkey, mule or llama you ride, or else go back to your own inventory "
            + "window.")
    @Example("local w = numen.gui.open()")
    @Note("Instant. Riding a tame mount that has an inventory opens that (saddle, armor, chest); otherwise any open window "
            + "is closed and your own inventory menu is returned. An untamed mount does not open and the call fails.")
    @SeeAlso({"numen.gui.view", "numen.gui.close"})
    public static GuiOps.Window open(ServerCall call) {
        return GuiOps.open(call.her());
    }
}
