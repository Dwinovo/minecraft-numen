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
 * <p>方块的写法和原版 {@code /setblock} 一字不差(状态跟在名字后面),也可以是加权混合({@link BuildPalette});
 * 每一步可以带自己的让路档位({@code --mask})。同一格后写覆盖先写,见 {@link Canvas}。
 */
public enum Primitive {

    /** 一格,照写下的方块状态直写。 */
    SET("set", List.of(Params.BLOCK, Params.X, Params.Y, Params.Z)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            BlockPos pos = new BlockPos(args.get(Params.X), args.get(Params.Y), args.get(Params.Z));
            canvas.put(masked(args, cell(BuildPalette.parse(args.get(Params.BLOCK)), pos)));
        }
    },

    /** 一格,像玩家右键那样放下这件东西:朝向随她的视线,模组钩在物品放置上的转换照常发生。 */
    PLACE("place", List.of(Params.BLOCK, Params.X, Params.Y, Params.Z)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            BlockPos pos = new BlockPos(args.get(Params.X), args.get(Params.Y), args.get(Params.Z));
            canvas.put(masked(args, cell(BuildPalette.parse(args.get(Params.BLOCK)), pos).asItemPlace()));
        }
    },

    /** 两点之间一条线,斜的也行。 */
    LINE("line", List.of(Params.BLOCK, Params.X1, Params.Y1, Params.Z1, Params.X2, Params.Y2, Params.Z2)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            shape(args, canvas, BuildShapes.shapeCells("line", false,
                    args.get(Params.X1), args.get(Params.Y1), args.get(Params.Z1),
                    args.get(Params.X2), args.get(Params.Y2), args.get(Params.Z2), null, null));
        }
    },

    /** 字符网格:图例里每个字符一种方块,铺一层,或从 y 一直铺到 {@code --up_to}。 */
    LAYER("layer", List.of(Params.X, Params.Y, Params.Z, Params.ROWS, Params.LEGEND, Params.FILL, Params.UP_TO)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            Map<Character, BuildPalette> legend = new HashMap<>();
            List<String> entries = args.get(Params.LEGEND);
            for (String entry : entries == null ? List.<String>of() : entries) {
                if (entry.length() < 3 || entry.charAt(1) != '=') {
                    throw new IllegalArgumentException("a legend entry is one character, =, and a block, like "
                            + "#=stone_bricks; got \"" + entry + "\"");
                }
                legend.put(entry.charAt(0), BuildPalette.parse(entry.substring(2)));
            }
            String fill = args.get(Params.FILL);
            BuildPalette fallback = fill == null ? null : BuildPalette.parse(fill);
            int y = args.get(Params.Y);
            Integer upTo = args.get(Params.UP_TO);
            for (BuildShapes.CharCell c : BuildShapes.layerCells(args.get(Params.X), y, upTo == null ? y : upTo,
                    args.get(Params.Z), args.get(Params.ROWS))) {
                BuildPalette palette = legend.getOrDefault(c.key(), fallback);
                if (palette == null) {
                    throw new IllegalArgumentException("layer: character '" + c.key()
                            + "' is not in the legend and there is no --block to fall back on");
                }
                canvas.put(masked(args, cell(palette, c.pos())));
            }
        }
    },

    /** 底面中心、半径、高度的圆柱。 */
    CYLINDER("cylinder", List.of(Params.BLOCK, Params.X, Params.Y, Params.Z, Params.RADIUS, Params.HEIGHT,
            Params.HOLLOW)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            shape(args, canvas, BuildShapes.shapeCells("cylinder", Boolean.TRUE.equals(args.get(Params.HOLLOW)),
                    args.get(Params.X), args.get(Params.Y), args.get(Params.Z), null, null, null,
                    args.get(Params.RADIUS), args.get(Params.HEIGHT)));
        }
    },

    /** 球心、半径的球。 */
    SPHERE("sphere", List.of(Params.BLOCK, Params.X, Params.Y, Params.Z, Params.RADIUS, Params.HOLLOW)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            shape(args, canvas, BuildShapes.shapeCells("sphere", Boolean.TRUE.equals(args.get(Params.HOLLOW)),
                    args.get(Params.X), args.get(Params.Y), args.get(Params.Z), null, null, null,
                    args.get(Params.RADIUS), null));
        }
    },

    /** 抄一片区域画到别处,可以转、可以镜像。 */
    COPY("copy", List.of(Params.X1, Params.Y1, Params.Z1, Params.X2, Params.Y2, Params.Z2,
            Params.X, Params.Y, Params.Z, Params.ROTATION, Params.MIRROR, Params.INCLUDE_AIR)) {
        @Override
        void paint(CommandArgs args, Canvas canvas) {
            Mirror mirror = switch (args.get(Params.MIRROR) == null ? "none" : args.get(Params.MIRROR)) {
                case "left_right" -> Mirror.LEFT_RIGHT;
                case "front_back" -> Mirror.FRONT_BACK;
                default -> Mirror.NONE;
            };
            BuildCopy.Result copied = BuildCopy.copy(canvas,
                    new BlockPos(args.get(Params.X1), args.get(Params.Y1), args.get(Params.Z1)),
                    new BlockPos(args.get(Params.X2), args.get(Params.Y2), args.get(Params.Z2)),
                    new BlockPos(args.get(Params.X), args.get(Params.Y), args.get(Params.Z)),
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
        BuildPalette palette = BuildPalette.parse(args.get(Params.BLOCK));
        for (BlockPos pos : cells) {
            canvas.put(masked(args, cell(palette, pos)));
        }
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

    /** 原语的参数:每一个都是命令行上的写法、帮助里的一行、设计文件里一步的那一截。 */
    public static final class Params {

        private Params() {}

        public static final Param<String> BLOCK = Param.required("block", ArgType.string(),
                        "The block, written exactly as /setblock takes it, block state included.")
                .values("an id such as stone_bricks or oak_stairs[facing=north,half=top], or a weighted mix such as "
                        + "\"stone_bricks*8, mossy_stone_bricks\" (quoted, it has spaces); air clears the cell");
        public static final Param<Integer> X = Param.required("x", ArgType.integer(), "X of the cell.");
        public static final Param<Integer> Y = Param.required("y", ArgType.integer(), "Y of the cell.");
        public static final Param<Integer> Z = Param.required("z", ArgType.integer(), "Z of the cell.");
        public static final Param<Integer> X1 = Param.required("x1", ArgType.integer(), "X of the first corner.");
        public static final Param<Integer> Y1 = Param.required("y1", ArgType.integer(), "Y of the first corner.");
        public static final Param<Integer> Z1 = Param.required("z1", ArgType.integer(), "Z of the first corner.");
        public static final Param<Integer> X2 = Param.required("x2", ArgType.integer(), "X of the second corner.");
        public static final Param<Integer> Y2 = Param.required("y2", ArgType.integer(), "Y of the second corner.");
        public static final Param<Integer> Z2 = Param.required("z2", ArgType.integer(), "Z of the second corner.");
        public static final Param<Integer> RADIUS = Param.required("radius", ArgType.integer(1, 64),
                "Radius in blocks.");
        public static final Param<Integer> HEIGHT = Param.required("height", ArgType.integer(1, 256),
                "Height in blocks, upward from y.");
        public static final Param<Boolean> HOLLOW = Param.optional("hollow", ArgType.bool(),
                        "Keep only the outer shell.")
                .whenOmitted("fill it solid");
        public static final Param<List<String>> ROWS = Param.required("rows", ArgType.list(ArgType.string()),
                        "The grid, one row per value: the first row sits at z and each row runs +x from x, so it "
                                + "reads like a map with north at the top. ' ' and '.' leave a cell alone.")
                .values("rows like ##### or #...#; quote a row that has spaces in it");
        public static final Param<List<String>> LEGEND = Param.optional("legend", ArgType.list(ArgType.string()),
                        "Which block each character of the grid is.")
                .values("entries like #=stone_bricks or <=oak_stairs[facing=south]; quote an entry whose block is "
                        + "a mix with spaces")
                .whenOmitted("use --block for every character");
        public static final Param<String> FILL = Param.optional("block", ArgType.string(),
                        "The block for every grid character the legend does not name, written as /setblock takes it.")
                .whenOmitted("require every character to be in the legend");
        public static final Param<Integer> UP_TO = Param.optional("up_to", ArgType.integer(),
                        "Repeat the same grid on every level from y up to this one: a four-high wall ring is one layer.")
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
         * 写不写它只决定这一行是当场画到世界里还是记进设计。
         */
        public static final Param<String> INTO = Param.optional("into", ArgType.word(),
                        "Add this step to the end of a design instead of building it now; the coordinates are then "
                                + "relative to the design's origin (0,0,0).")
                .values("a design name, as build designs lists it")
                .whenOmitted("build it now, at these world coordinates");
    }
}
