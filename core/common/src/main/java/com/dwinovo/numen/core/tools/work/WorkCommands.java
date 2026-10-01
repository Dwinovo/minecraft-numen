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
import com.dwinovo.numen.cli.Place;
import com.dwinovo.numen.core.task.dig.DigCompanionTask;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.core.tools.InventoryOps;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.resources.ResourceLocation;

/**
 * {@code work}:采集类的活——挖方块、捡掉落物、钓鱼。
 *
 * <p>三个动作都占身体,交任务槽:受理即回执,收尾走 task_finished;开始不了的(手够得着的一格都没有、地上没有能捡的、没有鱼竿……)
 * 受理之前就当场拒绝,判据在各自的任务里。{@code dig} 提升成快捷工具 {@code work_dig}。
 *
 * <p>{@code dig} 与 {@code collect} 各是一件原子的事:{@code dig} 只挖她站在原地手够得着的格,挡在前面的一并挖开,不走动、不捡
 * ({@link DigCompanionTask});{@code collect} 只捡,走过去捡她工作区里的掉落物({@link WorkArea},受理时她脚下为中心、半径
 * {@link WorkArea#RADIUS} 的球)。走到够得着的地方是 {@code move goto … --arrive dig} 的事;三件事的组合交给脚本。
 */
public final class WorkCommands {

    static final String GROUP = "work";

    private static final int MAX_CATCHES = 64;
    private static final long TICKS_PER_CATCH = 90L * 20L;
    private static final long MIN_FISH_TICKS = 120L * 20L;

    private static final Param<List<Place>> DIG_PLACES = Param.required("place", ArgType.list(ArgType.place()),
            "What to dig: areas of your owner's (or parts of them) and cells, as many as you like — a cell is an area "
                    + "of one cell. Scanned cells are dug only while they still hold the block the scan saw; framed "
                    + "cells and coordinates are dug whatever they hold, air and fluid skipped.")
            .values("area names as `area list` and `area parts` list them (ores, ores/g3), or x y z");
    private static final Param<Integer> DIG_COUNT = Param.optional("count",
            ArgType.integer(1, BlockActionOps.MAX_DIG_COUNT), "How many cells to dig at most.")
            .whenOmitted("dig every cell of it within reach");
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
        work.server("dig", "Dig the blocks of areas and cells within reach of where you stand.", WorkCommands::dig,
                        DIG_PLACES, DIG_COUNT)
                .example("work dig ores/g3")
                .example("work dig ores --count 4")
                .example("work dig 120 12 -35")
                .example("work dig 120 12 -35 121 12 -35")
                .note("Digs only what your hand reaches from where you stand: it never walks and never picks up. Get "
                        + "within reach first with `move goto ores --arrive dig` (it picks the spot that reaches the "
                        + "most cells), dig, and pick the drops up with `work collect`.")
                .note("Background work: before it replies it checks something within reach can be dug, harvested "
                        + "with your tools and is allowed; when nothing is, the call is refused with the reason and the "
                        + "move goto line to copy — no task id, no task_finished, and whatever you were doing goes on. "
                        + "The end of an accepted job arrives as a task_finished event saying how many cells it dug and "
                        + "how many are still out of reach, with the next line to copy.")
                .note("What to dig comes from the area itself: cells a scan added are dug only while they still hold "
                        + "the block the scan saw (mining); framed cells and coordinates are dug whatever they hold "
                        + "(a pit, a tree, clearing), air and fluid skipped. `area has ores` says whether anything is "
                        + "left; `area minus` and rules like `deny break(area:house)` keep things standing.")
                .note("A block in the way of your hand is dug open too when it is natural terrain. A block in the way "
                        + "that needs your owner's consent or that their rules forbid is not touched: the reply names "
                        + "it, where it is and why.")
                .note("Takes the best tool for each block; only digs what your tools actually harvest, and says so "
                        + "when nothing qualifies. Asks your owner before breaking a named block their rules want "
                        + "asked about; a refusal stops the job with the reason.")
                .seeAlso("move goto", "work collect", "area has", "scan blocks", "task stop")
                .promote("Dig the blocks within reach of where you stand. place: what to dig — areas of your owner's "
                        + "(ores, or parts ores/g3 ores/g4) and cells (\"120 12 -35\"), as many as you like. It never "
                        + "walks and never picks up: first move_goto the same place with arrive:'dig' (it stands where "
                        + "the hand reaches the most of it), then work_dig, then `work collect` for the drops; repeat "
                        + "while `area has ores` says something is left. The area says what to dig: cells scan_blocks "
                        + "added with into are dug only while they still hold the block the scan saw (mining ore); "
                        + "framed cells and coordinates are dug whatever they hold, air and fluid skipped. Natural "
                        + "blocks in the way of the hand are dug open; blocks in the way that need the owner's consent "
                        + "or are forbidden are left and named. count: at most this many cells. A call with nothing "
                        + "within reach is refused with the move_goto line to copy. The task_finished says how many "
                        + "cells it dug and how many are still out of reach, with the next line to copy.");
        work.server("collect", "Pick up dropped items lying on the ground nearby.", WorkCommands::collect,
                        ITEM_IDS, COLLECT_AREA)
                .example("work collect")
                .example("work collect --item-ids minecraft:iron_ingot minecraft:raw_iron")
                .example("work collect --area farm")
                .note("Background work: refused with the reason when no drop lies in her work area or she can "
                        + "reach none of them — no task id, no task_finished. The end of an accepted sweep arrives as "
                        + "a task_finished event.")
                .note("Walks to each drop until none she can reach remain; she picks up what she gets close to. "
                        + "It never breaks or places a block: drops in a pit or across a gap it cannot walk to "
                        + "are left there and named in the result.")
                .note("Only drops in her work area count — within " + WorkArea.RADIUS + " blocks of where she stood "
                        + "when you called it — and she moves only inside it, so she never wanders off chasing "
                        + "drops. --area narrows it to the drops lying in that area.")
                .note("Picks up what `work dig` leaves on the ground; `fight attack` already walks over the drops "
                        + "it makes.")
                .seeAlso("work dig", "area show", "task stop");
        work.server("fish", "Fish from nearby water with a fishing rod.", WorkCommands::fish, CATCHES)
                .example("work fish --count 5")
                .example("work fish")
                .note("Background work: refused with the reason when she carries no fishing rod or there is no "
                        + "water to fish from a dry stance nearby — no task id, no task_finished. Otherwise the end "
                        + "arrives as a task_finished event. Without --count it is a standing job: it never ends on "
                        + "its own and never sends task_finished.")
                .note("Needs a vanilla fishing rod in her inventory. In water she first moves up to 12 blocks "
                        + "onto a dry stance; it does not search far for a biome or a lake.")
                .note("A catch is one bite reeled in: fish, junk or treasure, with vanilla loot, rod wear and "
                        + "stats.")
                .seeAlso("task stop");
    }

    /** 点名的几处读成格子的那一步在 {@link BlockActionOps#dig},够不够得着在任务受理之前的准备里判。 */
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
