package com.dwinovo.numen.core.tools.work;

import java.util.List;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.WorkArea;
import com.dwinovo.numen.core.task.fish.FishTaskRecord;
import com.dwinovo.numen.core.task.move.Destination;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.core.tools.InventoryOps;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.resources.ResourceLocation;

/**
 * {@code work}:采集类的活——挖方块、捡掉落物、钓鱼。
 *
 * <p>三个动作都占身体,交任务槽:受理即回执,收尾走 task_finished。{@code dig} 提升成快捷工具 {@code work_dig}。
 *
 * <p>{@code dig} 与 {@code collect} 都只在跟前干,工作区是同一个({@link WorkArea}):受理时她脚下那一格为中心、半径
 * {@link WorkArea#RADIUS} 的球,走动关在里面。dig 挖点名的几处(区域、坐标)在区里的格,区外的只报告并给出开路的写法;collect 只捡
 * 区里的,{@code --area} 再把它收窄到点名的区域里。
 */
public final class WorkCommands {

    static final String GROUP = "work";

    private static final int MAX_CATCHES = 64;
    private static final long TICKS_PER_CATCH = 90L * 20L;
    private static final long MIN_FISH_TICKS = 120L * 20L;

    /** 要挖的那一串:一个名字是一块区域,三个数是一格;怎么读成几处只在 {@link Destination.Stop#each}。 */
    private static final ArgType<List<String>> PLACES = ArgType.list(ArgType.string()
            .as("place", "x y z (one cell), or an area of your owner's: its name, or name/part like ores/g3",
                    text -> text, text -> text));
    private static final Param<List<String>> DIG_PLACES = Param.required("place", PLACES,
            "What to dig: areas of your owner's (or parts of them) and cells, as many as you like — a coordinate is "
                    + "an area of one cell. Scanned cells are dug only while they still hold the block the scan saw; "
                    + "framed cells and coordinates are dug whatever they hold, air and fluid skipped.")
            .values("area names as `area list` and `area show` list them (ores, ores/g3), or x y z");
    private static final Param<Integer> DIG_COUNT = Param.optional("count",
            ArgType.integer(1, BlockActionOps.MAX_DIG_COUNT),
            "How many ITEMS to gather (not blocks: a block may drop several), counting only items gained on top "
                    + "of what you already hold.")
            .whenOmitted("dig out every cell of it in her work area");
    private static final Param<List<ResourceLocation>> ITEM_IDS = Param.optional("item_ids",
            ArgType.list(ArgType.id()), "Item types to pick up.")
            .whenOmitted("pick up everything");
    private static final Param<List<AreaRef>> COLLECT_AREA = Param.optional("area", ArgType.list(ArgType.area()),
            "Only pick up drops lying in this area, or these parts of it.")
            .values("an area or its parts as `area list` and `area show` list them")
            .whenOmitted("pick up anywhere in her work area");
    private static final Param<Integer> CATCHES = Param.optional("count", ArgType.integer(1, MAX_CATCHES),
            "How many catches to reel in.")
            .whenOmitted("keep fishing until given something else to do");

    private WorkCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Gathering: digging blocks, picking up drops, fishing.", WorkCommands::actions);
    }

    private static void actions(CommandGroup work) {
        work.server("dig", "Dig out the blocks of areas and cells right in front of you.", WorkCommands::dig,
                        DIG_PLACES, DIG_COUNT)
                .example("work dig ores/g3")
                .example("work dig ores --count 10")
                .example("work dig 120 12 -35")
                .example("work dig pit")
                .note("Background work: returns at once; the end arrives as a task_finished event.")
                .note("What to dig comes from the area itself: cells a scan added are dug only while they still hold "
                        + "the block the scan saw (mining); framed cells and coordinates are dug whatever they hold "
                        + "(a pit, a tree, clearing), air and fluid skipped. What cannot be broken is reported. Look, "
                        + "then dig: `area new ores`, `scan blocks 16 iron_ore deepslate_iron_ore --into ores`, "
                        + "`work dig ores`; `area minus` and rules like `deny break(area:house)` keep things standing.")
                .note("Works only right in front of her: her work area is within " + WorkArea.RADIUS + " blocks of "
                        + "where she stands when you call it, and she moves only inside it — a few steps, into the "
                        + "hole she just dug, over to a drop. Cells beyond it are reported, not visited: the reply "
                        + "says how many, where the nearest is, and the lines that open the way (route new … --arrive "
                        + "dig --alter natural, route plan, move go; or move goto … --arrive dig), then dig again. "
                        + "Something wholly beyond it is refused at once.")
                .note("She takes the best tool for each block, breaks what is in the way of her hand, and picks up "
                        + "what falls. Only digs what her tools actually harvest, and says which tier she needs when "
                        + "nothing qualifies.")
                .note("Asks your owner the moment she is about to break a block their rules want asked about; a "
                        + "refusal stops the job with the reason.")
                .seeAlso("scan blocks", "area show", "move goto", "route new", "work collect", "task stop")
                .promote("Dig out blocks right in front of you. place: what to dig — areas of your owner's (ores, or "
                        + "parts ores/g3 ores/g4) and cells (three numbers are one cell: 120 12 -35), as many as you "
                        + "like. The area says what to dig: cells scan_blocks added with into are dug only while they "
                        + "still hold the block the scan saw (mining ore); framed cells (`area add --box`) and "
                        + "coordinates are dug whatever they hold (a pit, a tree, clearing), air and fluid skipped. "
                        + "Look first for ore: `area new ores`, scan_blocks with into:'ores' for every variant "
                        + "(iron_ore AND deepslate_iron_ore), then work_dig with place:['ores']. count: how many NEW "
                        + "items to gather (items, not blocks: redstone_ore drops ~4); without it she digs every cell "
                        + "in her work area. WORK AREA: she works only within " + WorkArea.RADIUS + " blocks of where "
                        + "she stands when you call it and moves only inside it; cells beyond it are reported with "
                        + "the exact lines that open the way there (route new … --arrive dig --alter natural, route "
                        + "plan, move go — or move_goto … arrive:'dig' alter:'natural'), then call work_dig again. "
                        + "Something wholly beyond it is refused at once. Keep things standing with area minus or "
                        + "your owner's rules, not with flags. She takes the best tool, breaks what is in her way and "
                        + "picks up the drops. Before breaking a block that needs the owner's consent she asks; a "
                        + "refusal stops the job with the reason — decide what to do next, do not route around it. "
                        + "Only digs what its tools actually harvest, and names the needed tier if nothing "
                        + "qualifies. task_finished status=done means the job is complete; only timeout permits "
                        + "resending the same arguments.");
        work.server("collect", "Pick up dropped items lying on the ground nearby.", WorkCommands::collect,
                        ITEM_IDS, COLLECT_AREA)
                .example("work collect")
                .example("work collect --item_ids minecraft:iron_ingot minecraft:raw_iron")
                .example("work collect --area farm")
                .note("Background work: returns at once; the end arrives as a task_finished event.")
                .note("Walks to each drop until none she can reach remain; she picks up what she gets close to. "
                        + "It never breaks or places a block: drops in a pit or across a gap it cannot walk to "
                        + "are left there and named in the result.")
                .note("Only drops in her work area count — within " + WorkArea.RADIUS + " blocks of where she stood "
                        + "when you called it — and she moves only inside it, so she never wanders off chasing "
                        + "drops. --area narrows it to the drops lying in that area.")
                .note("For drops left by your own interactions; `fight attack` and work_dig already walk over the "
                        + "drops they make.")
                .seeAlso("work dig", "area show", "task stop");
        work.server("fish", "Fish from nearby water with a fishing rod.", WorkCommands::fish, CATCHES)
                .example("work fish --count 5")
                .example("work fish")
                .note("Background work: returns at once; the end arrives as a task_finished event. Without "
                        + "--count it is a standing job: it never ends on its own and never sends task_finished.")
                .note("Needs a vanilla fishing rod in her inventory. In water she first moves up to 12 blocks "
                        + "onto a dry stance; it does not search far for a biome or a lake.")
                .note("A catch is one bite reeled in: fish, junk or treasure, with vanilla loot, rod wear and "
                        + "stats.")
                .seeAlso("task stop");
    }

    private static void dig(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new BlockActionOps().dig(src, args.get(DIG_PLACES), args.get(DIG_COUNT)));
    }

    private static void collect(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new InventoryOps().collectItems(src, args.get(ITEM_IDS), args.get(COLLECT_AREA)));
    }

    /** 没给数量就是常驻:一直钓,不设期限——期限是给"该多久干完"用的,而它没有干完。 */
    private static void fish(ServerSource src, CommandArgs args) {
        Integer asked = args.get(CATCHES);
        if (asked == null) {
            TaskDispatch.setTask(src, new FishTaskRecord(src, TaskRecord.NO_DEADLINE, 0));
            return;
        }
        int count = Math.clamp(asked, 1, MAX_CATCHES);
        long budget = Math.max(MIN_FISH_TICKS, count * TICKS_PER_CATCH);
        TaskDispatch.setTask(src, new FishTaskRecord(src, src.companion().level().getGameTime() + budget, count));
    }
}
