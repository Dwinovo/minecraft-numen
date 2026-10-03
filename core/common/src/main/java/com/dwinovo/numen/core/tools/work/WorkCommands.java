package com.dwinovo.numen.core.tools.work;

import java.util.List;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.task.fish.FishTaskRecord;
import com.dwinovo.numen.cli.Place;
import com.dwinovo.numen.core.task.dig.DigCompanionTask;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.core.tools.InventoryOps;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.resources.ResourceLocation;

/**
 * {@code work}:采集类的活——挖方块、钓鱼。两个动作都站在原地干,占身体,交任务槽:受理即回执,收尾走 task_finished;开始不了的
 * (手够得着的一格都没有、站的地方抛不进水……)受理之前就当场拒绝,判据在各自的任务里。
 *
 * <p>{@code dig} 只挖她站在原地手够得着的格,挡在前面的一并挖开,不走动、不捡({@link DigCompanionTask});{@code fish} 只钓,
 * 不走去岸边、不追战果。走到够得着的地方是 {@code move.goto_(…, {arrive = "dig"})} 的事;捡是库里的 {@code work.collect}:
 * 原版玩家走近掉落物就捡起来,所以捡就是扫掉落物、走到它跟前。组合交给脚本。
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
            .values("area names as area.list and area.parts list them (\"ores\", \"ores/g3\"), or a cell: a Pos, or a "
                    + "Block or anything else with a pos");
    private static final Param<Integer> DIG_COUNT = Param.optional("count",
            ArgType.integer(1, BlockActionOps.MAX_DIG_COUNT), "How many cells to dig at most.")
            .whenOmitted("dig every cell of it within reach");
    private static final Param<Integer> CATCHES = Param.optional("count", ArgType.integer(1, MAX_CATCHES),
            "How many catches to reel in.")
            .whenOmitted("keep fishing until given something else to do");

    private WorkCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Gathering where you stand: digging blocks within reach, fishing. work.collect "
                + "(library) walks to the drops and picks them up.", WorkCommands::actions);
    }

    private static void actions(CommandGroup work) {
        work.server("dig", "Dig the blocks of areas and cells within reach of where you stand.", WorkCommands::dig,
                        DIG_PLACES, DIG_COUNT)
                .returns(ScriptType.table(
                        ScriptType.field("dug", ScriptType.INTEGER, "Cells it dug."),
                        ScriptType.field("left", ScriptType.INTEGER, "Cells of what you named still to dig."),
                        ScriptType.field("out_of_reach", ScriptType.INTEGER, "Of those, how many your hand does not "
                                + "reach from where you stand."),
                        ScriptType.optional("nearest", Shapes.POS.type(), "The nearest of those out of reach.")))
                .example("work.dig(\"ores/g3\")")
                .example("work.dig(\"ores\", {count = 4})")
                .example("work.dig({x = 120, y = 12, z = -35})")
                .example("local r = work.dig(\"ores\")\nprint(r.dug, r.left, r.out_of_reach)")
                .note("A block or a nearest cell from a query goes in as it is: `work.dig(scan.block({x = 120, y = 12, "
                        + "z = -35}))`, `for _, g in ipairs(scan.blocks(\"iron_ore\").groups) do work.dig(g.nearest) end`.")
                .note("Digs only what your hand reaches from where you stand: it never walks and never picks up. Get "
                        + "within reach first with `move.goto_(\"ores\", {arrive = \"dig\"})` (it picks the spot that "
                        + "reaches the most cells), dig, and pick the drops up with `work.collect()`.")
                .note("Background work: before it starts it checks something within reach can be dug, harvested "
                        + "with your tools and is allowed; when nothing is, it fails with kind out_of_reach (or denied, "
                        + "failed) and a hint with the move.goto_ call to copy — no task starts and whatever you were "
                        + "doing goes on. It returns when the job ends: how many cells it dug and how many are still out "
                        + "of reach, the nearest of them as a pos.")
                .note("What to dig comes from the area itself: cells a scan added are dug only while they still hold "
                        + "the block the scan saw (mining); framed cells and coordinates are dug whatever they hold "
                        + "(a pit, a tree, clearing), air and fluid skipped. `area.has(\"ores\")` says whether anything "
                        + "is left; `area.minus` and rules like deny break(area:house) keep things standing.")
                .note("A block in the way of your hand is dug open too when it is natural terrain. A block in the way "
                        + "that needs your owner's consent or that their rules forbid is not touched: the reply names "
                        + "it, where it is and why.")
                .note("Takes the best tool for each block; only digs what your tools actually harvest, and says so "
                        + "when nothing qualifies. Asks your owner before breaking a named block their rules want "
                        + "asked about; a refusal stops the job with the reason.")
                .seeAlso("move goto_", "work collect", "area has", "scan blocks", "task stop");
        work.server("fish", "Fish from where you stand with a fishing rod.", WorkCommands::fish, CATCHES)
                .returns(ScriptType.table(ScriptType.field("caught", ScriptType.INTEGER, null),
                        ScriptType.field("casts", ScriptType.INTEGER, null),
                        ScriptType.field("requested", ScriptType.INTEGER, "The count you gave; 0 = no count.")))
                .example("work.fish({count = 5})")
                .example("work.fish()")
                .note("Background work: refused with the reason when she carries no fishing rod, does not stand on "
                        + "dry ground, or has no open water to cast into from where she stands — no task id, no "
                        + "task_finished. Otherwise the end arrives as a task_finished event. Without count it is a "
                        + "standing job: it never ends on its own and never sends task_finished.")
                .note("It never walks: stand on the shore first. The reel throws each catch to her; one that lands "
                        + "short lies on the ground for `work.collect()`.")
                .note("A catch is one bite reeled in: fish, junk or treasure, with vanilla loot, rod wear and "
                        + "stats.")
                .seeAlso("work collect", "task stop");
    }

    /** 点名的几处读成格子的那一步在 {@link BlockActionOps#dig},够不够得着在任务受理之前的准备里判。 */
    private static void dig(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new BlockActionOps().dig(src, args.get(DIG_PLACES), args.get(DIG_COUNT)));
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
