package com.dwinovo.numen.core.tools.work;

import java.util.List;
import java.util.stream.Stream;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.task.fish.FishTaskRecord;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import com.dwinovo.numen.core.tools.BlockActionOps;
import com.dwinovo.numen.core.tools.InventoryOps;
import com.dwinovo.numen.core.tools.RouteSpecFlags;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.resources.ResourceLocation;

/**
 * {@code work}:采集类的活——挖方块、捡掉落物、钓鱼。
 *
 * <p>三个动作都占身体,交任务槽:受理即回执,收尾走 task_finished。{@code mine} 提升成同名快捷工具;挖矿的路线规格
 * 标志与 goto 共用({@link RouteSpecFlags}),叠在 mine 自己的默认规格上(可以改地形,要主人同意的格也算进去)。
 */
public final class WorkCommands {

    static final String GROUP = "work";

    private static final int MAX_MINE_COUNT = 256;
    private static final int MAX_CATCHES = 64;
    private static final int MAX_COLLECT_RADIUS = 48;
    private static final long TICKS_PER_CATCH = 90L * 20L;
    private static final long MIN_FISH_TICKS = 120L * 20L;

    private static final Param<List<String>> BLOCK_IDS = Param.optional("block_ids",
            ArgType.list(ArgType.idOrTag()), "Block types to gather; she finds the nearest herself. Include every "
                    + "variant (iron_ore AND deepslate_iron_ore). Give this OR groups.");
    private static final Param<List<String>> GROUPS = Param.optional("groups", ArgType.list(ArgType.word()),
            "Dig exactly the cells of these groups that still hold the block the scan saw, nothing beyond them. "
                    + "Give this OR block_ids.")
            .values("group ids (g1, g2, ...) from your latest scan_blocks");
    private static final Param<Integer> MINE_COUNT = Param.optional("count", ArgType.integer(1, MAX_MINE_COUNT),
            "How many ITEMS to gather (not blocks: a block may drop several), counting only items gained on top "
                    + "of what you already hold. Required with block_ids.")
            .whenOmitted("dig the groups out (groups only)");
    private static final Param<List<ResourceLocation>> ITEM_IDS = Param.optional("item_ids",
            ArgType.list(ArgType.id()), "Item types to pick up.")
            .whenOmitted("pick up everything");
    private static final Param<Integer> RADIUS = Param.optional("radius", ArgType.integer(1, MAX_COLLECT_RADIUS),
            "How far around her to look, in blocks.")
            .whenOmitted("look within 16");
    private static final Param<Integer> CATCHES = Param.optional("count", ArgType.integer(1, MAX_CATCHES),
            "How many catches to reel in.")
            .whenOmitted("keep fishing until given something else to do");

    private WorkCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Gathering: mining blocks, picking up drops, fishing.", WorkCommands::actions);
    }

    private static void actions(CommandGroup work) {
        work.server("mine", "Gather blocks by type and quantity, or dig named groups from a scan.",
                        WorkCommands::mine,
                        Stream.concat(Stream.of(BLOCK_IDS, GROUPS, MINE_COUNT), RouteSpecFlags.PARAMS.stream())
                                .toArray(Param<?>[]::new))
                .example("work mine --block_ids iron_ore deepslate_iron_ore --count 10")
                .example("work mine --groups g3 g4")
                .example("work mine --block_ids #minecraft:logs --count 16 --avoid_break 10,64,-3..14,70,1")
                .note("Background work: returns at once; the end arrives as a task_finished event.")
                .note("Travels on its own with full terrain navigation: digs to buried ores, pillars up cliffs, "
                        + "bridges gaps — no goto needed. The route flags are laid over mine's own default, which "
                        + "may dig anything (blocks needing consent included); pass them only to restrict her.")
                .note("Asks your owner before breaking a block their rules want asked about; a refusal stops the "
                        + "task with the reason.")
                .note("Only mines what her tools actually harvest, and stops naming the tier she needs when "
                        + "nothing qualifies.")
                .seeAlso("scan blocks", "work collect", "task stop")
                .promote("mine", "Gather blocks, in one of two ways. block_ids + count: she finds the nearest "
                        + "blocks of those types herself and mines until `count` NEW items are gained or none "
                        + "remain nearby; include all variants (iron_ore AND deepslate_iron_ore). groups: ids from "
                        + "your latest scan_blocks (g1, g2, ...) — she digs exactly the cells of those groups that "
                        + "still hold the block the scan saw, nothing beyond them; count is optional there and "
                        + "without it she digs the groups out. An id not from the latest scan fails — scan again. "
                        + "Either way she travels with full terrain-traversing navigation (digs to buried ores, "
                        + "pillars up cliffs, bridges gaps); no coordinates or goto needed. count is items, not "
                        + "blocks (redstone_ore drops ~4). Before breaking a block that needs the owner's consent "
                        + "she asks; if the owner or a rule refuses, the task stops with the reason — decide what "
                        + "to do next, do not route around it. Only mines what its tools actually harvest, and "
                        + "stops naming the needed tier if nothing qualifies (to destroy a block regardless of "
                        + "drops, goto beside it and run use block left on it). The route fields are "
                        + "goto's, laid over mine's own default, which may dig anything (cells needing consent "
                        + "included) — pass them only to restrict her, e.g. avoid_break for blocks or cells she "
                        + "must leave standing. BACKGROUND: a successful call is already running; do not call "
                        + "mine/goto again while <current_task> exists and do not poll. task_finished status=done "
                        + "means the job is complete; only timeout permits resending the same arguments.");
        work.server("collect", "Pick up dropped items lying on the ground nearby.", WorkCommands::collect,
                        ITEM_IDS, RADIUS)
                .example("work collect")
                .example("work collect --item_ids minecraft:iron_ingot minecraft:raw_iron --radius 8")
                .note("Background work: returns at once; the end arrives as a task_finished event.")
                .note("Walks to each drop until none she can reach remain; she picks up what she gets close to. "
                        + "It never breaks or places a block: drops in a pit or across a gap it cannot walk to "
                        + "are left there and named in the result.")
                .note("For drops left by your own interactions; fight attack and mine already walk over the "
                        + "drops they make.")
                .seeAlso("work mine", "task stop");
        work.server("fish", "Fish from nearby water with a fishing rod.", WorkCommands::fish, CATCHES)
                .example("work fish --count 5")
                .example("work fish")
                .note("Background work: returns at once; the end arrives as a task_finished event. Without "
                        + "--count it is a standing job that runs until another body action replaces it.")
                .note("Needs a vanilla fishing rod in her inventory. In water she first moves up to 12 blocks "
                        + "onto a dry stance; it does not search far for a biome or a lake.")
                .note("A catch is one bite reeled in: fish, junk or treasure, with vanilla loot, rod wear and "
                        + "stats.")
                .seeAlso("task stop");
    }

    private static void mine(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new BlockActionOps().autoMine(src, args.get(BLOCK_IDS), args.get(GROUPS),
                args.get(MINE_COUNT), RouteSpecFlags.parse(args, MineBlockTaskRecord.DEFAULT_SPEC)));
    }

    private static void collect(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new InventoryOps().collectItems(src, args.get(ITEM_IDS), args.get(RADIUS)));
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
