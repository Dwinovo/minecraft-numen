package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.core.scan.BlockScan;
import com.dwinovo.numen.core.tools.PerceptionOps;
import com.dwinovo.numen.core.tools.QueryExtraOps;
import com.dwinovo.numen.core.tools.ScanOps;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * {@code scan}:看她周围——脚下一圈的地形图、某几种方块在哪、附近有谁、一格方块是什么、一格方块里装着什么、看不看得见一处。
 * 六个动作都在服务端读世界,不动世界,不占身体,什么也不存;脚本拿到的是读到的数据(方块、实体都带 Pos,原样能交给下一个函数)。
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
    private static final Param<Double> ENTITY_RADIUS = Param.optional("radius", ArgType.number(1, 64),
            "Search radius in blocks.")
            .whenOmitted("look " + DEFAULT_ENTITY_RADIUS + " blocks around you");
    private static final Param<String> TYPE_FILTER = Param.optionalPositional("type_filter",
            ArgType.oneOf("hostile", "passive", "player", "item", "all"),
            "Which entities: hostile = monsters, passive = animals and other mobs, player = players, item = items "
                    + "lying on the ground, all = everything.")
            .whenOmitted("list all of them");
    private static final Param<BlockPos> CELL = Param.required("cell", ArgType.cell(), "The block's cell.");
    private static final Param<com.dwinovo.numen.cli.CellOrEntity> SEEN = Param.required("target",
            ArgType.cellOrEntity(), "What to look at: a cell (a Pos, a Block) or an entity.");

    private ScanCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Look around you: the ground map, where blocks are, who is near, one block "
                + "and what it holds, whether you can see something.", ScanCommands::actions);
    }

    private static void actions(CommandGroup scan) {
        scan.declare(ScanOps.CLUSTER);
        scan.server("map", "A top-down map of the ground around you: where you can walk, step, drop, swim.",
                        ScanCommands::map, VIEW_RADIUS)
                .returns(ScriptType.table(
                        ScriptType.field("rows", ScriptType.listOf(ScriptType.STRING),
                                "The map, one string per row, north first; cells are separated by spaces."),
                        ScriptType.field("center", Shapes.POS.type(), "The cell you stand in: the @."),
                        ScriptType.field("facing", ScriptType.STRING, null),
                        ScriptType.field("legend", ScriptType.STRING, null)))
                .example("for _, row in ipairs(numen.scan.map().rows) do print(row) end")
                .example("numen.scan.map({radius = 12})")
                .note("Instant and read-only. @ is you, North is up, East is right, one cell is one block; each cell "
                        + "says how you could move onto it and the legend comes with it.")
                .note("One map instead of many single-block looks; for things further out use `numen.scan.blocks` or "
                        + "`numen.scan.entities`.")
                .seeAlso("scan blocks", "scan entities", "scan block");
        scan.server("blocks", "Find blocks of the given types near you, as clusters of touching blocks, nearest "
                        + "first.", ScanCommands::blocks, BLOCK_IDS, SEARCH_RADIUS)
                .returns("clusters", ScriptType.listOf(ScanOps.CLUSTER.type()))
                .example("local found = numen.scan.blocks(\"iron_ore\", \"deepslate_iron_ore\")\n"
                        + "print(#found, found[1].count, found[1].nearest.pos.x)")
                .example("numen.scan.blocks({\"iron_ore\", \"deepslate_iron_ore\"}, {radius = 32})")
                .example("numen.scan.blocks(\"#minecraft:beds\")")
                .note("Read-only; the reply comes when the search is done. Name every variant you want.")
                .note("A cluster is matching blocks that touch (diagonals count): every block of it nearest first "
                        + "(a Block: name and pos), the nearest one, and how many. Hand a cluster's blocks on as they are: "
                        + "`numen.work.dig` digs those still standing within reach, `numen.move.goto_` with arrive \"dig\" "
                        + "walks within reach of them, `numen.work.mine` does both until they are gone.")
                .note("Nothing is kept: to use what it found again, keep the result in the program, or scan again.")
                .note("Only loaded terrain is read: anything further out is UNKNOWN, not empty; the reply says so.")
                .seeAlso("work dig", "scan block");
        scan.server("entities", "List the entities near you, nearest first, with the ids other actions take.",
                        ScanCommands::entities, TYPE_FILTER, ENTITY_RADIUS, Listing.PAGE)
                .returns(QueryExtraOps.ENTITIES, ScriptType.listOf(Shapes.ENTITY.type()))
                .example("numen.scan.entities(\"hostile\")")
                .example("numen.scan.entities({radius = 12})")
                .example("numen.scan.entities(\"item\", {radius = 8})")
                .note("Going through them: `for _, e in ipairs(numen.scan.entities(\"item\", {radius = 8})) do "
                        + "numen.move.goto_(e.pos) end`.")
                .note("Instant and read-only. The list of all of them, nearest first: each is an Entity (id, type, "
                        + "category, pos, distance, hp); a dropped item is an Item (also item, count and pickup_delay, "
                        + "ticks before anyone can pick it up); a tamed one has owner: you, your owner, or the other "
                        + "player's name.")
                .note("Hand one on as it is: `local e = numen.scan.entities(\"hostile\")[1]; numen.fight.attack(e)`, and the same "
                        + "with numen.use.entity(e) or numen.move.goto_(e.pos). The ids are runtime ids and do not survive a restart.")
                .seeAlso("scan map", "fight attack", "use entity");
        scan.server("block", "One block: its id and state, hardness, whether your held tool is right, dig time, "
                        + "whether it is in reach.",
                        ScanCommands::block, CELL)
                .returns(ScriptType.table(
                        ScriptType.field("name", ScriptType.STRING, "Its id."),
                        ScriptType.field("pos", Shapes.POS.type(), null),
                        ScriptType.optional("properties", new ScriptType.Simple("table<string, string>"),
                                "Its state, facing = \"north\" ..."),
                        ScriptType.field("is_air", ScriptType.BOOLEAN, null),
                        ScriptType.field("is_solid", ScriptType.BOOLEAN, null),
                        ScriptType.field("is_liquid", ScriptType.BOOLEAN, null),
                        ScriptType.field("hardness", ScriptType.NUMBER, null),
                        ScriptType.field("unbreakable", ScriptType.BOOLEAN, null),
                        ScriptType.field("needs_correct_tool", ScriptType.BOOLEAN, null),
                        ScriptType.field("current_hand_correct_tool", ScriptType.BOOLEAN, null),
                        ScriptType.optional("estimated_mining_ticks", ScriptType.INTEGER, null),
                        ScriptType.field("distance", ScriptType.NUMBER, null),
                        ScriptType.field("in_reach", ScriptType.BOOLEAN, null)))
                .example("numen.scan.block({x = 120, y = 64, z = -35})")
                .note("Instant and read-only, from any distance: the block id and its state properties (an "
                        + "end_portal_frame's has_eye), hardness, whether the tool in hand is right, an estimated dig "
                        + "time, and whether it is within your reach.")
                .seeAlso("scan container", "scan blocks", "scan sight");
        scan.server("container", "What a block holds — items, fluid, energy — read without opening it.",
                        ScanCommands::container, CELL, Listing.PAGE)
                .returns(ScriptType.table(ScriptType.field("block", Shapes.BLOCK.type(), null),
                        ScriptType.field("storage", ScriptType.listOf(ScriptType.STRING),
                                "What it holds, one line per slot, tank or battery.")))
                .example("numen.scan.container({x = 120, y = 64, z = -35})")
                .note("Instant and read-only, from any distance; nothing is opened or moved.")
                .note("Works on chests, furnaces and most modded machines, tanks and batteries. Storage-network "
                        + "terminals (AE2/RS) show only their local buffer, not the whole network.")
                .note("Use it instead of opening a machine's GUI when you only need its contents or fill levels.")
                .seeAlso("scan block");
        scan.server("sight", "Whether you can see a cell or an entity from where you stand, and what is in the way "
                        + "when you cannot.", (src, args) -> src.reply(PERCEPTION.sight(src.companion(), args.get(SEEN))
                        .toJson()), SEEN)
                .returns(ScriptType.table(
                        ScriptType.field("visible", ScriptType.BOOLEAN, "A line from your eyes reaches it."),
                        ScriptType.field("distance", ScriptType.NUMBER, "Blocks from your eyes."),
                        ScriptType.optional("blocked_by", Shapes.BLOCK.type(), "When you cannot see it: the first "
                                + "block in the way, one to dig out before grass and the like.")))
                .example("local s = numen.scan.sight({x = 120, y = 64, z = -35})\nprint(s.visible, s.blocked_by)")
                .example("numen.scan.sight(184)")
                .note("Instant and read-only. A cell is seen when a line from your eyes reaches one of its faces "
                        + "turned to you (an empty cell: its middle) with nothing in between; an entity, when you see "
                        + "its eyes.")
                .seeAlso("scan block", "scan entities");
    }

    private static void map(ServerSource src, CommandArgs args) {
        Integer radius = args.get(VIEW_RADIUS);
        src.reply(LookAround.render(src.companion(), radius == null ? LookAround.DEFAULT_RADIUS : radius).toJson());
    }

    /** 搜索按刻分片,回执在搜完的那一刻经回信口送出。 */
    private static void blocks(ServerSource src, CommandArgs args) {
        Integer radius = args.get(SEARCH_RADIUS);
        ScanOps.scanBlocks(src, radius == null ? DEFAULT_SEARCH_RADIUS : radius, args.get(BLOCK_IDS));
    }

    private static void entities(ServerSource src, CommandArgs args) {
        Double radius = args.get(ENTITY_RADIUS);
        String filter = args.get(TYPE_FILTER);
        src.reply(QUERY.scanNearbyEntities(radius == null ? DEFAULT_ENTITY_RADIUS : radius,
                filter == null ? "all" : filter, src.companion(), args));
    }

    private static void block(ServerSource src, CommandArgs args) {
        src.reply(PERCEPTION.inspectBlock(args.get(CELL), src.companion()).toJson());
    }

    private static void container(ServerSource src, CommandArgs args) {
        src.reply(QUERY.inspectBlockStorage(args.get(CELL), src.companion(), args));
    }
}
