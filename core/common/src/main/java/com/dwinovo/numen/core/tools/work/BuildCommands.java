package com.dwinovo.numen.core.tools.work;

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
import com.dwinovo.numen.core.build.Placement;
import com.dwinovo.numen.core.build.Primitive;
import com.dwinovo.numen.core.tools.BuildOps;
import com.dwinovo.numen.core.tools.DesignOps;

import net.minecraft.core.BlockPos;

/**
 * {@code build}:原语、设计、建成的房子。设计稿见 {@code docs/build-designs.md}。
 *
 * <ul>
 *   <li><b>原语</b>({@code set place line layer cylinder sphere copy}):不带 {@code --into} 当场执行(长活,交任务槽),
 *       带 {@code --into <设计>} 就把这一步记进设计的末尾(当场回)。两条路读的是同一份参数、画的是同一些格子。</li>
 *   <li><b>设计</b>({@code new show step insert drop designs delete}):改的是设计库里的一份文本,不动世界,当场回。</li>
 *   <li><b>{@code at}</b>:把一处变成设计或蓝图文件的样子——第一次是盖,之后是按差异改(长活);{@code built} 列出建成的房子。</li>
 * </ul>
 */
public final class BuildCommands {

    static final String GROUP = "build";

    private static final Param<String> NEW_NAME = Param.required("name", ArgType.word(),
            "Name of the new design: lowercase letters, digits, _ and -.");
    private static final Param<String> DESIGN = Param.required("design", ArgType.word(), "The design.")
            .values("a design name, as `build designs` lists it");
    private static final Param<String> SOURCE = Param.required("name", ArgType.string(),
                    "The design or blueprint file.")
            .values("a name as `build designs` lists it");
    private static final Param<Integer> STEP = Param.required("step", ArgType.integer(1, 999),
            "The step number, as `build show` numbers the steps.");
    private static final Param<String> PRIMITIVE = Param.required("primitive", ArgType.text(),
                    "The step: one primitive written as it follows build, without --into.")
            .values("e.g. layer 0 1 0 ##### #...# ##### --block oak_planks");
    private static final Param<Integer> LAYER = Param.optional("layer", ArgType.integer(),
                    "Draw one level of it as a map seen from above instead: the block that ends up in every cell, later "
                            + "steps over earlier ones.")
            .values("a y in the design's own coordinates, as the steps write it; a blueprint file's lowest level is 0")
            .whenOmitted("list the steps, or price the blueprint file");
    private static final Param<Integer> AT_X = Param.required("x", ArgType.integer(),
            "X of the world cell the design's 0 0 0 goes to (a blueprint file's lowest north-west corner): what "
                    + "the design draws at x=0 is built at this x.");
    private static final Param<Integer> AT_Y = Param.required("y", ArgType.integer(),
            "Y of that cell: what the design draws at y=0 is built at this y, y=1 one higher. A floor drawn at y=0 "
                    + "given the ground's own y replaces the top ground block, flush with the ground outside; given "
                    + "one more it sits on top of the ground.");
    private static final Param<Integer> AT_Z = Param.required("z", ArgType.integer(),
            "Z of that cell: what the design draws at z=0 is built at this z.");
    private static final Param<Integer> AT_ROTATION = Param.optional("rotation", ArgType.integer(),
                    "Turn it clockwise seen from above, about its 0 0 0, which stays on x y z: at 90 the design's +x "
                            + "runs south and what faced north faces east.")
            .values("0, 90, 180 or 270")
            .whenOmitted("keep it as drawn");
    private BuildCommands() {}

    public static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Building: primitives you run now or collect into a design, and building a "
                + "design or blueprint file at a spot.", BuildCommands::actions);
    }

    private static void actions(CommandGroup build) {
        primitives(build);
        designs(build);
        build.server("at", "Make a spot look like a design or blueprint file: builds it there the first time, and "
                        + "changes it to match on every run after.", BuildCommands::at, SOURCE, AT_X, AT_Y, AT_Z,
                        AT_ROTATION)
                .example("build at house 100 64 -20")
                .example("build at japanese_cottage 100 64 -20 --rotation 90")
                .note("Background work: returns at once; the end arrives as a task_finished event saying how many "
                        + "blocks were placed, replaced and removed, and what is missing.")
                .note("The same design (or file) at the same dimension and spot is the same building: running it "
                        + "again after changing the design adds what is missing, replaces what differs, and removes "
                        + "only blocks you placed there before that the design no longer has and that nobody has "
                        + "changed since. When nothing differs it says so and does nothing.")
                .note("Survival: a design is priced as a whole before the first block and refused, placing nothing, "
                        + "when anything is short; a blueprint file builds as far as your stock goes, and the same "
                        + "line again carries on where it stopped. Creative builds freely.")
                .note("Asks your owner first when their rules say so, for the cells it would change.")
                .seeAlso("build show", "build built", "task stop");
        build.server("built", "The buildings made with `build at`: design, dimension, spot, rotation, when and by whom.",
                        (src, args) -> src.reply(BuildOps.built(src.companion().getServer(), args)), Listing.PAGE)
                .example("build built")
                .note("Instant and read-only. A building whose design was deleted is still listed, and says so.")
                .seeAlso("build at");
    }

    /** 七个原语:同一个处理函数,带 {@code --into} 记进设计,不带当场执行。 */
    private static void primitives(CommandGroup build) {
        primitive(build, Primitive.SET, "Set one cell to exactly the block state written.")
                .example("build set oak_stairs[facing=east,half=top] 10 65 5")
                .example("build set air 10 64 5 --into house")
                .note("For a block that should face the way you look, like a chest or a furnace, use `build place`.");
        primitive(build, Primitive.PLACE, "Place one block the way a player right-clicks it in: it faces the way "
                + "you look.")
                .example("build place crafting_table 10 64 5")
                .example("build place chest 3 1 2 --into house");
        primitive(build, Primitive.LINE, "A line of blocks between two points, diagonals included: beams, posts, "
                + "ridges.")
                .example("build line oak_log[axis=y] 0 1 0 0 3 0 --into house")
                .example("build line cobblestone_wall 10 64 5 20 64 5");
        primitive(build, Primitive.LAYER, "Stamp a character grid: a floor, a wall ring, a roof course, a window "
                + "pattern, anything you can draw.")
                .example("build layer 0 0 0 ##### ##### ##### --block cobblestone --into house")
                .example("build layer 0 1 0 ##### #...# ##### --block \"oak_planks*8, spruce_planks*2\" --up_to 3 "
                        + "--into house")
                .example("build layer 0 4 0 <<<<< ..... >>>>> --legend <=oak_stairs[facing=south] "
                        + ">=oak_stairs[facing=north] --into house")
                .note("Rows run +x from x, the first row at z and each next row one further south, so the grid reads "
                        + "like a map. ' ' and '.' leave a cell alone; an air block digs one out.");
        primitive(build, Primitive.CYLINDER, "A cylinder from its bottom centre: towers, wells, round rooms.")
                .example("build cylinder stone_bricks 5 0 5 3 6 --hollow true --into tower");
        primitive(build, Primitive.SPHERE, "A sphere around its centre: domes, globes.")
                .example("build sphere glass 5 8 5 5 --hollow true --into tower");
        primitive(build, Primitive.COPY, "Copy a region to another corner, turned or mirrored: build one wing, "
                + "mirror it.")
                .example("build copy 0 0 0 4 5 6 10 0 0 --mirror left_right --into house")
                .example("build copy 100 64 20 104 70 26 110 64 20")
                .note("In a design it copies the design's own earlier steps; run now it copies what already stands "
                        + "in the world.");
    }

    /**
     * 登记一个原语:参数是原语自己的,最后接 {@code --into}。所有原语都写同一句"当场执行或记进设计"与"长活"的注意,
     * 各自的例子与注意由调用处接着写。
     */
    private static Action primitive(CommandGroup build, Primitive primitive, String summary) {
        List<Param<?>> params = new ArrayList<>(primitive.params());
        params.add(Primitive.Params.INTO);
        return build.server(primitive.action, summary, (src, args) -> run(src, primitive, args),
                        params.toArray(Param<?>[]::new))
                .note("Without --into it is built now, at world coordinates, as background work: the end arrives as "
                        + "a task_finished event. With --into it becomes the last step of that design, relative to "
                        + "its origin, and nothing is built yet: `build at` builds the design.")
                .note("Blocks are written as /setblock takes them, block state included; a door or a bed is written "
                        + "as its lower half or foot. Later steps overwrite earlier cells.")
                .note("Survival: every cell costs one matching item and a run that is short places nothing and "
                        + "says what is missing.");
    }

    private static void run(ServerSource src, Primitive primitive, CommandArgs args) {
        if (args.get(Primitive.Params.INTO) != null) {
            src.reply(DesignOps.append(src.companion(), primitive, args));
        } else {
            BuildOps.now(src, primitive, args);
        }
    }

    /** 设计库:都改一份文本,不动世界,当场回。 */
    private static void designs(CommandGroup build) {
        build.server("new", "Start an empty design: a named list of primitive steps you can build anywhere.",
                        (src, args) -> src.reply(DesignOps.create(src.companion(), args.get(NEW_NAME))), NEW_NAME)
                .example("build new house")
                .note("Instant. Add steps with any primitive and --into house; coordinates in a design are relative "
                        + "to its origin (0,0,0), which `build at` puts on a spot.")
                .seeAlso("build layer", "build show", "build at");
        build.server("show", "Show a design step by step with what each costs, or price a blueprint file; or draw "
                        + "one level of either as a map.", (src, args) -> src.reply(DesignOps.show(src.companion(),
                        args.get(SOURCE), args.get(LAYER), args, args.write(GROUP + " show", List.of(SOURCE, LAYER)))),
                        SOURCE, LAYER, Listing.PAGE)
                .example("build show house")
                .example("build show house --layer 1")
                .example("build show \"my cottage\" --layer 0")
                .note("Instant and read-only: every step numbered with its line, the cells it covers and the blocks "
                        + "it takes; for a blueprint file its size, cells, every material and how its cells spread "
                        + "over height. In survival it also says what you are still short of for all of it. A long "
                        + "design comes a page at a time: --page 2 shows the next.")
                .note("With --layer y it draws that level as it will stand when built, one character per cell in the "
                        + "same grid as build layer: the first row is the north edge, each row runs east, '.' is "
                        + "nothing, and the legend names every character. Rows and columns are labelled with z and x.")
                .seeAlso("build step", "build at");
        build.server("step", "Replace one step of a design.", (src, args) -> src.reply(DesignOps.replace(
                        src.companion(), args.get(DESIGN), args.get(STEP), args.get(PRIMITIVE))), DESIGN, STEP, PRIMITIVE)
                .example("build step house 2 layer 0 1 0 ##### #...# ##### --block stone_bricks --up_to 3")
                .note("Instant. The whole design is drawn again; a step that does not draw is refused and the "
                        + "design stays as it was. Only your owner's companions change their designs.")
                .seeAlso("build show", "build insert", "build drop");
        build.server("insert", "Insert a step before another one.", (src, args) -> src.reply(DesignOps.insert(
                        src.companion(), args.get(DESIGN), args.get(STEP), args.get(PRIMITIVE))), DESIGN, STEP, PRIMITIVE)
                .example("build insert house 3 set oak_door[facing=south] 2 1 0")
                .note("Instant. Step numbers after it move up by one; one past the last step appends.")
                .seeAlso("build show", "build step");
        build.server("drop", "Remove one step of a design.", (src, args) -> src.reply(DesignOps.drop(
                        src.companion(), args.get(DESIGN), args.get(STEP))), DESIGN, STEP)
                .example("build drop house 4")
                .note("Instant. The steps after it move down by one.")
                .seeAlso("build show");
        build.server("designs", "The designs and blueprint files you can build.",
                        (src, args) -> src.reply(DesignOps.library(src.companion().getServer(), args)), Listing.PAGE)
                .example("build designs")
                .note("Instant and read-only. Designs are shared by everyone on this server; blueprint files are the "
                        + ".litematic, .schem, .nbt and .snbt files in its schematics folder.")
                .seeAlso("build show", "build at");
        build.server("delete", "Delete a design.", (src, args) -> src.reply(DesignOps.delete(
                        src.companion(), args.get(DESIGN))), DESIGN)
                .example("build delete shed")
                .note("Instant. What was built from it stays standing and stays in `build built`; it just cannot be "
                        + "changed through the design any more. Only your owner's companions delete their designs.")
                .seeAlso("build designs");
    }

    private static void at(ServerSource src, CommandArgs args) {
        BuildOps.at(src, args.get(SOURCE), new BlockPos(args.get(AT_X), args.get(AT_Y), args.get(AT_Z)),
                Placement.quarters(args.get(AT_ROTATION)));
    }
}
