package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.scan.BlockScan;
import com.dwinovo.numen.core.tools.PerceptionOps;
import com.dwinovo.numen.core.tools.QueryExtraOps;
import com.dwinovo.numen.core.tools.ScanOps;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * {@code scan}:看她周围——脚下一圈的地形图、某几种方块在哪、附近有谁、一格方块是什么、一格方块里装着什么。
 * 五个动作都在服务端读世界,不动世界,不占身体;回执照原样是那份结果(JSON,地形图是一张字符图)。找方块带
 * {@code --into} 时把看到的记进一块区域({@link ScanOps}),那是改区域,先过权限层。
 *
 * <p>前四个是做事之前最常用的眼睛,提升为快捷工具({@code scan_around}、{@code scan_blocks}、
 * {@code scan_entities}、{@code scan_block});{@code storage} 用得少,只留命令。
 */
public final class ScanCommands {

    private static final String GROUP = "scan";

    private static final PerceptionOps PERCEPTION = new PerceptionOps();
    private static final QueryExtraOps QUERY = new QueryExtraOps();

    private static final Param<Integer> VIEW_RADIUS = Param.optional("radius",
            ArgType.integer(LookAround.MIN_RADIUS, LookAround.MAX_RADIUS), "Half-width of the square view in blocks.")
            .whenOmitted("use " + LookAround.DEFAULT_RADIUS);
    /** 找方块不写半径时搜多远:她"附近"的那一圈,一次搜索与一页结果都不大。 */
    private static final int DEFAULT_SEARCH_RADIUS = 16;
    /** 列实体不写半径时看多远。 */
    private static final int DEFAULT_ENTITY_RADIUS = 24;

    private static final Param<Integer> SEARCH_RADIUS = Param.optional("radius",
            ArgType.integer(1, BlockScan.MAX_RADIUS), "Spherical search radius in blocks (max " + BlockScan.MAX_RADIUS
                    + ").")
            .whenOmitted("search " + DEFAULT_SEARCH_RADIUS + " blocks around you");
    private static final Param<List<String>> BLOCK_IDS = Param.required("block_ids", ArgType.list(ArgType.idOrTag()),
            "The block ids or #tags to search for; name every variant.");
    private static final Param<AreaRef> WITHIN = Param.optional("within", ArgType.area(),
            "Only look inside this area, or one part of it (base, ores/g3).")
            .values("an area as `area.list()` lists it")
            .whenOmitted("look everywhere within the radius");
    private static final Param<AreaRef> INTO = Param.optional("into", ArgType.area(),
            "Add each group found to this area as a new part (g1, g2, ... counted within the area); an area that "
                    + "does not exist yet is made.")
            .values("an area name: one of yours, or a new one")
            .whenOmitted("only look: the groups get no ids and nothing is kept");
    private static final Param<Double> ENTITY_RADIUS = Param.optional("radius", ArgType.number(1, 64),
            "Search radius in blocks.")
            .whenOmitted("look " + DEFAULT_ENTITY_RADIUS + " blocks around you");
    private static final Param<String> TYPE_FILTER = Param.optionalPositional("type_filter",
            ArgType.oneOf("hostile", "passive", "player", "item", "all"),
            "Which entities: hostile = monsters, passive = animals and other mobs, player = players, item = items "
                    + "lying on the ground, all = everything.")
            .whenOmitted("list all of them");
    private static final Param<BlockPos> CELL = Param.required("cell", ArgType.cell(), "The block's cell.");

    private ScanCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Look around you: the ground map, where blocks are, who is near, one block "
                + "and what it holds.", ScanCommands::actions);
    }

    private static void actions(CommandGroup scan) {
        scan.server("around", "A top-down map of the ground around you: where you can walk, step, drop, swim.",
                        ScanCommands::around, VIEW_RADIUS)
                .example("scan.around()")
                .example("scan.around({radius = 12})")
                .note("Instant and read-only. @ is you, North is up, East is right, one cell is one block; each cell "
                        + "says how you could move onto it and the legend comes with it.")
                .note("One map instead of many single-block looks; for things further out use `scan.blocks` or "
                        + "`scan.entities`.")
                .seeAlso("scan blocks", "scan entities", "scan block");
        scan.server("blocks", "Find blocks of the given types near you, reported as groups of touching blocks; "
                        + "into keeps them in an area.",
                        ScanCommands::blocks, BLOCK_IDS, SEARCH_RADIUS, WITHIN, INTO, Listing.PAGE)
                .example("scan.blocks(\"iron_ore\", \"deepslate_iron_ore\")")
                .example("scan.blocks({\"iron_ore\", \"deepslate_iron_ore\"}, {radius = 32, into = \"ores\"})")
                .example("scan.blocks(\"#minecraft:beds\", {within = \"base\"})")
                .example("scan.blocks(\"iron_ore\", \"deepslate_iron_ore\", {page = 2})")
                .note("Read-only; the reply comes when the search is done. Name every variant you want.")
                .note("Groups are matching cells that touch (diagonals count) and get the same answer for breaking "
                        + "them, one per line (a JSON object), nearest first: cells and a count per block type, the "
                        + "nearest cell with direction and distance, the permission for breaking (allow; ask = "
                        + "work.dig asks your owner first; deny = work.dig does not dig it) with the reason, source "
                        + "cells for water or lava, and every position for groups of up to 16 cells. A long list comes "
                        + "in short pages (a few dozen groups), and each page looks again. With into the reply lists "
                        + "only the nearest few; `area.show` lists them all.")
                .note("Without into it only looks: the groups have no ids. With into = \"ores\" each group becomes "
                        + "a part of the area ores (made then and there if you have no area ores yet — the result says "
                        + "so), and its id (ores/g5) is what `work.dig`, `move.goto_(\"ores/g5\", {arrive = \"dig\"})` "
                        + "and `area.show` take. Adding to an area your owner's rules name asks your owner first.")
                .note("within = \"base\" looks only inside the area base, as far as the radius reaches from you.")
                .note("Only loaded terrain is read: anything further out is UNKNOWN, not empty.")
                .seeAlso("area show", "work dig", "scan block");
        scan.server("entities", "List the entities near you, nearest first, with the ids other actions take.",
                        ScanCommands::entities, TYPE_FILTER, ENTITY_RADIUS, Listing.PAGE)
                .returns(QueryExtraOps.ENTITIES)
                .example("scan.entities(\"hostile\")")
                .example("scan.entities({radius = 12})")
                .example("scan.entities(\"item\", {radius = 8})")
                .note("Going through them: `for _, e in ipairs(scan.entities(\"item\", {radius = 8})) do print(e.item, "
                        + "e.cell[1], e.cell[2], e.cell[3]) end`.")
                .note("Instant and read-only. Each entity has id, type, category, position, cell (the block cell it "
                        + "is in), distance, hp; an item also has item, count and pickup_delay (ticks before anyone "
                        + "can pick it up); a tamed one has owner: you, your owner, or the other player's name. In a script it returns the list, nearest first, all of "
                        + "them; the reply lists one per line and a long list comes in pages.")
                .note("The ids are runtime ids: `fight.attack(184)`, `use.entity(184)`. They do not survive a "
                        + "restart.")
                .seeAlso("scan around", "fight attack", "use entity");
        scan.server("block", "One block: its id and state, hardness, whether your held tool is right, dig time, "
                        + "whether it is in reach.",
                        ScanCommands::block, CELL)
                .example("scan.block({120, 64, -35})")
                .note("Instant and read-only, from any distance: the block id and its state properties (an "
                        + "end_portal_frame's has_eye), hardness, whether the tool in hand is right, an estimated dig "
                        + "time, and whether it is within your reach.")
                .seeAlso("scan storage", "scan blocks");
        scan.server("storage", "What a block holds — items, fluid, energy — read without opening it.",
                        ScanCommands::storage, CELL, Listing.PAGE)
                .example("scan.storage({120, 64, -35})")
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
        Integer radius = args.get(SEARCH_RADIUS);
        ScanOps.scanBlocks(src, radius == null ? DEFAULT_SEARCH_RADIUS : radius, args.get(BLOCK_IDS),
                args.get(WITHIN), args.get(INTO), args,
                args.call(GROUP + " blocks", List.of(BLOCK_IDS, SEARCH_RADIUS, WITHIN, INTO)));
    }

    private static void entities(ServerSource src, CommandArgs args) {
        Double radius = args.get(ENTITY_RADIUS);
        String filter = args.get(TYPE_FILTER);
        src.reply(QUERY.scanNearbyEntities(radius == null ? DEFAULT_ENTITY_RADIUS : radius,
                filter == null ? "all" : filter, src.companion(), args));
    }

    private static void block(ServerSource src, CommandArgs args) {
        BlockPos cell = args.get(CELL);
        src.reply(PERCEPTION.inspectBlock(cell.getX(), cell.getY(), cell.getZ(), src.companion()));
    }

    private static void storage(ServerSource src, CommandArgs args) {
        BlockPos cell = args.get(CELL);
        src.reply(QUERY.inspectBlockStorage(cell.getX(), cell.getY(), cell.getZ(), src.companion(), args));
    }
}
