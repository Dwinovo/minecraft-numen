package com.dwinovo.numen.core.build;

import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.build.ReplaceMode;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 建造的原语:{@code build} 组里画格子的那几个动作。每个原语是一条命令(参数就是它的写法),也是画布上的一步
 * ({@link #draw})——当场执行的一行、设计文件里的一行,读成同一份参数、画成同一些格子。
 *
 * <p>原语只管几何,<b>不管风格</b>。屋顶怎么举架、脊用什么料、墙面怎么做凹凸,是建筑知识,住在 {@code building_design}
 * 技能里;这里只提供"把格子放到哪儿、放成什么状态"。
 *
 * <p>原语操作的格是依次给的对象(一格、两头、三角、中心;{@code layer} 是那张字符图),方块是选项 {@code block}——一次调用只有
 * 一类对象。方块的写法和原版 {@code /setblock} 一字不差(状态跟在名字后面),也可以是加权混合({@link BuildPalette});
 * 不写 {@code block} 用的是她主手里那种方块,由执行这一步的一方在画之前填上({@code BuildCommands}),所以记进设计的每一步都写着
 * 方块。每一步可以带自己的让路档位({@code mask})。同一格后写覆盖先写,见 {@link Canvas}。设计文件里的一步是同一份参数写成的
 * 一行命令({@link Design})。
 */
public enum Primitive {

    /** 一格,照写下的方块状态直写。 */
    SET("set", List.of(Params.CELL, Params.BLOCK)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            canvas.put(masked(args, cell(block(args), args.get(Params.CELL))));
        }
    },

    /** 一格,像玩家右键那样放下这件东西:朝向随她的视线,模组钩在物品放置上的转换照常发生。 */
    PLACE("place", List.of(Params.CELL, Params.BLOCK)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            canvas.put(masked(args, cell(block(args), args.get(Params.CELL)).asItemPlace()));
        }
    },

    /** 两点之间一条线,斜的也行。 */
    LINE("line", List.of(Params.FROM, Params.TO, Params.BLOCK)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            BlockPos a = args.get(Params.FROM);
            BlockPos b = args.get(Params.TO);
            shape(args, canvas, BuildShapes.shapeCells("line", false, a.getX(), a.getY(), a.getZ(),
                    b.getX(), b.getY(), b.getZ(), null, null));
        }
    },

    /** 字符网格:图例里每个字符一种方块,铺一层,或从 y 一直铺到 {@code up_to}。 */
    LAYER("layer", List.of(Params.ROWS, Params.AT, Params.LEGEND, Params.FILL, Params.UP_TO)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            Map<Character, BuildPalette> legend = new HashMap<>();
            List<Legend> entries = args.get(Params.LEGEND);
            for (Legend entry : entries == null ? List.<Legend>of() : entries) {
                legend.put(entry.key(), entry.block());
            }
            BuildPalette fallback = args.get(Params.FILL);
            BlockPos at = args.get(Params.AT);
            if (at == null) {
                throw new IllegalArgumentException("layer: the at option names the cell the first row starts at");
            }
            int y = at.getY();
            Integer upTo = args.get(Params.UP_TO);
            for (BuildShapes.CharCell c : BuildShapes.layerCells(at.getX(), y, upTo == null ? y : upTo,
                    at.getZ(), args.get(Params.ROWS))) {
                BuildPalette palette = legend.getOrDefault(c.key(), fallback);
                if (palette == null) {
                    throw new IllegalArgumentException("layer: character '" + c.key()
                            + "' is not in the legend and there is no block option to fall back on");
                }
                canvas.put(masked(args, cell(palette, c.pos())));
            }
        }
    },

    /** 底面中心、半径、高度的圆柱。 */
    CYLINDER("cylinder", List.of(Params.CENTER, Params.BLOCK, Params.RADIUS, Params.HEIGHT, Params.HOLLOW)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            BlockPos c = args.get(Params.CENTER);
            shape(args, canvas, BuildShapes.shapeCells("cylinder", Boolean.TRUE.equals(args.get(Params.HOLLOW)),
                    c.getX(), c.getY(), c.getZ(), null, null, null, radius(args), height(args)));
        }
    },

    /** 球心、半径的球。 */
    SPHERE("sphere", List.of(Params.CENTER, Params.BLOCK, Params.RADIUS, Params.HOLLOW)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            BlockPos c = args.get(Params.CENTER);
            shape(args, canvas, BuildShapes.shapeCells("sphere", Boolean.TRUE.equals(args.get(Params.HOLLOW)),
                    c.getX(), c.getY(), c.getZ(), null, null, null, radius(args), null));
        }
    },

    /** 抄一片区域画到别处,可以转、可以镜像。 */
    COPY("copy", List.of(Params.FROM, Params.TO, Params.DEST, Params.ROTATION, Params.MIRROR, Params.INCLUDE_AIR)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            Mirror mirror = switch (args.get(Params.MIRROR) == null ? "none" : args.get(Params.MIRROR)) {
                case "left_right" -> Mirror.LEFT_RIGHT;
                case "front_back" -> Mirror.FRONT_BACK;
                default -> Mirror.NONE;
            };
            BuildCopy.Result copied = BuildCopy.copy(canvas, args.get(Params.FROM), args.get(Params.TO),
                    args.get(Params.DEST),
                    new Placement(BlockPos.ZERO, Placement.quarters(args.get(Params.ROTATION))).rotation(), mirror,
                    Boolean.TRUE.equals(args.get(Params.INCLUDE_AIR)));
            for (BuildTaskRecord.Target target : copied.targets()) {
                canvas.put(masked(args, target));
            }
            canvas.dropped(copied.dropped());
        }
    };

    /** 组里的名字,{@code build <名字>}。 */
    public final String action;
    private final List<Param<?>> params;

    Primitive(String action, List<Param<?>> own) {
        this.action = action;
        List<Param<?>> all = new ArrayList<>(own);
        all.add(Params.MASK);
        this.params = List.copyOf(all);
    }

    /** 这个原语的参数表:它自己的几个,最后是让路档位 {@code --mask}。设计文件里的一步就按这张表写回。 */
    public List<Param<?>> params() {
        return params;
    }

    /** 画一步:格子按顺序画上画布,写了 {@code --mask} 的每一格带上这一档。 */
    public void draw(CommandArgs args, Canvas canvas) {
        canvas.nextStep();
        paint(args, canvas);
    }

    abstract void paint(CommandArgs args, Canvas canvas);

    /** {@code build <名字>} 那一行对应的原语;不是原语是 null。 */
    public static Primitive named(String action) {
        for (Primitive p : values()) {
            if (p.action.equals(action)) {
                return p;
            }
        }
        return null;
    }

    private static void shape(CommandArgs args, Canvas canvas, List<BlockPos> cells) {
        BuildPalette palette = block(args);
        for (BlockPos pos : cells) {
            canvas.put(masked(args, cell(palette, pos)));
        }
    }

    /** 这一步的方块:{@code --block};执行这一行的一方在画之前已经按她主手里的填好,还是没有就是这一步没写方块。 */
    private static BuildPalette block(CommandArgs args) {
        BuildPalette palette = args.get(Params.BLOCK);
        if (palette == null) {
            throw new IllegalArgumentException("this step names no block: give the block option, e.g. {block = \"stone_bricks\"}");
        }
        return palette;
    }

    private static int radius(CommandArgs args) {
        return args.get(Params.RADIUS) == null ? Params.DEFAULT_RADIUS : args.get(Params.RADIUS);
    }

    private static int height(CommandArgs args) {
        return args.get(Params.HEIGHT) == null ? 1 : args.get(Params.HEIGHT);
    }

    /** 一格:调色板按位置取料,方块状态就是它自己带的那份。 */
    private static BuildTaskRecord.Target cell(BuildPalette palette, BlockPos pos) {
        BuildPalette.Entry e = palette.pick(pos);
        return new BuildTaskRecord.Target(e.state(), e.item(), pos, e.label());
    }

    private static BuildTaskRecord.Target masked(CommandArgs args, BuildTaskRecord.Target target) {
        ReplaceMode mode = mode(args.get(Params.MASK));
        return mode == null ? target : target.withMask(mode);
    }

    /** 写下的档位名 → 让路档位;没写是 null(按格子默认的 carve)。几个名字由参数类型把关。 */
    private static ReplaceMode mode(String mask) {
        if (mask == null) {
            return null;
        }
        return switch (mask) {
            case "overwrite" -> ReplaceMode.REPLACE_ANY;
            case "solid" -> ReplaceMode.REPLACE_SOLID;
            case "keep" -> ReplaceMode.DONT_REPLACE;
            default -> ReplaceMode.REPLACE_EMPTY;
        };
    }

    /**
     * {@code layer} 图例里的一项:一个字符、{@code =}、一种方块(写法同 {@link BuildPalette}),如 {@code <=oak_stairs[facing=south]}。
     * 读命令行时就认好,写回是原来那段文字。
     */
    public record Legend(char key, BuildPalette block) {

        static final ArgType<Legend> ARG = ArgType.string().as("legend entry",
                "one character, =, and a block, like #=stone_bricks; quote it if the block is a mix with spaces",
                Legend::parse, Legend::written);

        static Legend parse(String entry) {
            if (entry.length() < 3 || entry.charAt(1) != '=') {
                throw new IllegalArgumentException("a legend entry is one character, =, and a block, like "
                        + "#=stone_bricks; got \"" + entry + "\"");
            }
            return new Legend(entry.charAt(0), BuildPalette.parse(entry.substring(2)));
        }

        String written() {
            return key + "=" + block.spec();
        }
    }

    /** 原语的参数:每一个都是命令行上的写法、帮助里的一行、设计文件里一步的那一截。 */
    public static final class Params {

        private Params() {}

        /** {@code --radius} 不写时的半径。 */
        static final int DEFAULT_RADIUS = 3;
        /** 不写 {@code --block} 时用什么:回执与帮助里这样说。 */
        public static final String HELD = "use the block in your main hand";

        public static final Param<BlockPos> CELL = Param.required("cell", ArgType.cell(), "The cell.");
        public static final Param<BlockPos> FROM = Param.required("from", ArgType.cell(), "The first corner or end.");
        public static final Param<BlockPos> TO = Param.required("to", ArgType.cell(), "The second corner or end.");
        public static final Param<BlockPos> DEST = Param.required("dest", ArgType.cell(),
                "Where the first corner's copy goes.");
        public static final Param<BlockPos> CENTER = Param.required("center", ArgType.cell(),
                "The centre: a cylinder's bottom centre, a sphere's middle.");
        public static final Param<BlockPos> AT = Param.optional("at", ArgType.cell(),
                        "Where the grid's first character goes: {x, y, z}.")
                .whenOmitted("start at the design's origin {0, 0, 0} with into, else at the cell you stand in");
        public static final Param<BuildPalette> BLOCK = Param.optional("block", BuildPalette.ARG,
                        "The block, written exactly as /setblock takes it, block state included.")
                .values("an id such as \"stone_bricks\" or \"oak_stairs[facing=north,half=top]\", or a weighted mix "
                        + "such as \"stone_bricks*8, mossy_stone_bricks\"; \"air\" clears the cell")
                .whenOmitted(HELD);
        public static final Param<Integer> RADIUS = Param.optional("radius", ArgType.integer(1, 64),
                "Radius in blocks.").whenOmitted("use " + DEFAULT_RADIUS);
        public static final Param<Integer> HEIGHT = Param.optional("height", ArgType.integer(1, 256),
                "Height in blocks, upward from the centre.").whenOmitted("make it one block high, a disc");
        public static final Param<Boolean> HOLLOW = Param.optional("hollow", ArgType.bool(),
                        "Keep only the outer shell.")
                .whenOmitted("fill it solid");
        public static final Param<List<String>> ROWS = Param.required("rows", ArgType.list(ArgType.string()),
                        "The grid, one string per row: the first row starts at the at option and each row runs +x, the "
                                + "next row one further south, so it reads like a map with north at the top. ' ' and "
                                + "'.' leave a cell alone.")
                .values("a list of rows like {\"#####\", \"#...#\", \"#####\"}");
        public static final Param<List<Legend>> LEGEND = Param.optional("legend", ArgType.list(Legend.ARG),
                        "Which block each character of the grid is.")
                .values("a list of entries like {\"#=stone_bricks\", \"<=oak_stairs[facing=south]\"}")
                .whenOmitted("use the block option for every character");
        public static final Param<BuildPalette> FILL = Param.optional("block", BuildPalette.ARG,
                        "The block for every grid character the legend does not name, written as /setblock takes it.")
                .whenOmitted(HELD + ", if you hold one; otherwise every character must be in the legend");
        public static final Param<Integer> UP_TO = Param.optional("up_to", ArgType.integer(),
                        "Repeat the same grid on every level from at's y up to this one: a four-high wall ring is "
                                + "one layer.")
                .whenOmitted("lay the grid on level y only");
        public static final Param<Integer> ROTATION = Param.optional("rotation", ArgType.integer(),
                        "Turn the copy clockwise, in degrees.")
                .values("0, 90, 180 or 270")
                .whenOmitted("keep it as it is");
        public static final Param<String> MIRROR = Param.optional("mirror",
                        ArgType.oneOf("none", "left_right", "front_back"),
                        "Mirror the copy across an axis; stair corners, door hinges and bed heads flip correctly.")
                .whenOmitted("not mirror it");
        public static final Param<Boolean> INCLUDE_AIR = Param.optional("include_air", ArgType.bool(),
                        "Also copy the empty cells, so the destination is hollowed out to match.")
                .whenOmitted("copy only the blocks");
        public static final Param<String> MASK = Param.optional("mask",
                        ArgType.oneOf("carve", "overwrite", "solid", "keep"),
                        "What happens where something already stands: carve builds through anything and an air "
                                + "cell digs that cell out; overwrite builds through anything but leaves air cells "
                                + "alone; solid only overwrites with full blocks; keep only builds into air, grass and "
                                + "other replaceable cells.")
                .whenOmitted("carve");
        /**
         * 这一步记进哪份设计。它不是原语的一部分(不在 {@link #params()} 里):设计里的一步不带它,
         * 写不写它只决定这一行是当场画到世界里还是记进设计({@link #STEP}、{@link #BEFORE} 说记在哪一步)。
         */
        public static final Param<String> INTO = Param.optional("into", ArgType.word(),
                        "Add this step to a design instead of building it now; the coordinates are then relative to "
                                + "the design's origin (0,0,0).")
                .values("a design name, as build.designs lists it")
                .whenOmitted("build it now, at these world coordinates");
        /** 和 {@link #INTO} 一起:换掉设计的这一步。 */
        public static final Param<Integer> STEP = Param.optional("step", ArgType.integer(1, 999),
                        "With into: replace this step of the design, as `build.show` numbers them.")
                .whenOmitted("add it after the last step");
        /** 和 {@link #INTO} 一起:插在这一步前面。 */
        public static final Param<Integer> BEFORE = Param.optional("before", ArgType.integer(1, 999),
                        "With into: insert it before this step; one past the last step appends.")
                .whenOmitted("add it after the last step");
    }
}
