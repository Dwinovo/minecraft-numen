package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.PerceptionOps;
import com.dwinovo.numen.core.tools.QueryExtraOps;
import com.dwinovo.numen.core.tools.ScanOps;

import java.util.List;

/**
 * {@code scan}:看她周围——脚下一圈的地形图、某几种方块在哪、附近有谁、一格方块是什么、一格方块里装着什么。
 * 五个动作都在服务端读世界,只读不动,不占身体;回执照原样是那份结果(JSON,地形图是一张字符图)。
 *
 * <p>前四个是做事之前最常用的眼睛,提升回原来的工具名({@code look_around}、{@code scan_blocks}、
 * {@code scan_nearby_entities}、{@code inspect_block});{@code storage} 用得少,只留命令。
 */
public final class ScanCommands {

    private static final PerceptionOps PERCEPTION = new PerceptionOps();
    private static final ScanOps SCAN = new ScanOps();
    private static final QueryExtraOps QUERY = new QueryExtraOps();

    private static final Param<Integer> VIEW_RADIUS = Param.optional("radius",
            ArgType.integer(LookAround.MIN_RADIUS, LookAround.MAX_RADIUS), "Half-width of the square view in blocks.")
            .whenOmitted("use " + LookAround.DEFAULT_RADIUS);
    private static final Param<Integer> SEARCH_RADIUS = Param.required("radius", ArgType.integer(1, 192),
            "Spherical search radius in blocks (max 192).");
    private static final Param<List<String>> BLOCK_IDS = Param.required("block_ids", ArgType.list(ArgType.idOrTag()),
            "List of namespaced block ids to search for.");
    private static final Param<Double> ENTITY_RADIUS = Param.required("radius", ArgType.number(1, 64),
            "Search radius in blocks.");
    private static final Param<String> TYPE_FILTER = Param.required("type_filter",
            ArgType.oneOf("hostile", "passive", "player", "all"),
            "Which entities: hostile = monsters, passive = animals and items, player = players, all = everything.");
    private static final Param<Integer> X = Param.required("x", ArgType.integer(), "Block X.");
    private static final Param<Integer> Y = Param.required("y", ArgType.integer(), "Block Y.");
    private static final Param<Integer> Z = Param.required("z", ArgType.integer(), "Block Z.");

    private ScanCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands("scan", "Look around you: the ground map, where blocks are, who is near, one block "
                + "and what it holds.", ScanCommands::actions);
    }

    private static void actions(CommandGroup scan) {
        scan.server("around", "A top-down map of the ground around you: where you can walk, step, drop, swim.",
                        ScanCommands::around, VIEW_RADIUS)
                .example("scan around")
                .example("scan around --radius 12")
                .note("Instant and read-only. @ is you, North is up, one cell is one block; the legend comes with it.")
                .note("One map instead of many single-block looks; for things further out use scan blocks or "
                        + "scan entities.")
                .seeAlso("scan blocks", "scan entities", "scan block")
                .promote("look_around", "Your spatial view: a top-down character map of the blocks around you, "
                        + "centred on yourself. `@` is you at the middle, North is up, East is right, each cell is "
                        + "one block. Each cell encodes how you could move onto it, collapsing height into one "
                        + "symbol: `.` flat walkable, `^` step up 1 (jumpable), `,` step down 1-2, `v` drop of 3+ "
                        + "(pit/cliff), `#` wall/blocked, `~` water, `!` lava/hazard, `x` caution (next to a "
                        + "hazard), `T` tree, `?` not loaded. Call this ONCE to grasp terrain, walls, ledges, water "
                        + "and gaps around you instead of many inspect_block calls; to plan a route, trace it cell "
                        + "by cell across the grid. For far-away or specific blocks/entities use scan_blocks / "
                        + "scan_nearby_entities. Optional `radius` (4-16, default 8).");
        scan.server("blocks", "Find blocks of the given types near you, reported as groups of touching blocks.",
                        ScanCommands::blocks, SEARCH_RADIUS, BLOCK_IDS)
                .example("scan blocks 32 iron_ore deepslate_iron_ore")
                .example("scan blocks 16 #minecraft:beds")
                .note("Read-only; the reply comes when the search is done. Name every variant you want.")
                .note("Group ids (g1, g2, ...) stay good only until your next scan blocks.")
                .note("Only loaded terrain is read: anything further out is UNKNOWN, not empty.")
                .seeAlso("scan block", "scan around")
                .promote("scan_blocks", "Find blocks of given type(s) near you, reported as GROUPS: matching cells "
                        + "that touch (diagonals count) and get the same permission answer for breaking them — so a "
                        + "player's log pillar standing against a wild tree comes back as two groups. Nearest groups "
                        + "first, up to 16; groups_total counts them all when the whole radius was read. Each group "
                        + "gives: id, cells and a count per block type, the nearest cell with direction and "
                        + "distance, a box (x1,y1,z1..x2,y2,z2 — the form avoid_break takes), permission for "
                        + "breaking its cells (allow; ask = mine asks the owner first; deny = mine stops) with the "
                        + "reason, sources = source cells for water or lava (a source behaves very differently from "
                        + "flowing), and for groups of up to 16 cells every position. A very large group comes back "
                        + "cut along 16-block section lines, one group per piece. Group ids (g1, g2, ...) are valid "
                        + "only until your next scan_blocks; to dig exactly those cells, pass them to mine as "
                        + "groups. Sees terrain that is loaded right now; anything further out is UNKNOWN, not "
                        + "empty, and note says when that happened — walk that way and scan again. Give every "
                        + "variant of what you want, e.g. both iron_ore and deepslate_iron_ore.");
        scan.server("entities", "List the entities near you, nearest first, with the ids other actions take.",
                        ScanCommands::entities, ENTITY_RADIUS, TYPE_FILTER)
                .example("scan entities 24 hostile")
                .example("scan entities 12 all")
                .note("Instant and read-only. At most 20; truncated:true means more exist.")
                .note("The ids are runtime ids: they do not survive a restart.")
                .seeAlso("scan around")
                .promote("scan_nearby_entities", "List entities within a radius around you, sorted by distance. Use "
                        + "type_filter to narrow: 'hostile' for monsters, 'passive' for animals/items, 'player' for "
                        + "players, 'all' for everything. Returns at most 20 entities; truncated:true means more "
                        + "exist. Each entry has id, type, position, distance, hp, and category. Pass the returned "
                        + "runtime ids to attack; it cannot attack anything outside that set.");
        scan.server("block", "One block: its id and state, hardness, whether your held tool is right, dig time, "
                        + "whether it is in reach.",
                        ScanCommands::block, X, Y, Z)
                .example("scan block 120 64 -35")
                .note("Instant and read-only, from any distance.")
                .seeAlso("scan storage", "scan blocks")
                .promote("inspect_block", "Inspect a single block at the given integer coordinates. Returns block "
                        + "id, its block-state properties when any (e.g. an end_portal_frame's has_eye/facing), "
                        + "hardness, whether you have the correct tool in hand, an estimated dig-tick count, "
                        + "and whether the block is in your 4.5-block mining reach. Call this before mine "
                        + "to confirm the operation will succeed, or to check which end_portal_frame cells "
                        + "still need an ender_eye.");
        scan.server("storage", "What a block holds — items, fluid, energy — read without opening it.",
                        ScanCommands::storage, X, Y, Z)
                .example("scan storage 120 64 -35")
                .note("Instant and read-only, from any distance; nothing is opened or moved.")
                .note("Works on chests, furnaces and most modded machines, tanks and batteries. Storage-network "
                        + "terminals (AE2/RS) show only their local buffer, not the whole network.")
                .note("Use it instead of opening a machine's GUI when you only need its contents or fill levels.")
                .seeAlso("scan block");
    }

    private static void around(ServerSource src, CommandArgs args) {
        Integer radius = args.get(VIEW_RADIUS);
        src.reply(LookAround.render(src.companion(), radius == null ? LookAround.DEFAULT_RADIUS : radius));
    }

    /** 搜索按刻分片,回执在搜完的那一刻经回信口送出。 */
    private static void blocks(ServerSource src, CommandArgs args) {
        SCAN.scanBlocks(args.get(SEARCH_RADIUS), args.get(BLOCK_IDS), src.companion(), src::reply);
    }

    private static void entities(ServerSource src, CommandArgs args) {
        src.reply(QUERY.scanNearbyEntities(args.get(ENTITY_RADIUS), args.get(TYPE_FILTER), src.companion()));
    }

    private static void block(ServerSource src, CommandArgs args) {
        src.reply(PERCEPTION.inspectBlock(args.get(X), args.get(Y), args.get(Z), src.companion()));
    }

    private static void storage(ServerSource src, CommandArgs args) {
        src.reply(QUERY.inspectBlockStorage(args.get(X), args.get(Y), args.get(Z), src.companion()));
    }
}
