package com.dwinovo.numen.core.tools.interact;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.task.inventory.GuiItemsTaskRecord;
import com.dwinovo.numen.core.tools.ContainerOps;
import com.dwinovo.numen.core.tools.GuiOps;
import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.task.TaskDispatch;

import net.minecraft.resources.ResourceLocation;

/**
 * {@code gui}:她打开的那个界面(一个 Window)。看它、按种类放进去拿出来、一格挪到另一格、整叠挪到另一边、关掉它。打开它是
 * {@code numen.use.block}(右键一口箱子、一台机器),它交回的就是这个 Window;Window 的方法({@code w:put}、{@code w:take}……)写在内置
 * 模块 {@code numen.gui} 里,调的就是这一组的函数。
 *
 * <p>一次一步:{@code move} 放到指定的一格,{@code quick} 像按住 Shift 点它、整叠挪到另一边;{@code put}/{@code take} 按种类搬,
 * 搬够为止。从容器里拿东西的那一步和别的身体动作一样,可能要等主人点头。
 */
public final class GuiCommands {

    static final String GROUP = "gui";

    /** 按种类搬,一刻一步,一个界面至多几十格:期限按格数宽宽地给。 */
    private static final long ITEMS_TICKS = 60 * 20;

    private static final Param<Integer> FROM = Param.required("from", ArgType.integer(0, 999),
                    "The slot to take the items from.")
            .values("a slot index from `numen.gui.view()`");
    private static final Param<Integer> TO = Param.required("to", ArgType.integer(0, 999),
                    "The slot to put them in: an empty slot takes them, the same item merges, a different item swaps "
                            + "places with them.")
            .values("a slot index from `numen.gui.view()`");
    private static final Param<Integer> COUNT = Param.optional("count", ArgType.integer(1, 99),
                    "How many to move; needs an empty slot or the same item there.")
            .whenOmitted("move the whole stack");
    private static final Param<ResourceLocation> ITEM = Param.required("item", ArgType.id(), "The item, minecraft:coal.");
    private static final Param<Integer> HOW_MANY = Param.optionalPositional("count", ArgType.integer(1, 9999),
                    "How many to move.")
            .whenOmitted("move all of them");

    private static final GuiOps GUIS = new GuiOps();

    private GuiCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "The window you have open (numen.use.block on a chest or a machine opens one): "
                + "read it, put items in and take them out, move a stack, close it.", GuiCommands::actions);
    }

    private static void actions(CommandGroup gui) {
        gui.declare(GuiOps.WINDOW);
        gui.server("view", "Look at the window you have open, or at your own inventory menu when none is.",
                        (src, args) -> src.reply(GUIS.inspectGui(src.companion(), args)), Listing.PAGE)
                .returns(GuiOps.WINDOW.type())
                .example("for _, s in ipairs(numen.gui.view().slots) do print(s.index, s.item, s.count) end")
                .note("Instant and read-only. Lists every slot (index, side, item and count, output mark), the "
                        + "cursor and any machine progress; a crafting grid is drawn as a 2D map of slot numbers.")
                .note("With nothing open it shows YOUR inventory menu, whose 2x2 grid crafts small recipes "
                        + "without a table.")
                .note("A modded window with very many slots comes a page at a time; the last line says how to get "
                        + "the next.")
                .seeAlso("gui put", "gui take", "gui move", "gui quick", "gui close");
        gui.server("put", "Put items of one kind from your inventory into the window you have open: all of them, "
                        + "or count.", (src, args) -> items(src, args, true), ITEM, HOW_MANY)
                .returns("moved", ScriptType.INTEGER)
                .example("numen.gui.put(\"minecraft:raw_iron\")")
                .example("numen.gui.put(\"minecraft:coal\", 8)")
                .note("The window decides where each stack lands, like a shift-click: raw iron into a furnace's "
                        + "input, coal into its fuel slot, anything into a chest's free slots. Part of a stack goes "
                        + "into a free slot of the same kind.")
                .note("Returns how many went in; stops when the window has no room left, and says so. None at all "
                        + "fails: not_found when you carry none, failed when there is no room.")
                .seeAlso("gui take", "gui view");
        gui.server("take", "Take items of one kind out of the window you have open into your inventory: all of "
                        + "them, or count.", (src, args) -> items(src, args, false), ITEM, HOW_MANY)
                .returns("moved", ScriptType.INTEGER)
                .example("numen.gui.take(\"minecraft:iron_ingot\")")
                .example("numen.gui.take(\"minecraft:bread\", 3)")
                .note("Returns how many came out; stops when your inventory is full, and says so. Taking out of "
                        + "someone's container may ask your owner first; the call waits for the answer.")
                .seeAlso("gui put", "gui view");
        gui.server("move", "Move items from one slot of the window you have open to another: move, merge or swap.",
                        (src, args) -> step(src, new ContainerOps.Move(args.get(FROM), args.get(TO), args.get(COUNT))),
                        FROM, TO, COUNT)
                .returns(ScriptType.NOTHING)
                .example("numen.gui.move(38, 1, {count = 1})")
                .example("numen.gui.move(12, 40)")
                .note("One move per call; read slot indices with `numen.gui.view()` first.")
                .note("Taking something out of a container may ask your owner first; the call waits for the answer.")
                .seeAlso("gui view", "gui quick");
        gui.server("quick", "Shift-click a slot of the window you have open: its whole stack goes to the other side.",
                        (src, args) -> step(src, new ContainerOps.Move(args.get(FROM), null, null)), FROM)
                .returns(ScriptType.NOTHING)
                .example("numen.gui.quick(5)")
                .note("The window picks where it lands, like a real shift-click; on a crafting result it takes the "
                        + "result, crafting again while the grid still holds enough.")
                .note("Taking something out of a container may ask your owner first; the call waits for the answer.")
                .seeAlso("gui view", "gui move");
        gui.server("close", "Close the window you have open, once you have finished with it.",
                        (src, args) -> src.reply(GUIS.closeGui(src.companion())))
                .returns(ScriptType.NOTHING)
                .example("numen.gui.close()")
                .note("Instant. Your own inventory menu is always there; with nothing else open there is nothing "
                        + "to close.")
                .seeAlso("gui view");
    }

    /** 有界短活:点击一刻就完,从容器里拿东西的那一步可能挂着等主人。 */
    private static void step(ServerSource src, ContainerOps.Move move) {
        TaskDispatch.runSync(src.companion(), ContainerOps.transfer(src, move), src::reply);
    }

    private static void items(ServerSource src, CommandArgs args, boolean put) {
        TaskDispatch.runSync(src.companion(), new GuiItemsTaskRecord(src,
                src.companion().level().getGameTime() + ITEMS_TICKS, ToolArgs.parseItem(args.get(ITEM).toString()),
                args.get(HOW_MANY), put), src::reply);
    }
}
