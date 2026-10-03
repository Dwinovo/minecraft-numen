package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.agent.script.ScriptType;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.Building;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.tools.BuildOps;

import net.minecraft.core.BlockPos;

/**
 * {@code build}:把一处手够得着的格变成要盖的样子。要盖的样子是一串格(Cells,{@code numen.shape} 画的,或任何一串 Block),或一份
 * 蓝图文件摆在哪儿({@code numen.build.blueprint} 交回的那张表,格子留在文件里)。{@code place} 只放站在原地够得着的格、每格轮到一次就
 * 收场,{@code diff} 数还差什么;走到够得着的地方、挖开挡着的、一轮轮放到底,是库里的 {@code numen.build.raise}。
 */
public final class BuildCommands {

    static final String GROUP = "build";

    private static final Param<String> FILE = Param.required("name", ArgType.string(),
                    "The blueprint file: a .litematic, .schem, .nbt or .snbt in the server's schematics folder, without "
                            + "its extension.");
    private static final Param<BlockPos> ORIGIN = Param.required("origin", ArgType.cell(),
            "The world cell the file's lowest north-west corner goes to: what the file has at its 0 0 0 is built there. "
                    + "Given the ground's own y, its lowest level replaces the top ground block; given one more it "
                    + "sits on top of the ground.");
    private static final Param<Integer> ROTATION = Param.optional("rotation", ArgType.integer(),
                    "Turn it clockwise seen from above, about its origin, which stays where it is: at 90 the file's +x "
                            + "runs south and what faced north faces east.")
            .values("0, 90, 180 or 270")
            .whenOmitted("keep it as drawn");
    private static final Param<Building> WHAT = Param.required("building", ArgType.building(),
            "What it should look like: Cells (each a Block, the block for that cell written as /setblock takes it), "
                    + "or a Blueprint from numen.build.blueprint.");

    /** 一份蓝图文件摆在哪儿:{@code numen.build.blueprint} 交回的那张表,{@code place} 与 {@code diff} 收它。 */
    static final ScriptType.Class BLUEPRINT_CLASS = new ScriptType.Class("Blueprint",
            "A blueprint file placed at a spot, as numen.build.blueprint returns it; numen.build.place and "
                    + "numen.build.diff take it. It says only which file and where: the cells stay in the file.",
            null, java.util.List.of(
            ScriptType.field("blueprint", ScriptType.STRING, "The file."),
            ScriptType.field("origin", Shapes.POS.type(), "Where its lowest north-west corner goes."),
            ScriptType.field("rotation", ScriptType.INTEGER, "Degrees clockwise: 0, 90, 180 or 270."),
            ScriptType.optional("size", Shapes.POS.type(), "How big the file is: x by y by z."),
            ScriptType.optional("cells", ScriptType.INTEGER, "How many cells it has."),
            ScriptType.optional("materials", new ScriptType.Simple("table<string, integer>"), "Items for all of it."),
            ScriptType.optional("short_of", new ScriptType.Simple("table<string, integer>"),
                    "Of those, what you are still short of, in survival.")));

    /** 一次放方块收尾时的结果。 */
    static final ScriptType.Class PLACED_CLASS = new ScriptType.Class("Placed",
            "What one numen.build.place did.", null, java.util.List.of(
            ScriptType.field("placed", ScriptType.INTEGER, "Blocks placed."),
            ScriptType.field("left", ScriptType.INTEGER, "Cells still to do."),
            ScriptType.optional("completed", ScriptType.INTEGER, "Cells that now match."),
            ScriptType.optional("replaced", ScriptType.INTEGER, null),
            ScriptType.optional("cleared", ScriptType.INTEGER, null),
            ScriptType.optional("removed", ScriptType.INTEGER, null),
            ScriptType.optional("building", ScriptType.STRING, "The building's name, house#1, for a blueprint."),
            ScriptType.optional("site", ScriptType.listOf(Shapes.POS.type()), "Two corners of the site."),
            ScriptType.optional("still_short", ScriptType.STRING, "What you are short of."),
            ScriptType.optional("settled_away", ScriptType.INTEGER, "When the last cell went in: cells that changed "
                    + "once the world settled (vanilla would not hold them as drawn, or their neighbours reshape them).")));

    private BuildCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Building: make the cells your hand reaches from where you stand look like "
                + "Cells (numen.shape draws them) or a blueprint file. numen.build.raise (library) walks the site and "
                + "builds all of it.", BuildCommands::actions);
    }

    private static void actions(CommandGroup build) {
        build.declare(BLUEPRINT_CLASS);
        build.declare(PLACED_CLASS);
        build.server("blueprint", "A blueprint file placed at a spot: its size, cells and materials, for "
                        + "numen.build.place and numen.build.diff.",
                        (src, args) -> src.reply(BuildOps.blueprint(src.companion(), args.get(FILE), args.get(ORIGIN),
                                Placement.quarters(args.get(ROTATION)))), FILE, ORIGIN, ROTATION)
                .returns(BLUEPRINT_CLASS.type())
                .example("local house = numen.build.blueprint(\"japanese_cottage\", {x = 100, y = 64, z = -20})")
                .example("numen.build.blueprint(\"tower\", {x = 100, y = 64, z = -20}, {rotation = 90})")
                .note("Instant and read-only: it reads the file once and returns where and how it goes with what it "
                        + "costs; nothing is built. A name that is not a file fails with not_found and names the files "
                        + "there are.")
                .seeAlso("build place", "build diff", "build raise");
        build.server("place", "Make the cells of a building that your hand reaches from where you stand look like "
                        + "it: each such cell gets its turn once.",
                        (src, args) -> BuildOps.place(src, args.get(WHAT)), WHAT)
                .returns(PLACED_CLASS.type())
                .example("numen.build.place({{name = \"cobblestone\", pos = {x = 100, y = 64, z = -20}}, "
                        + "{name = \"oak_stairs[facing=east]\", pos = {x = 101, y = 64, z = -20}}})")
                .example("numen.build.place({{name = \"crafting_table\", pos = {x = 101, y = 64, z = -20}}})")
                .example("numen.build.place(numen.build.blueprint(\"tower\", {x = 100, y = 64, z = -20}))")
                .note("It never walks and never digs: it places only the cells within reach of where you stand, each "
                        + "once, then returns how many it placed and how many are left. `numen.build.diff` says what "
                        + "is still to do and where; `numen.build.raise` (library) walks the site, digs what is in "
                        + "the way and calls this until the whole building stands.")
                .note("Background work: fails at once with kind out_of_reach when nothing of it is within reach to "
                        + "place, with how many cells are left and a hint with the numen.move.to call to the lowest "
                        + "nearest one; nothing starts.")
                .note("A blueprint at the same dimension, spot and rotation is the same building: placing it again "
                        + "adds what is missing, replaces what differs, and removes only blocks you placed there before "
                        + "that the file no longer has and that nobody has changed since. Cells are only those cells.")
                .note("Survival: Cells are priced as a whole before the first block and refused, placing nothing, "
                        + "when anything is short; a blueprint builds as far as your stock goes. Where another block "
                        + "stands it places nothing: dig it out first with numen.work.dig (numen.build.diff lists those "
                        + "cells). Creative replaces it at once.")
                .note("Asks your owner first when their rules say so, for the cells it would change; a refusal stops "
                        + "it with their words.")
                .seeAlso("build diff", "build raise", "task stop");
        build.server("diff", "What a spot still lacks to look like a building, seen from where you stand: how many "
                        + "cells are within reach to place, which must be dug out first, how many are out of reach and "
                        + "the lowest nearest of those.",
                        (src, args) -> src.reply(BuildOps.diff(src.companion(), args.get(WHAT))), WHAT)
                .returns(ScriptType.table(
                        ScriptType.field("left", ScriptType.INTEGER, "Cells still to do."),
                        ScriptType.field("reach", ScriptType.INTEGER, "Within reach to place now."),
                        ScriptType.field("dig", ScriptType.listOf(Shapes.POS.type()), "Up to 16 cells holding another "
                                + "block to dig out first, nearest first."),
                        ScriptType.field("far", ScriptType.INTEGER, "Out of reach."),
                        ScriptType.optional("next", Shapes.POS.type(), "The lowest, then nearest, of those out of "
                                + "reach: numen.move.to(d.next, {arrive = \"place\"}) gets within reach of it."),
                        ScriptType.optional("short", ScriptType.INTEGER, "Cells holding another block with nothing "
                                + "of yours to put there."),
                        ScriptType.optional("unheld", ScriptType.INTEGER, "Cells that would not stay put yet: what "
                                + "holds them (the block below, the wall behind) is not built."),
                        ScriptType.optional("skipped", ScriptType.INTEGER, "Cells you leave alone.")))
                .example("local d = numen.build.diff(numen.build.blueprint(\"tower\", {x = 100, y = 64, z = -20}))\n"
                        + "print(d.left, d.reach, d.far)")
                .note("Instant and read-only.")
                .note("A cell is within reach exactly when numen.build.place would place it from here.")
                .seeAlso("build place", "build raise");
    }
}
