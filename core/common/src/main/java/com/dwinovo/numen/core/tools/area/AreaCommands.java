package com.dwinovo.numen.core.tools.area;

import java.util.List;
import java.util.function.UnaryOperator;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.tools.AreaOps;
import com.dwinovo.numen.core.tools.AreaText;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;

/**
 * {@code area}:区域这个名词——主人名下有名字、存盘的一堆格子,每一部分是一次扫描的一团、一个框的盒子、一个点、一栋房子或一条路线
 * 要改的格。它是"一块地方"唯一的写法:权限规则的 {@code area:} 项、{@code scan.blocks} 的 {@code into}/{@code within}、
 * {@code work.dig(区域)} 都点它。设计稿见 {@code docs/look-plan-act.md} §三。
 *
 * <p>无状态:每次调用都点名区域。看({@code show}、{@code list})与给脚本用的查询({@code parts} 一行一个部分名、{@code has}
 * 还有没有要挖的格,成败即答案)当场回,不占身体;改区域的每一行先过权限层({@code edit_area},主人的规则点名的区域要问主人),
 * 也不占身体。运算的结果存成一块新的区域,不存算式;运算的几个位置参数都是区域,第一个是存结果的新区域名。
 */
public final class AreaCommands {

    private static final String GROUP = "area";

    private static final Param<String> NAME = Param.required("name", ArgType.word(), "The area.")
            .values("an area name, as `area.list()` lists it");
    private static final Param<String> NEW_NAME = Param.required("name", ArgType.word(),
            "Name of the new area: lowercase letters, digits, _ and -.");
    private static final Param<AreaRef> SHOWN = Param.required("area", ArgType.area(),
            "The area, or one part of it written area/part (ores/g3).");
    private static final Param<AreaRef> PART = Param.required("part", ArgType.area(),
            "The part, written area/part as area.parts lists it (ores/g3).");
    private static final Param<List<BlockPos>> BOX = Param.optional("box", ArgType.list(ArgType.cell()),
            "A box: its two corners {from, to}, each a Pos, in the dimension you are in.")
            .whenOmitted("add no box");
    private static final Param<BlockPos> AT = Param.optional("at", ArgType.cell(),
            "One cell, in the dimension you are in.")
            .whenOmitted("add the cell you stand in, when nothing else is given");
    private static final Param<String> BUILT = Param.optional("built", ArgType.string(),
            "A building, as build.built names it (house#1): the cells it was built of.")
            .whenOmitted("add no building");
    private static final Param<String> ROUTE = Param.optional("route", ArgType.word(),
            "A route: the cells its latest plan breaks or places, as route.show lists it.")
            .whenOmitted("add no route's cells");
    private static final Param<AreaRef> RESULT = Param.required("name", ArgType.area(),
            "Name of the new area the result is kept as: lowercase letters, digits, _ and -.");
    private static final Param<AreaRef> FROM = Param.required("area", ArgType.area(),
            "The area to start from, or one part of it (ores/g3).");
    private static final Param<List<AreaRef>> AREAS = Param.required("areas", ArgType.list(ArgType.area()),
            "The areas, or parts of them (ores/g3), one or more.");
    private static final Param<List<String>> BLOCKS = Param.optional("blocks", ArgType.list(ArgType.idOrTag()),
            "Block types or #tags to keep.")
            .whenOmitted("keep every cell that carries a block as seen");
    private static final Param<Integer> BY = Param.optional("by", ArgType.integer(1, 32),
            "How many cells to grow by, in every direction (diagonals too).")
            .whenOmitted("grow by 1");

    private static final ScriptType AREA = AreaText.AREA_CLASS.type();

    private AreaCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Areas: named, saved sets of cells — what a scan found, boxes you frame, a "
                + "building, a route's changes — that scans, mining, picking up and your owner's rules can name.",
                AreaCommands::actions);
    }

    /** 这次调用写成脚本里的样子:回执与征询里点名这件事。 */
    private static String line(CommandArgs args, String action, List<Param<?>> params) {
        return args.call(GROUP + " " + action, params);
    }

    private static void actions(CommandGroup area) {
        area.declare(AreaText.AREA_CLASS);
        area.declare(AreaText.PART_CLASS);
        area.server("new", "Make an empty area in the dimension you are in.",
                        (src, args) -> AreaOps.create(src, args.get(NEW_NAME), line(args, "new", List.of(NEW_NAME))),
                        NEW_NAME)
                .returns(AREA)
                .example("area.new(\"ores\")")
                .note("Instant. Areas belong to your owner: every companion of theirs sees and changes the same ones, "
                        + "and they survive restarts.")
                .note("Changing an area your owner's rules name (`area:house` in a rule) asks your owner first; the "
                        + "call waits for the answer.")
                .seeAlso("area add", "scan blocks");
        area.server("add", "Add a part to an area: a box, one cell, a building or a route's planned changes.",
                        AreaCommands::add, NAME, BOX, AT, BUILT, ROUTE)
                .returns(AREA)
                .example("area.add(\"house\", {box = {{x = 10, y = 60, z = 5}, {x = 20, y = 70, z = 15}}})")
                .example("area.add(\"chest\", {at = {x = 12, y = 64, z = 7}})")
                .example("area.add(\"here\")")
                .example("area.add(\"home\", {built = \"house#1\"})")
                .example("area.add(\"tunnel\", {route = \"mine\"})")
                .note("Instant. Give one of box, at, built, route; none of them adds the cell you stand in. "
                        + "The part gets the next number of its letter: b for a box, p for a cell, c for a building's "
                        + "or a route's cells; numbers are never reused.")
                .note("Framed cells carry no block. To add blocks as they stand, scan them in: "
                        + "`scan.blocks(\"iron_ore\", {into = \"ores\"})`.")
                .seeAlso("area show", "area drop");
        area.server("drop", "Remove one part of an area; the other parts keep their numbers.",
                        AreaCommands::drop, PART)
                .returns(AREA)
                .example("area.drop(\"ores/g2\")")
                .note("Instant.")
                .seeAlso("area show");
        area.server("show", "Show an area part by part: cells, blocks as seen, the nearest cell, the box, and whether "
                        + "breaking what stands there is allowed now.",
                        (src, args) -> src.reply(AreaOps.show(src.companion(), args.get(SHOWN), args)), SHOWN,
                        Listing.PAGE)
                .returns(ScriptType.table(ScriptType.field("area", AREA, "The whole area."),
                        ScriptType.field("parts", ScriptType.listOf(AreaText.PART_CLASS.type()),
                                "The parts shown: all of them, or the one named.")))
                .example("area.show(\"ores\")")
                .example("area.show(\"ores/g3\")")
                .note("Instant and read-only. Permission is asked for every cell now, the way breaking it would be: "
                        + "allow, ask (your owner is asked first) or deny, with the reason.")
                .seeAlso("area list", "area parts", "area refresh", "work dig");
        area.server("parts", "The parts of an area, one name per line (ores/g1), for going through them one by one.",
                        (src, args) -> src.reply(AreaOps.parts(src.companion(), args.get(SHOWN), args)), SHOWN,
                        Listing.PAGE)
                .example("area.parts(\"ores\")")
                .note("Going through them: `for _, part in ipairs(area.parts(\"ores\")) do print(part) end`.")
                .note("Instant and read-only: the list of the names. `move.goto_(\"ores/g1\", {arrive = \"dig\"})` and "
                        + "`work.dig(\"ores/g1\")` take each as it is.")
                .returns("parts", ScriptType.listOf(ScriptType.STRING))
                .seeAlso("area show", "area has");
        area.server("has", "Whether an area, or one part of it, still has a cell to dig: true or false.",
                        (src, args) -> src.reply(AreaOps.has(src.companion(), args.get(SHOWN))), SHOWN)
                .example("while area.has(\"ores\") do work.dig(\"ores\") end")
                .example("area.has(\"ores/g3\")")
                .note("Instant and read-only. Each cell is judged the way work.dig judges it, in the world now: a "
                        + "scanned cell counts while it still holds the block the scan saw, a framed cell while a block "
                        + "stands in it. Cells in unloaded terrain are not read.")
                .returns("has", ScriptType.BOOLEAN)
                .seeAlso("work dig", "area parts");
        area.server("list", "The areas of your owner, one line each.",
                        (src, args) -> src.reply(AreaOps.list(src.companion(), args)), Listing.PAGE)
                .returns("areas", ScriptType.listOf(AREA))
                .example("area.list()")
                .note("Instant and read-only.")
                .seeAlso("area show");
        area.server("delete", "Delete an area.",
                        (src, args) -> AreaOps.delete(src, args.get(NAME), line(args, "delete", List.of(NAME))), NAME)
                .returns(ScriptType.NOTHING)
                .example("area.delete(\"ores\")")
                .note("Instant.")
                .seeAlso("area list");
        area.server("refresh", "Check the scanned cells of an area against the world now and strike off those that no "
                        + "longer hold what was seen.",
                        (src, args) -> AreaOps.refresh(src, args.get(NAME), line(args, "refresh", List.of(NAME))), NAME)
                .returns(AREA)
                .example("area.refresh(\"ores\")")
                .note("Instant. Only cells a scan added carry a block to check; cells in unloaded terrain are kept and "
                        + "counted. Parts left with no cell are removed.")
                .seeAlso("area show");
        area.server("union", "Keep the cells of several areas together as a new area.",
                        (src, args) -> derive(src, args, "union", List.of(RESULT, AREAS), args.get(AREAS).get(0),
                                a -> unionRest(src.companion(), a, args.get(AREAS))), RESULT, AREAS)
                .returns(AREA)
                .example("area.union(\"all\", \"ores\", \"gold\")")
                .note("Instant. The first area's parts keep their ids; the others' follow with new numbers.")
                .seeAlso("area minus", "area intersect");
        area.server("minus", "Keep, as a new area, the cells of an area that are not in the others.",
                        (src, args) -> derive(src, args, "minus", List.of(RESULT, FROM, AREAS), args.get(FROM),
                                a -> fold(src.companion(), a, args.get(AREAS), Area::minus)), RESULT, FROM, AREAS)
                .returns(AREA)
                .example("area.minus(\"safe\", \"house\", \"house/b2\")")
                .note("Instant. Each part of the first area loses the cells in the others and keeps its id; a part left "
                        + "empty goes.")
                .seeAlso("area union");
        area.server("intersect", "Keep, as a new area, the cells of an area that are also in all the others.",
                        (src, args) -> derive(src, args, "intersect", List.of(RESULT, FROM, AREAS), args.get(FROM),
                                a -> fold(src.companion(), a, args.get(AREAS), Area::intersect)), RESULT, FROM, AREAS)
                .returns(AREA)
                .example("area.intersect(\"near\", \"ores\", \"base\")")
                .note("Instant. Each part of the first area keeps only the cells the others share and keeps its id.")
                .seeAlso("area union");
        area.server("filter", "Keep, as a new area, the cells whose block as seen is one of the given types.",
                        (src, args) -> derive(src, args, "filter", List.of(RESULT, FROM, BLOCKS), args.get(FROM),
                                AreaOps.filter(args.get(BLOCKS))), RESULT, FROM, BLOCKS)
                .returns(AREA)
                .example("area.filter(\"logs\", \"house\", {blocks = \"#minecraft:logs\"})")
                .example("area.filter(\"seen\", \"ores\")")
                .note("Instant. It reads the blocks the area carries as seen, not the world: framed cells carry none "
                        + "and are dropped.")
                .seeAlso("area refresh");
        area.server("grow", "Keep, as a new area, an area grown by some cells in every direction.",
                        (src, args) -> derive(src, args, "grow", List.of(RESULT, FROM, BY), args.get(FROM),
                                a -> a.grow(args.get(BY) == null ? 1 : args.get(BY))), RESULT, FROM, BY)
                .returns(AREA)
                .example("area.grow(\"buffer\", \"house\", {by = 2})")
                .example("area.grow(\"wall\", \"house\")")
                .note("Instant. Each part grows by itself; the cells it gains carry no block.")
                .seeAlso("area minus");
        area.server("center", "Keep, as a new area, the one cell of an area nearest its middle.",
                        (src, args) -> derive(src, args, "center", List.of(RESULT, FROM), args.get(FROM),
                                Area::center), RESULT, FROM)
                .returns(AREA)
                .example("area.center(\"mid\", \"ores\")")
                .note("Instant. The cell is always one of the area's own, even for a ring or an L.")
                .seeAlso("area show");
    }

    /** 加一部分:四种来源至多一种;一种都不写是她脚下那一格。 */
    private static void add(ServerSource src, CommandArgs args) {
        NumenPlayer her = src.companion();
        int given = (args.get(BOX) == null ? 0 : 1) + (args.get(AT) == null ? 0 : 1)
                + (args.get(BUILT) == null ? 0 : 1) + (args.get(ROUTE) == null ? 0 : 1);
        if (given > 1) {
            throw new IllegalArgumentException("area.add takes one of box, at, built or route; you gave " + given);
        }
        AreaOps.Source source = args.get(BOX) != null ? AreaOps.box(her, args.get(BOX))
                : args.get(BUILT) != null ? AreaOps.built(her, args.get(BUILT))
                : args.get(ROUTE) != null ? AreaOps.route(her, args.get(ROUTE))
                : AreaOps.point(her, args.get(AT) != null ? args.get(AT) : Feet.cell(her));
        AreaOps.add(src, args.get(NAME), source, line(args, "add", List.of(NAME, BOX, AT, BUILT, ROUTE)));
    }

    /** 去掉一部分:点名的得是 {@code 区域/部分}。 */
    private static void drop(ServerSource src, CommandArgs args) {
        AreaRef part = args.get(PART);
        if (part.part() == null) {
            throw new IllegalArgumentException("area.drop removes one part, written area/part as `area.parts(\""
                    + part.name() + "\")` lists them; `area.delete(\"" + part.name() + "\")` removes the whole area");
        }
        AreaOps.drop(src, part.name(), part.part(), line(args, "drop", List.of(PART)));
    }

    /** 运算:点名的第一块起算,结果存成新的一块;存结果的名字是整块区域的名字,不带部分。 */
    private static void derive(ServerSource src, CommandArgs args, String action, List<Param<?>> params,
                               AreaRef first, UnaryOperator<Area> operation) {
        AreaRef result = args.get(RESULT);
        if (result.part() != null) {
            throw new IllegalArgumentException("the result of area." + action + " is kept as a new area, named "
                    + "without a part; got " + result);
        }
        AreaOps.derive(src, result.name(), line(args, action, params), operation,
                AreaOps.resolve(src.companion(), first));
    }

    /** 并:第一块之后的几块依次接上。 */
    private static Area unionRest(NumenPlayer her, Area first, List<AreaRef> all) {
        return fold(her, first, all.subList(1, all.size()), Area::union);
    }

    private static Area fold(NumenPlayer her, Area start, List<AreaRef> others,
                             java.util.function.BinaryOperator<Area> op) {
        Area out = start;
        for (AreaRef other : others) {
            out = op.apply(out, AreaOps.resolve(her, other));
        }
        return out;
    }
}
