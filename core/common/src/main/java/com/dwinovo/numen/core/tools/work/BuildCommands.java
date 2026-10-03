package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.agent.script.ScriptType;
import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.Action;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.build.BuildPalette;
import com.dwinovo.numen.core.build.Design;
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.build.Primitive;
import com.dwinovo.numen.core.nav.Feet;
import com.dwinovo.numen.core.tools.BuildOps;
import com.dwinovo.numen.core.tools.DesignOps;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;

/**
 * {@code build}:原语、设计、建成的房子。设计稿见 {@code docs/build-designs.md}。
 *
 * <ul>
 *   <li><b>原语</b>({@code set place line layer cylinder sphere copy}):不带 {@code into} 当场放(长活,交任务槽,只放站在原地
 *       够得着的格),带 {@code into = <设计>} 就把这一步记进设计(当场回):默认接在末尾,{@code step = N} 换掉第 N 步,
 *       {@code before = N} 插在第 N 步前面。两条路读的是同一份参数、画的是同一些格子。</li>
 *   <li><b>设计</b>({@code new show drop designs delete}):改的是设计库里的一份文本,不动世界,当场回。</li>
 *   <li><b>{@code at}</b>:把一处手够得着的格变成设计或蓝图文件的样子——不走动、不挖;{@code left} 数这一处还差什么
 *       (够得着的、要先挖开的、够不着的);{@code built} 列出建成的房子。走到够得着的地方、挖开挡着的,由脚本组合
 *       (库里的 {@code build.raise})。</li>
 * </ul>
 *
 * <p>原语没写 {@code block} 时用她主手里那种方块,没写 {@code at} 的 {@code layer} 落在设计原点(记进设计时)或她脚下那一格
 * (当场盖时):这两样在画之前填进参数({@link #resolved}),所以记进设计的每一步都写着方块与位置。
 */
public final class BuildCommands {

    static final String GROUP = "build";

    private static final Param<String> NEW_NAME = Param.required("name", ArgType.word(),
            "Name of the new design: lowercase letters, digits, _ and -.");
    private static final Param<String> DESIGN = Param.required("design", ArgType.word(), "The design.")
            .values("a design name, as `build.designs()` lists it");
    private static final Param<String> SOURCE = Param.required("name", ArgType.string(),
                    "The design or blueprint file.")
            .values("a name as `build.designs()` lists it");
    /** 设计的一步:{@code house/4}。 */
    private static final Param<StepRef> STEP_REF = Param.required("step", ArgType.string().as("design/step",
                    "a design and a step number, like \"house/4\"", StepRef::parse, StepRef::written),
            "The step, written design/step as `build.show` numbers them (\"house/4\").");
    private static final Param<Integer> LAYER = Param.optional("layer", ArgType.integer(),
                    "Draw one level of it as a map seen from above instead: the block that ends up in every cell, later "
                            + "steps over earlier ones.")
            .values("a y in the design's own coordinates, as the steps write it; a blueprint file's lowest level is 0")
            .whenOmitted("list the steps, or price the blueprint file");
    private static final Param<BlockPos> AT_CELL = Param.optional("at", ArgType.cell(),
            "The world cell the design's origin {x = 0, y = 0, z = 0} goes to (a blueprint file's lowest north-west "
                    + "corner): what the "
                    + "design draws at 0 0 0 is built there, y=1 one higher. A floor drawn at y=0 given the ground's "
                    + "own y replaces the top ground block, flush with the ground outside; given one more it sits on "
                    + "top of the ground.")
            .whenOmitted("build it at the cell you stand in");
    private static final Param<Integer> AT_ROTATION = Param.optional("rotation", ArgType.integer(),
                    "Turn it clockwise seen from above, about its 0 0 0, which stays on at: at 90 the design's +x "
                            + "runs south and what faced north faces east.")
            .values("0, 90, 180 or 270")
            .whenOmitted("keep it as drawn");

    /** 一次放方块(当场放的原语、{@code build.at})收尾时的结果。 */
    static final ScriptType.Class BUILT_CLASS = new ScriptType.Class("Placed",
            "What a build.at or a primitive placed now did.", null, java.util.List.of(
            ScriptType.field("placed", ScriptType.INTEGER, "Blocks placed."),
            ScriptType.field("left", ScriptType.INTEGER, "Cells still to do."),
            ScriptType.optional("completed", ScriptType.INTEGER, "Cells that now match."),
            ScriptType.optional("replaced", ScriptType.INTEGER, null),
            ScriptType.optional("cleared", ScriptType.INTEGER, null),
            ScriptType.optional("removed", ScriptType.INTEGER, null),
            ScriptType.optional("building", ScriptType.STRING, "The building's name, house#1."),
            ScriptType.optional("site", ScriptType.listOf(Shapes.POS.type()), "Two corners of the site."),
            ScriptType.optional("still_short", ScriptType.STRING, "What you are short of."),
            ScriptType.optional("settled_away", ScriptType.INTEGER, "When the last cell went in: cells that changed "
                    + "once the world settled (vanilla would not hold them as drawn, or their neighbours reshape them).")));
    /** 一份设计。 */
    static final ScriptType.Class DESIGN_CLASS = new ScriptType.Class("Design", "One of the designs.", null,
            java.util.List.of(ScriptType.field("name", ScriptType.STRING, null),
                    ScriptType.field("steps", ScriptType.INTEGER, null),
                    ScriptType.field("cells", ScriptType.INTEGER, null)));
    static final ScriptType BUILT = BUILT_CLASS.type();
    static final ScriptType DESIGN_INFO = DESIGN_CLASS.type();

    private BuildCommands() {}

    /** 设计的第几步:{@code house/4}。 */
    record StepRef(String design, int step) {

        static StepRef parse(String text) {
            int slash = text.lastIndexOf('/');
            if (slash <= 0) {
                throw new IllegalArgumentException("a step is written design/step, like house/4; got \"" + text + "\"");
            }
            try {
                return new StepRef(Design.checkedName(text.substring(0, slash)),
                        Integer.parseInt(text.substring(slash + 1)));
            } catch (NumberFormatException notNumber) {
                throw new IllegalArgumentException("a step is written design/step with a step number, like house/4; "
                        + "got \"" + text + "\"");
            }
        }

        String written() {
            return design + "/" + step;
        }
    }

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Building: primitives you place now or collect into a design, and building a "
                + "design or blueprint file at a spot, as far as your hand reaches from where you stand. build.raise "
                + "(library) walks the site and builds all of it.", BuildCommands::actions);
    }

    private static void actions(CommandGroup build) {
        build.declare(BUILT_CLASS);
        build.declare(DESIGN_CLASS);
        primitives(build);
        designs(build);
        build.server("at", "Make the cells of a spot that your hand reaches from where you stand look like a design or "
                        + "blueprint file: builds it the first time, and changes it to match after.", BuildCommands::at,
                        SOURCE, AT_CELL, AT_ROTATION)
                .returns(BUILT)
                .example("build.at(\"house\", {at = {x = 100, y = 64, z = -20}})")
                .example("build.at(\"japanese_cottage\", {at = {x = 100, y = 64, z = -20}, rotation = 90})")
                .note("It never walks and never digs: it places only the cells within reach of where you stand. "
                        + "`build.left` says what is still to do and where; `build.raise` (library) walks the site, "
                        + "digs what is in the way and calls this until the whole building stands.")
                .note("Background work: fails at once with kind out_of_reach when nothing of it is within reach to "
                        + "place, with how many cells are left and a hint with the move.to call to the lowest "
                        + "nearest one; nothing starts. Otherwise it returns when the job ends: how many blocks were "
                        + "placed and how many cells are still to do.")
                .note("The same design (or file) at the same dimension and spot is the same building: building it "
                        + "again after changing the design adds what is missing, replaces what differs, and removes "
                        + "only blocks you placed there before that the design no longer has and that nobody has "
                        + "changed since. When nothing differs it says so and does nothing.")
                .note("Survival: a design is priced as a whole before the first block and refused, placing nothing, "
                        + "when anything is short; a blueprint file builds as far as your stock goes. Where another "
                        + "block stands it places nothing: dig it out first with work.dig (build.left lists those "
                        + "cells). Creative replaces it at once.")
                .note("Asks your owner first when their rules say so, for the cells it would change; a refusal stops "
                        + "it with their words.")
                .seeAlso("build left", "build raise", "build show", "build built", "task stop");
        build.server("left", "What a spot still lacks to look like a design or blueprint file, seen from where you "
                        + "stand: how many cells are within reach to place, which must be dug out first, how many are "
                        + "out of reach and the lowest nearest of those.",
                        (src, args) -> src.reply(BuildOps.left(src.companion(), args.get(SOURCE), spot(src, args),
                                Placement.quarters(args.get(AT_ROTATION)))), SOURCE, AT_CELL, AT_ROTATION)
                .returns(ScriptType.table(
                        ScriptType.field("left", ScriptType.INTEGER, "Cells still to do."),
                        ScriptType.field("reach", ScriptType.INTEGER, "Within reach to place now."),
                        ScriptType.field("dig", ScriptType.listOf(Shapes.POS.type()), "Up to 16 cells holding another "
                                + "block to dig out first, nearest first."),
                        ScriptType.field("far", ScriptType.INTEGER, "Out of reach."),
                        ScriptType.optional("next", Shapes.POS.type(), "The lowest, then nearest, of those out of "
                                + "reach: move.to(left.next, {arrive = \"place\"}) gets within reach of it."),
                        ScriptType.optional("short", ScriptType.INTEGER, "Cells holding another block with nothing "
                                + "of yours to put there."),
                        ScriptType.optional("unheld", ScriptType.INTEGER, "Cells that would not stay put yet: what "
                                + "holds them (the block below, the wall behind) is not built."),
                        ScriptType.optional("skipped", ScriptType.INTEGER, "Cells you leave alone.")))
                .example("local left = build.left(\"house\", {at = {x = 100, y = 64, z = -20}})\n"
                        + "if left.reach > 0 then build.at(\"house\", {at = {x = 100, y = 64, z = -20}}) end")
                .note("Instant and read-only.")
                .note("A cell is within reach exactly when build.at would place it from here.")
                .seeAlso("build at", "build raise");
        build.server("built", "The buildings made with `build.at`: design, dimension, spot, rotation, when and by whom.",
                        (src, args) -> src.reply(BuildOps.built(src.companion().getServer(), args)), Listing.PAGE)
                .returns("buildings", ScriptType.listOf(ScriptType.table(
                        ScriptType.field("name", ScriptType.STRING, "house#1"),
                        ScriptType.field("source", ScriptType.STRING, "The design or blueprint file."),
                        ScriptType.field("dimension", ScriptType.STRING, null),
                        ScriptType.field("at", Shapes.POS.type(), null),
                        ScriptType.field("rotation", ScriptType.INTEGER, null),
                        ScriptType.field("builder", ScriptType.STRING, null),
                        ScriptType.field("cells", ScriptType.INTEGER, "Blocks on its record."))))
                .example("build.built()")
                .note("Instant and read-only. A building whose design was deleted is still listed, and says so.")
                .seeAlso("build at");
    }

    /** 七个原语:同一个处理函数,带 {@code into} 记进设计,不带当场放。 */
    private static void primitives(CommandGroup build) {
        primitive(build, Primitive.SET, "Set one cell to exactly the block state written.")
                .example("build.set({x = 10, y = 65, z = 5}, {block = \"oak_stairs[facing=east,half=top]\"})")
                .example("build.set({x = 10, y = 64, z = 5}, {block = \"air\", into = \"house\"})")
                .note("For a block that should face the way you look, like a chest or a furnace, use `build.place`.");
        primitive(build, Primitive.PLACE, "Place one block the way a player right-clicks it in: it faces the way "
                + "you look.")
                .example("build.place({x = 10, y = 64, z = 5}, {block = \"crafting_table\"})")
                .example("build.place({x = 3, y = 1, z = 2}, {block = \"chest\", into = \"house\"})");
        primitive(build, Primitive.LINE, "A line of blocks between two points, diagonals included: beams, posts, "
                + "ridges.")
                .example("build.line({x = 0, y = 1, z = 0}, {x = 0, y = 3, z = 0}, {block = \"oak_log[axis=y]\", into = \"house\"})")
                .example("build.line({x = 10, y = 64, z = 5}, {x = 20, y = 64, z = 5}, {block = \"cobblestone_wall\"})");
        primitive(build, Primitive.LAYER, "Stamp a character grid: a floor, a wall ring, a roof course, a window "
                + "pattern, anything you can draw.")
                .example("build.layer({\"#####\", \"#####\", \"#####\"}, {at = {x = 0, y = 0, z = 0}, block = \"cobblestone\", "
                        + "into = \"house\"})")
                .example("build.layer({\"#####\", \"#...#\", \"#####\"}, {at = {x = 0, y = 1, z = 0}, "
                        + "block = \"oak_planks*8, spruce_planks*2\", up_to = 3, into = \"house\"})")
                .example("build.layer({\"<<<<<\", \".....\", \">>>>>\"}, {at = {x = 0, y = 4, z = 0}, "
                        + "legend = {\"<=oak_stairs[facing=south]\", \">=oak_stairs[facing=north]\"}, into = \"house\"})")
                .example("build.layer({\"#####\", \"#...#\", \"#####\"}, {at = {x = 0, y = 1, z = 0}, block = \"stone_bricks\", "
                        + "into = \"house\", step = 2})")
                .note("Rows run +x from at, the first row at its z and each next row one further south, so the grid "
                        + "reads like a map. ' ' and '.' leave a cell alone; an air block digs one out.");
        primitive(build, Primitive.CYLINDER, "A cylinder from its bottom centre: towers, wells, round rooms.")
                .example("build.cylinder({x = 5, y = 0, z = 5}, {radius = 3, height = 6, block = \"stone_bricks\", hollow = true, "
                        + "into = \"tower\"})");
        primitive(build, Primitive.SPHERE, "A sphere around its centre: domes, globes.")
                .example("build.sphere({x = 5, y = 8, z = 5}, {radius = 5, block = \"glass\", hollow = true, into = \"tower\"})");
        primitive(build, Primitive.COPY, "Copy a region to another corner, turned or mirrored: build one wing, "
                + "mirror it.")
                .example("build.copy({x = 0, y = 0, z = 0}, {x = 4, y = 5, z = 6}, {x = 10, y = 0, z = 0}, {mirror = \"left_right\", into = \"house\"})")
                .example("build.copy({x = 100, y = 64, z = 20}, {x = 104, y = 70, z = 26}, {x = 110, y = 64, z = 20})")
                .note("In a design it copies the design's own earlier steps; run now it copies what already stands "
                        + "in the world.");
    }

    /**
     * 登记一个原语:参数是原语自己的,最后接 {@code into}、{@code step}、{@code before}。所有原语都写同一句"当场放或记进设计"
     * 与"长活"的注意,各自的例子与注意由调用处接着写。
     */
    private static Action primitive(CommandGroup build, Primitive primitive, String summary) {
        List<Param<?>> params = new ArrayList<>(primitive.params());
        params.add(Primitive.Params.INTO);
        params.add(Primitive.Params.STEP);
        params.add(Primitive.Params.BEFORE);
        return build.server(primitive.action, summary, (src, args) -> run(src, primitive, args),
                        params.toArray(Param<?>[]::new))
                .returns(ScriptType.union(BUILT, DESIGN_INFO))
                .note("Without into it is placed now, at world coordinates, as background work, and only the cells "
                        + "within reach of where you stand: it fails with out_of_reach when none is, and returns what it "
                        + "placed when done. With into it becomes a step of that design, relative to its origin, and "
                        + "nothing is built yet: added after the last step, or in place of step N with step = N, or "
                        + "before it with before = N; it returns the design. `build.at` builds the design.")
                .note("Blocks are written as /setblock takes them, block state included; a door or a bed is written "
                        + "as its lower half or foot. Without block it uses the block in your main hand. Later steps "
                        + "overwrite earlier cells.")
                .note("Survival: every cell costs one matching item and a run that is short places nothing and "
                        + "says what is missing.");
    }

    private static void run(ServerSource src, Primitive primitive, CommandArgs given) {
        String into = given.get(Primitive.Params.INTO);
        Integer step = given.get(Primitive.Params.STEP);
        Integer before = given.get(Primitive.Params.BEFORE);
        if (into == null && (step != null || before != null)) {
            throw new IllegalArgumentException("step and before say where in a design the step goes; give the "
                    + "design with into");
        }
        if (step != null && before != null) {
            throw new IllegalArgumentException("give step = N to replace step N or before = N to insert before it, "
                    + "not both");
        }
        NumenPlayer her = src.companion();
        CommandArgs args = resolved(her, primitive, given, into != null);
        if (into == null) {
            // 重启后重放照填好的那一份:那时她手里、脚下可能已经不是现在这样
            BuildOps.now(src.replayedWith(args), primitive, args);
            return;
        }
        Design.Step written = new Design.Step(primitive, args);
        src.reply(step != null ? DesignOps.replace(her, into, step, written)
                : before != null ? DesignOps.insert(her, into, before, written)
                : DesignOps.append(her, into, written));
    }

    /**
     * 画之前把没写的两样填上,记进设计与当场盖读的是同一份:{@code block} 是她主手里那种方块(主手里不是方块时,要方块的原语
     * 当场说写上 {@code block};{@code layer} 有图例兜着,不填);{@code layer} 的 {@code at} 是设计原点或她脚下那一格。
     */
    private static CommandArgs resolved(NumenPlayer her, Primitive primitive, CommandArgs args, boolean inDesign) {
        CommandArgs out = args;
        boolean layer = primitive == Primitive.LAYER;
        Param<BuildPalette> block = layer ? Primitive.Params.FILL : Primitive.Params.BLOCK;
        if (primitive.params().contains(block) && out.get(block) == null) {
            BuildPalette held = held(her);
            if (held != null) {
                out = out.with(block, held);
            } else if (!layer) {
                throw new IllegalArgumentException("build." + primitive.action + " needs a block: give the block "
                        + "option (e.g. {block = \"stone_bricks\"}), or hold the block in your main hand");
            }
        }
        if (layer && out.get(Primitive.Params.AT) == null) {
            out = out.with(Primitive.Params.AT, inDesign ? BlockPos.ZERO : Feet.cell(her));
        }
        return out;
    }

    /** 她主手里那种方块;手里不是方块为 null。 */
    private static BuildPalette held(NumenPlayer her) {
        if (her.getMainHandItem().getItem() instanceof BlockItem item) {
            return BuildPalette.parse(BuiltInRegistries.BLOCK.getKey(item.getBlock()).toString());
        }
        return null;
    }

    /** 设计库:都改一份文本,不动世界,当场回。 */
    private static void designs(CommandGroup build) {
        build.server("new", "Start an empty design: a named list of primitive steps you can build anywhere.",
                        (src, args) -> src.reply(DesignOps.create(src.companion(), args.get(NEW_NAME))), NEW_NAME)
                .returns(DESIGN_INFO)
                .example("build.new(\"house\")")
                .note("Instant. Add steps with any primitive and {into = \"house\"}; coordinates in a design are "
                        + "relative to its origin {x = 0, y = 0, z = 0}, which `build.at` puts on a spot.")
                .seeAlso("build layer", "build show", "build at");
        build.server("show", "Show a design step by step with what each costs, or price a blueprint file; or draw "
                        + "one level of either as a map.", (src, args) -> src.reply(DesignOps.show(src.companion(),
                        args.get(SOURCE), args.get(LAYER), args)),
                        SOURCE, LAYER, Listing.PAGE)
                .returns(ScriptType.table(
                        ScriptType.field("name", ScriptType.STRING, null),
                        ScriptType.optional("steps", ScriptType.listOf(ScriptType.STRING),
                                "A design's steps, each the call that makes it."),
                        ScriptType.optional("cells", ScriptType.INTEGER, null),
                        ScriptType.optional("size", ScriptType.STRING, "x by y by z."),
                        ScriptType.optional("materials", new ScriptType.Simple("table<string, integer>"),
                                "Items for all of it."),
                        ScriptType.optional("short_of", new ScriptType.Simple("table<string, integer>"),
                                "What you are still short of, in survival."),
                        ScriptType.optional("layer_profile", new ScriptType.Simple("table<string, integer>"),
                                "A blueprint file's cells per level."),
                        ScriptType.optional("rows", ScriptType.listOf(ScriptType.STRING),
                                "With layer: that level drawn as a map, north first."),
                        ScriptType.optional("legend", ScriptType.STRING, "With layer: what each character is.")))
                .example("build.show(\"house\")")
                .example("build.show(\"house\", {layer = 1})")
                .example("build.show(\"my cottage\", {layer = 0})")
                .note("Instant and read-only: every step numbered with the call that makes it, the cells it covers "
                        + "and the blocks it takes; for a blueprint file its size, cells, every material and how its "
                        + "cells spread over height. In survival it also says what you are still short of for all of "
                        + "it. A long design comes a page at a time: {page = 2} shows the next.")
                .note("With layer = y it draws that level as it will stand when built, one character per cell in the "
                        + "same grid as build.layer: the first row is the north edge, each row runs east, '.' is "
                        + "nothing, and the legend names every character. Rows and columns are labelled with z and x.")
                .note("To change a step, call the primitive again with {into = <design>, step = N}; to insert one, "
                        + "before = N.")
                .seeAlso("build layer", "build at");
        build.server("drop", "Remove one step of a design.", (src, args) -> src.reply(DesignOps.drop(
                        src.companion(), args.get(STEP_REF).design(), args.get(STEP_REF).step())), STEP_REF)
                .returns(DESIGN_INFO)
                .example("build.drop(\"house/4\")")
                .note("Instant. The steps after it move down by one.")
                .seeAlso("build show");
        build.server("designs", "The designs and blueprint files you can build.",
                        (src, args) -> src.reply(DesignOps.library(src.companion().getServer(), args)), Listing.PAGE)
                .returns("designs", ScriptType.listOf(ScriptType.table(ScriptType.field("name", ScriptType.STRING, null),
                        ScriptType.field("kind", ScriptType.choice(java.util.List.of("design", "blueprint file")),
                                null))))
                .example("build.designs()")
                .note("Instant and read-only. Designs are shared by everyone on this server; blueprint files are the "
                        + ".litematic, .schem, .nbt and .snbt files in its schematics folder.")
                .seeAlso("build show", "build at");
        build.server("delete", "Delete a design.", (src, args) -> src.reply(DesignOps.delete(
                        src.companion(), args.get(DESIGN))), DESIGN)
                .returns(ScriptType.NOTHING)
                .example("build.delete(\"shed\")")
                .note("Instant. What was built from it stays standing and stays in `build.built`; it just cannot be "
                        + "changed through the design any more. Only your owner's companions delete their designs.")
                .seeAlso("build designs");
    }

    /** 没写 {@code at} 就是她脚下那一格。 */
    private static BlockPos spot(ServerSource src, CommandArgs args) {
        return args.get(AT_CELL) != null ? args.get(AT_CELL) : Feet.cell(src.companion());
    }

    /** 重启后重放照填好的那一格,不照那时她站在哪儿。 */
    private static void at(ServerSource src, CommandArgs args) {
        BlockPos at = spot(src, args);
        BuildOps.at(src.replayedWith(args.with(AT_CELL, at)), args.get(SOURCE), at,
                Placement.quarters(args.get(AT_ROTATION)));
    }
}
