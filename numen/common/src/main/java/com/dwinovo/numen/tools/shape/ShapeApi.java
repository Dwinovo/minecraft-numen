package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.build.Design;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.List;
import java.util.Optional;

/**
 * {@code numen.shape}:形状与选区、Cells 的几何在 Lua 模块里(模型读得到源码,方法挂在 Cells 与 Region 上),这里是 Lua 做不了的几件,各一处:
 * 图案文字读成方块({@link BlockPatterns})、方块状态跟着变换转({@link BlockTurns},原版的 rotate 与 mirror 在 Java 里)、一串格的上限、
 * 维度的高度范围。四件都不碰世界里的方块;落进世界只有 {@code numen.build.place} 一条路。
 */
public final class ShapeApi {

    private ShapeApi() {}

    public static void install(NumenApi numen) {
        numen.api("shape", "Positions, selections and blocks to draw as Cells, modelled on WorldEdit (cuboid, cyl, sphere, "
                + "line, set, walls, stack, rotate, paste …). It only works out cells; numen.build.place puts them in "
                + "the world.", ShapeApi.class);
    }

    /** 图案里的一组。 */
    @Doc("One weighted part of a pattern.")
    public record PatternChoice(@Doc("Relative weight: this part is picked with probability weight / the sum of all weights.")
                                double weight,
                                @Doc("The blocks of this part, each written as /setblock takes it; one of them is picked, all "
                                        + "equally likely.") List<String> blocks) {}

    /** 要读的图案。 */
    public record Pattern(@Doc("A pattern: stone, oak_stairs[facing=north], 50%stone,50%cobblestone (relative weights, a "
            + "part without % weighs 1), ##logs (a block of that tag), ##*logs (any state of any block of the tag), "
            + "*oak_stairs[facing=north] (any value of the properties left out).") String text) {}

    @Fn("Read a pattern's text into its weighted parts of blocks, checking every block against the game.")
    @Example("numen.shape.pattern(\"50%stone,50%cobblestone\")")
    @Example("numen.shape.pattern(\"##*logs\")")
    @Note("Instant and read-only. The shape functions take a pattern's text directly (numen.shape.cuboid(a, b):set(\"stone\")); "
            + "this is for reading what a text stands for, or reading it once and passing the result to several of them.")
    @Note("Each cell draws its own block, independently and without a seed: first a part by weight, then a block of "
            + "the part. A cell's block is fixed once the Cells exist, so numen.build.diff and numen.build.place get the same one.")
    @Note("A text that does not read fails with bad_argument naming the part that did not, in the game's own words.")
    @SeeAlso({"numen.shape.cuboid", "numen.build.place"})
    public static List<PatternChoice> pattern(ServerCall call, Pattern args) {
        try {
            return BlockPatterns.parse(args.text());
        } catch (IllegalArgumentException bad) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "the pattern \"" + args.text() + "\" does not read: " + bad.getMessage(),
                    Call.of("numen.shape.pattern", "50%stone,50%cobblestone"));
        }
    }

    /** 要转的方块与变换。 */
    public record TransformBlocks(@Doc("Blocks written as /setblock takes them, with NBT if they have it.") List<String> blocks,
                                  @Doc("The linear transform as three rows of three numbers, applied to a column (x, y, z): "
                                          + "{{0, 0, -1}, {0, 1, 0}, {1, 0, 0}} turns east to south, a quarter turn clockwise "
                                          + "seen from above.") List<List<Double>> matrix) {}

    @Fn("Turn blocks' states along with a linear transform of space: the same blocks, facing, axis and shape as they come "
            + "out of that turn or flip.")
    @Example("numen.shape.transform_blocks({\"oak_stairs[facing=east,half=bottom]\", \"stone\"}, {{0, 0, -1}, {0, 1, 0}, {1, 0, 0}})")
    @Note("Instant and read-only; it answers one block per block given, in order, written as /setblock takes it (what the "
            + "turn leaves as it was comes back as you wrote it). Quarter turns about the vertical axis and mirrors across "
            + "north-south or east-west are the game's own rotate and mirror, so every block (modded ones too) turns "
            + "as vanilla turns it. Flips upside down, turns about the x or z axis and turns by other angles follow "
            + "WorldEdit: directions go to the nearest direction, half and slab type swap top and bottom, rails and "
            + "signs follow.")
    @Note("Cells:rotate and Cells:flip call it for you and move the cells too; use it directly only for blocks you "
            + "handle yourself.")
    @SeeAlso({"numen.shape.pattern"})
    public static List<String> transformBlocks(ServerCall call, TransformBlocks args) {
        double[][] m = new double[3][3];
        if (args.matrix().size() != 3 || args.matrix().stream().anyMatch(row -> row.size() != 3)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "argument 'matrix' is three rows of three numbers; got "
                    + LuaCodecs.literal(args.matrix()),
                    Call.of("numen.shape.transform_blocks", List.of("stone"), List.of(List.of(1, 0, 0), List.of(0, 1, 0),
                            List.of(0, 0, 1))));
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                m[row][col] = args.matrix().get(row).get(col);
            }
        }
        try {
            return BlockTurns.turn(args.blocks(), m);
        } catch (IllegalArgumentException bad) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a block does not read: " + bad.getMessage(),
                    Call.help("numen.shape.transform_blocks"));
        }
    }

    @Fn("The most cells one Cells holds: what a shape function can return and numen.build.place can take at once.")
    @Example("print(numen.shape.max_cells())")
    @Note("Instant. A shape bigger than that fails with bad_argument; draw it in pieces (a smaller radius, or parts you "
            + "put together with Cells:union) and place each piece. Shapes of many thousands of cells also take most of "
            + "what a program may compute between two calls, so two or three pieces, each placed on its own, run lighter "
            + "than one big one.")
    public static int maxCells(ServerCall call) {
        return LuaCodecs.MAX_CELLS;
    }

    /** 维度的高度范围。 */
    public record Heights(@Doc("The lowest y a block can be at.") int min,
                          @Doc("The highest y a block can be at.") int max) {}

    @Fn("The lowest and highest y a block can be at in the dimension you are in.")
    @Example("local h = numen.shape.y_range()\nprint(h.min, h.max)")
    @Note("Instant and read-only. Region:expand_vert reaches exactly this far.")
    public static Heights yRange(ServerCall call) {
        var level = call.her().serverLevel();
        return new Heights(level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }

    // ---- 读世界:读已加载的方块,交回"要写什么"的一串格,不写世界 ----

    private static LoadedBlocks world(ServerCall call) {
        return new LoadedBlocks(call.her().serverLevel(), call.her().blockPosition());
    }

    private static ShapeMasks.Mask mask(String text) {
        try {
            return ShapeMasks.parse(text);
        } catch (IllegalArgumentException bad) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "the mask \"" + text + "\" does not read: " + bad.getMessage(),
                    Call.of("numen.shape.matching", List.of(new BlockPos(0, 64, 0)), "stone,dirt"));
        }
    }

    private static Design blocks(List<BlockAt> blocks) {
        return new Design(blocks.stream().map(b -> new Design.Cell(b.pos(), b.name())).toList());
    }

    private static Design positions(List<BlockPos> cells) {
        return new Design(cells.stream().map(p -> new Design.Cell(p, null)).toList());
    }

    private static final String MASKS = "A mask: stone,dirt (one of them; a property left out matches any value, "
            + "oak_stairs[facing=north]), ##logs (a block of that tag), ^[facing=north] (blocks with those property "
            + "values; ^=[…] also needs the properties), #existing (not air), #air, #solid (stops movement), #fullcube, "
            + "#surface (not air, with air beside it), !m (not m), >m (m is under it), <m (m is over it), ~m (m is "
            + "beside it), %25 (a quarter of the cells, by chance); words separated by spaces must all hold.";

    private static final String READS = "Only loaded chunks are read: if any cell it needs is in an unloaded chunk the "
            + "whole call fails and says which area; nothing is read halfway. Reading does not ask the owner and changes "
            + "nothing.";

    /** 要读的格,与可选的蒙版。 */
    public record Read(@Doc("The cells to read: Cells or any list of Pos.") List<BlockPos> cells,
                       @Doc(MASKS) @Omitted("every block as it is") @Positional Optional<String> mask) {}

    @Fn("The block each of some cells holds in the world now, written as /setblock takes it; with a mask, the cells that "
            + "do not pass it read as air.")
    @Example("numen.shape.read({{x = 100, y = 64, z = -20}, {x = 101, y = 64, z = -20}})")
    @Example("numen.shape.read({{x = 100, y = 64, z = -20}}, \"!air\")")
    @Note("Instant and read-only. Every cell comes back, air included, as a Block (name and pos), ready for "
            + "numen.build.place elsewhere (numen.shape.copy does this for a selection). A block's items and other data "
            + "are not read, only its state. " + READS)
    @SeeAlso({"numen.shape.matching", "numen.build.place"})
    public static Design read(ServerCall call, Read args) {
        String text = args.mask().orElse(null);
        return blocks(TerrainReads.read(world(call), args.cells(), text == null ? null : mask(text),
                text == null ? 0 : ShapeMasks.reach(text)));
    }

    /** 要筛的格与蒙版。 */
    public record Matching(@Doc("The cells to test: Cells or any list of Pos.") List<BlockPos> cells,
                           @Doc(MASKS) String mask) {}

    @Fn("The cells whose block in the world passes a mask.")
    @Example("numen.shape.matching({{x = 100, y = 64, z = -20}, {x = 101, y = 64, z = -20}}, \"stone,dirt\")")
    @Example("numen.shape.matching({{x = 100, y = 64, z = -20}}, \"#surface\")")
    @Note("Instant and read-only; the cells come back as Cells of Pos in the order given. " + READS)
    @SeeAlso({"numen.shape.read"})
    public static Design matching(ServerCall call, Matching args) {
        return positions(TerrainReads.matching(world(call), args.cells(), mask(args.mask()), ShapeMasks.reach(args.mask())));
    }

    /** 要读空的格与厚度。 */
    public record Interior(@Doc("The cells of the shape to hollow out: Cells or any list of Pos.") List<BlockPos> cells,
                           @Doc("How thick a shell to leave, measured in blocks the way a walker steps (not diagonally).")
                           @Omitted("0: the single layer that touches the outside") @Positional Optional<Integer> thickness) {}

    @Fn("The cells inside a shape that WorldEdit's //hollow would empty: the ones that no open air reaches within thickness.")
    @Example("numen.shape.interior({{x = 100, y = 64, z = -20}, {x = 101, y = 64, z = -20}})")
    @Example("numen.shape.interior({{x = 100, y = 64, z = -20}}, 2)")
    @Note("Instant and read-only. Open air is found by flowing in from the shape's box through every block that does not "
            + "stop movement; a cell is left when that air touches it, or touches a cell the air touched within thickness "
            + "steps. A shape that is solid on every side of its box has no open air, so all of it comes back. Region:hollow "
            + "writes this with a pattern. " + READS)
    @SeeAlso({"numen.build.place"})
    public static Design interior(ServerCall call, Interior args) {
        int thickness = args.thickness().orElse(0);
        if (thickness < 0) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "thickness is at least 0; got " + thickness,
                    Call.of("numen.shape.interior", List.of(new BlockPos(0, 64, 0)), 1));
        }
        return positions(TerrainReads.hollow(world(call), args.cells(), thickness));
    }

    /** 列(x、z)与高度范围。 */
    public record Columns(@Doc("The columns to look down: Cells or any list of Pos (only x and z count).") List<BlockPos> columns,
                          @Doc("The lowest y to look at.") int minY, @Doc("The highest y to look at.") int maxY) {}

    @Fn("For each column, the cell on top of the highest block that is not air between two heights (WorldEdit's //overlay).")
    @Example("numen.shape.tops({{x = 100, y = 0, z = -20}, {x = 101, y = 0, z = -20}}, 60, 80)")
    @Note("Instant and read-only. A column whose cell just above maxY is not air is passed over (it is underground); "
            + "a column with only air has none. The cells come back as Cells of Pos; Region:overlay writes a pattern on "
            + "them. " + READS)
    @SeeAlso({"numen.shape.naturalized"})
    public static Design tops(ServerCall call, Columns args) {
        return positions(TerrainReads.overlay(world(call), args.columns(), args.minY(), args.maxY()));
    }

    @Fn("The blocks WorldEdit's //naturalize would write in some columns: grass on the top layer, three of dirt, stone below.")
    @Example("numen.shape.naturalized({{x = 100, y = 0, z = -20}}, 40, 80)")
    @Note("Instant and read-only. Each column's grass, dirt and stone are counted from the highest of them down to minY; "
            + "only the cells whose block would change come back. " + READS)
    @SeeAlso({"numen.shape.tops"})
    public static Design naturalized(ServerCall call, Columns args) {
        return blocks(TerrainReads.naturalize(world(call), args.columns(), args.minY(), args.maxY()));
    }

    /** 要抹平的方框。 */
    public record Smoothed(@Doc("One corner of the box to smooth.") BlockPos from, @Doc("The opposite corner.") BlockPos to,
                           @Doc("How many passes of the smoothing.") @Omitted("1") Optional<Integer> iterations,
                           @Doc(MASKS + " The blocks that count as ground.") @Omitted("blocks that stop movement")
                           Optional<String> mask) {}

    @Fn("The blocks WorldEdit's //smooth would write in a box: the ground's height blurred with a Gaussian kernel, "
            + "columns raised or lowered to match.")
    @Example("numen.shape.smoothed({x = 100, y = 60, z = -20}, {x = 110, y = 80, z = -10})")
    @Example("numen.shape.smoothed({x = 100, y = 60, z = -20}, {x = 110, y = 80, z = -10}, {iterations = 2, mask = \"grass_block,dirt,stone\"})")
    @Note("Instant and read-only. Each column's height is its highest ground block between the corners' heights (water "
            + "and lava columns are not raised). Only the cells that change come back. " + READS)
    public static Design smoothed(ServerCall call, Smoothed args) {
        BlockPos lo = new BlockPos(Math.min(args.from().getX(), args.to().getX()), Math.min(args.from().getY(), args.to().getY()),
                Math.min(args.from().getZ(), args.to().getZ()));
        BlockPos hi = new BlockPos(Math.max(args.from().getX(), args.to().getX()), Math.max(args.from().getY(), args.to().getY()),
                Math.max(args.from().getZ(), args.to().getZ()));
        String text = args.mask().orElse(null);
        return blocks(TerrainReads.smooth(world(call), lo, hi, args.iterations().orElse(1), text == null ? null : mask(text),
                text == null ? 0 : ShapeMasks.reach(text)));
    }

    /** 照原来的方块改的图案。 */
    public record Restated(@Doc("The cells to change: Cells or any list of Pos.") List<BlockPos> cells,
                           @Doc("A ^ pattern: ^[facing=east] (those properties on the block that is there), ^oak_planks (that "
                                   + "block, keeping the properties it shares), ^oak_stairs[half=top] (both).") String pattern) {}

    @Fn("The blocks some cells would get from a ^ pattern: what is there now, with some properties changed or the block "
            + "swapped.")
    @Example("numen.shape.restated({{x = 100, y = 64, z = -20}}, \"^[waterlogged=false]\")")
    @Note("Instant and read-only. A property a block does not have is passed over; a value it does not take fails. "
            + "Region:set takes a ^ pattern and calls this. " + READS)
    @SeeAlso({"numen.shape.pattern"})
    public static Design restated(ServerCall call, Restated args) {
        try {
            return blocks(TerrainReads.restate(world(call), args.cells(), args.pattern()));
        } catch (IllegalArgumentException bad) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "the pattern \"" + args.pattern() + "\" does not read: " + bad.getMessage(),
                    Call.of("numen.shape.restated", List.of(new BlockPos(0, 64, 0)), "^[waterlogged=false]"));
        }
    }

    // ---- 以世界为准的维护:在一个位置周围读,交回要写的格 ----

    private static int height(LoadedBlocks w, Optional<Integer> height) {
        return Math.max(1, height.orElse(w.maxY() - w.minY()));
    }

    /** 找洞的位置与范围。 */
    public record Holes(@Doc("The cell to fill from.") BlockPos at, @Doc("How far round it, in blocks (at least 1).") double radius,
                        @Doc("How many layers down.") @Omitted("1; no limit with everywhere") @Positional Optional<Integer> depth,
                        @Doc("Spread in all six directions, not only sideways at the start's level and down.")
                        Optional<Boolean> everywhere) {}

    @Fn("The air a pool-filling would fill from a cell: the air in a ball round it, at its height or lower, no deeper than "
            + "depth, that the cell reaches by going sideways at its own level and then down (WorldEdit's //fill).")
    @Example("numen.shape.holes({x = 100, y = 64, z = -20}, 6)")
    @Example("numen.shape.holes({x = 100, y = 64, z = -20}, 6, 3, {everywhere = true})")
    @Note("Instant and read-only; Cells of Pos, the starting cell first. With everywhere the air spreads in all six "
            + "directions (//fillr) and depth has no limit unless given. numen.shape.fill and fillr write a pattern on "
            + "them. " + READS)
    public static Design holes(ServerCall call, Holes args) {
        LoadedBlocks w = world(call);
        boolean everywhere = args.everywhere().orElse(false);
        double radius = Math.max(1, args.radius());
        int depth = args.depth().map(d -> Math.max(1, d)).orElse(everywhere ? Integer.MAX_VALUE : 1);
        return positions(TerrainReads.fill(w, args.at(), radius, depth, everywhere));
    }

    /** 排水的位置与范围。 */
    public record Drain(@Doc("The centre.") BlockPos at, @Doc("How far round it, in blocks.") double radius,
                        @Doc("Also take the water out of blocks that hold it (stairs, slabs, fences).")
                        Optional<Boolean> waterlogged) {}

    @Fn("The blocks that drain a pool: the water and lava connected to the cells round a point, within a ball, as air "
            + "(WorldEdit's //drain).")
    @Example("numen.shape.drain({x = 100, y = 64, z = -20}, 5)")
    @Note("Instant and read-only; Cells of Blocks (air, or with waterlogged the same block without water). It starts from "
            + "the 3 x 3 x 3 cells round the point and follows liquid in every direction. " + READS)
    public static Design drain(ServerCall call, Drain args) {
        return blocks(TerrainReads.drain(world(call), args.at(), Math.max(0, args.radius()), args.waterlogged().orElse(false)));
    }

    /** 修流体的位置与范围。 */
    public record Fix(@Doc("The centre.") BlockPos at, @Doc("How far round it, in blocks.") double radius) {}

    @Fn("The cells that make flowing water stationary round a point: source water where the water is and where it would "
            + "flow sideways and down into air (WorldEdit's //fixwater).")
    @Example("numen.shape.fixwater({x = 100, y = 64, z = -20}, 5)")
    @Note("Instant and read-only; Cells of Blocks. It goes down and sideways from the water next to the point, within a "
            + "ball and no higher than the point. " + READS)
    public static Design fixwater(ServerCall call, Fix args) {
        return blocks(TerrainReads.fixLiquid(world(call), args.at(), Math.max(0, args.radius()), Blocks.WATER.defaultBlockState()));
    }

    @Fn("The cells that make flowing lava stationary round a point (WorldEdit's //fixlava); the same as fixwater for lava.")
    @Example("numen.shape.fixlava({x = 100, y = 64, z = -20}, 5)")
    @Note("Instant and read-only; Cells of Blocks. " + READS)
    public static Design fixlava(ServerCall call, Fix args) {
        return blocks(TerrainReads.fixLiquid(world(call), args.at(), Math.max(0, args.radius()), Blocks.LAVA.defaultBlockState()));
    }

    /** 圆柱里的位置与范围。 */
    public record Weather(@Doc("The centre.") BlockPos at,
                          @Doc("How far round it, in blocks.") @Omitted("10") @Positional Optional<Double> radius,
                          @Doc("How many layers it reaches up and down from the centre.") @Omitted("the whole height of the world")
                          Optional<Integer> height,
                          @Doc("Stack snow layers onto snow already there (snow only).") Optional<Boolean> stack,
                          @Doc("Also turn coarse dirt to grass (green only).") Optional<Boolean> coarse) {}

    @Fn("The cells that put snow on the ground in a cylinder: still water freezes, other ground gets a layer of snow "
            + "where snow can stand (WorldEdit's //snow).")
    @Example("numen.shape.snow({x = 100, y = 64, z = -20}, 8)")
    @Example("numen.shape.snow({x = 100, y = 64, z = -20}, 8, {stack = true})")
    @Note("Instant and read-only; Cells of Blocks (ice, snow, snow with more layers, a snow block). Each column takes the "
            + "highest block that can carry snow. " + READS)
    public static Design snow(ServerCall call, Weather args) {
        LoadedBlocks w = world(call);
        return blocks(TerrainReads.snow(w, args.at(), Math.max(1, args.radius().orElse(10.0)), height(w, args.height()),
                args.stack().orElse(false)));
    }

    @Fn("The cells that thaw a cylinder: the top ice turns to water, the top snow layer to air (WorldEdit's //thaw).")
    @Example("numen.shape.thaw({x = 100, y = 64, z = -20}, 8)")
    @Note("Instant and read-only; Cells of Blocks. " + READS)
    public static Design thaw(ServerCall call, Weather args) {
        LoadedBlocks w = world(call);
        return blocks(TerrainReads.thaw(w, args.at(), Math.max(1, args.radius().orElse(10.0)), height(w, args.height())));
    }

    @Fn("The cells that turn dirt to grass in a cylinder: the first dirt in each column from the top, unless water, "
            + "lava or something solid comes first (WorldEdit's //green).")
    @Example("numen.shape.green({x = 100, y = 64, z = -20}, 8)")
    @Note("Instant and read-only; Cells of grass blocks. With coarse, coarse dirt turns too. " + READS)
    public static Design green(ServerCall call, Weather args) {
        LoadedBlocks w = world(call);
        return blocks(TerrainReads.green(w, args.at(), Math.max(1, args.radius().orElse(10.0)), height(w, args.height()),
                args.coarse().orElse(false)));
    }

    /** 重力的位置与范围。 */
    public record Gravity(@Doc("The centre.") BlockPos at,
                          @Doc("Half the side of the square of columns.") @Omitted("5") @Positional Optional<Double> radius,
                          @Doc("How many layers it reaches up and down from the centre.") @Omitted("the whole height of the world")
                          Optional<Integer> height) {}

    @Fn("The cells that let blocks fall in a square of columns: each block with air under it moves down to the lowest air "
            + "(WorldEdit's gravity brush).")
    @Example("numen.shape.gravity({x = 100, y = 64, z = -20}, 4)")
    @Note("Instant and read-only; Cells of Blocks: the blocks at their new places, and air where they were. " + READS)
    public static Design gravity(ServerCall call, Gravity args) {
        LoadedBlocks w = world(call);
        double radius = Math.max(0, args.radius().orElse(5.0));
        return blocks(TerrainReads.gravity(w, args.at(), radius, height(w, args.height())));
    }

    /** 高度图笔刷。 */
    public record Heightmap(@Doc("The centre.") BlockPos at, @Doc("How far round it, in blocks.") double radius,
                            @Doc("Rows of numbers from 0 to 1 (the brightness of the image WorldEdit takes), the first row the "
                                    + "south edge; any size, it is sampled to the brush's square.") List<List<Double>> grid,
                            @Doc("How many blocks a 1 raises.") @Omitted("5") @Positional Optional<Double> intensity,
                            @Doc("Dig the ground down instead of raising it.") Optional<Boolean> erase,
                            @Doc("Do not change blocks above the centre's layer.") Optional<Boolean> flatten,
                            @Doc("Vary the heights a little, at random.") Optional<Boolean> randomize) {}

    @Fn("The blocks that raise (or dig) the ground under a brush by a heightmap: each column's top block repeated up by "
            + "the grid's height there (WorldEdit's heightmap brush, with a grid of numbers in place of an image).")
    @Example("numen.shape.heightmap({x = 100, y = 64, z = -20}, 2, {{0, 0.5, 0}, {0.5, 1, 0.5}, {0, 0.5, 0}})")
    @Note("Instant and read-only; Cells of Blocks. The ground is the highest block that stops movement in each column. " + READS)
    public static Design heightmap(ServerCall call, Heightmap args) {
        if (args.grid().isEmpty() || args.grid().stream().anyMatch(List::isEmpty)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "grid is rows of numbers from 0 to 1; got an empty one",
                    Call.of("numen.shape.heightmap", new BlockPos(0, 64, 0), 2, List.of(List.of(0, 0.5), List.of(0.5, 1))));
        }
        double[][] grid = args.grid().stream().map(row -> row.stream().mapToDouble(Double::doubleValue).toArray())
                .toArray(double[][]::new);
        return blocks(TerrainReads.heightmap(world(call), args.at(), Math.max(0, args.radius()), grid,
                args.intensity().orElse(5.0), args.erase().orElse(false), args.flatten().orElse(false),
                args.randomize().orElse(false)));
    }

    // ---- 雪层平滑、笔刷形状、统计 ----

    /** 雪层平滑的范围。 */
    public record SnowSmoothed(@Doc("One corner of the box to smooth.") BlockPos from, @Doc("The opposite corner.") BlockPos to,
                               @Doc("How many passes of the smoothing.") @Omitted("1") Optional<Integer> iterations,
                               @Doc("How many snow blocks to put under the snow layers.") @Omitted("1") Optional<Integer> layers,
                               @Doc(MASKS + " The blocks that count as ground.") @Omitted("blocks that stop movement")
                               Optional<String> mask) {}

    @Fn("The blocks WorldEdit's //snowsmooth would write in a box: the ground's height, snow layers counted (an eighth of "
            + "a block each), blurred like numen.shape.smoothed and laid as snow.")
    @Example("numen.shape.snowsmoothed({x = 100, y = 60, z = -20}, {x = 110, y = 80, z = -10})")
    @Note("Instant and read-only; Cells of Blocks (snow layers, snow blocks under them, air where the ground comes down). "
            + "Region:snowsmooth does it for a selection. Only the cells that change come back. " + READS)
    @SeeAlso({"numen.shape.smoothed"})
    public static Design snowsmoothed(ServerCall call, SnowSmoothed args) {
        BlockPos lo = new BlockPos(Math.min(args.from().getX(), args.to().getX()), Math.min(args.from().getY(), args.to().getY()),
                Math.min(args.from().getZ(), args.to().getZ()));
        BlockPos hi = new BlockPos(Math.max(args.from().getX(), args.to().getX()), Math.max(args.from().getY(), args.to().getY()),
                Math.max(args.from().getZ(), args.to().getZ()));
        String text = args.mask().orElse(null);
        return blocks(TerrainReads.snowSmooth(world(call), lo, hi, args.iterations().orElse(1), text == null ? null : mask(text),
                args.layers().orElse(1), text == null ? 0 : ShapeMasks.reach(text)));
    }

    /** 形变笔刷的范围与力度。 */
    public record Morph(@Doc("The centre.") BlockPos at,
                        @Doc("The radius of the ball it works in.") @Omitted("5") @Positional Optional<Double> radius,
                        @Doc("A solid block with at least this many faces open to air or liquid is eroded.") @Omitted("3")
                        Optional<Integer> erode_faces,
                        @Doc("How many erode passes.") @Omitted("1") Optional<Integer> erode_passes,
                        @Doc("An empty cell with at least this many solid faces beside it is filled.") @Omitted("3")
                        Optional<Integer> dilate_faces,
                        @Doc("How many dilate passes.") @Omitted("1") Optional<Integer> dilate_passes) {}

    @Fn("The blocks WorldEdit's morph brush would write in a ball: erode (open-air faces wear solid blocks away), then dilate "
            + "(solid faces fill the empty cells beside them).")
    @Example("numen.shape.morph({x = 100, y = 64, z = -20}, 5)")
    @Example("numen.shape.morph({x = 100, y = 64, z = -20}, 5, {erode_faces = 2, dilate_faces = 5})")
    @Note("Instant and read-only; Cells of Blocks, only the cells that change. Each pass reads the one before. "
            + "numen.shape.erode and numen.shape.dilate are the two presets. " + READS)
    @SeeAlso({"numen.shape.erode", "numen.shape.dilate"})
    public static Design morph(ServerCall call, Morph args) {
        return blocks(TerrainReads.morph(world(call), args.at(), Math.max(0, args.radius().orElse(5.0)),
                args.erode_faces().orElse(3), args.erode_passes().orElse(1), args.dilate_faces().orElse(3),
                args.dilate_passes().orElse(1)));
    }

    /** 数格子。 */
    public record Count(@Doc("The cells to count in: Cells or any list of Pos.") List<BlockPos> cells,
                        @Doc(MASKS) String mask) {}

    @Fn("How many of some cells hold a block that passes a mask (WorldEdit's //count).")
    @Example("numen.shape.count({{x = 100, y = 64, z = -20}, {x = 101, y = 64, z = -20}}, \"stone,dirt\")")
    @Note("Instant and read-only; a number. Region:count does it for a selection. " + READS)
    @SeeAlso({"numen.shape.distr", "numen.shape.matching"})
    public static int count(ServerCall call, Count args) {
        return TerrainReads.count(world(call), args.cells(), mask(args.mask()), ShapeMasks.reach(args.mask()));
    }

    /** 方块分布的范围与选项。 */
    public record Distr(@Doc("The cells to count in: Cells or any list of Pos.") List<BlockPos> cells,
                        @Doc(MASKS + " Only blocks that pass are counted.") @Omitted("every block") Optional<String> mask,
                        @Doc("Count each block state on its own (oak_stairs[facing=north] and oak_stairs[facing=east] apart).")
                        Optional<Boolean> separate) {}

    /** 一种方块与数量。 */
    public record Counted(@Doc("The block, written as /setblock takes it.") String name, @Doc("How many cells hold it.") int count) {}

    @Fn("How many cells of each kind of block some cells hold, the most common first (WorldEdit's //distr).")
    @Example("numen.shape.distr({{x = 100, y = 64, z = -20}, {x = 101, y = 64, z = -20}})")
    @Example("numen.shape.distr({{x = 100, y = 64, z = -20}}, {separate = true})")
    @Note("Instant and read-only; a list of {name, count}. Air is counted like any block (pass the mask !air to leave it out). "
            + "Region:distr does it for a selection. " + READS)
    @SeeAlso({"numen.shape.count"})
    public static List<Counted> distr(ServerCall call, Distr args) {
        String text = args.mask().orElse(null);
        return TerrainReads.distribution(world(call), args.cells(), text == null ? null : mask(text),
                text == null ? 0 : ShapeMasks.reach(text), args.separate().orElse(false)).stream()
                .map(c -> new Counted(c.name(), c.count())).toList();
    }

    // ---- 原版的生成器:跑在记录用的世界上,交回要写什么 ----

    /** 生成器想要、Cells 装不下的东西。 */
    public record Lost(@Doc("\"entity\" (the generator added a creature or an item frame), or \"block_entity\" (the block "
            + "holds data: a chest's loot, a spawner's mob, a sign's text).") String kind,
                       @Doc("The entity's id, or the block written as /setblock takes it.") String name,
                       @Doc("Where.") BlockPos pos) {}

    /** 生成的结果。 */
    public record Grown(@Doc("What it writes, as Cells of Blocks; numen.build.place puts them in the world.") Design cells,
                        @Doc("What the generator wanted that Cells cannot hold and is left out: entities it added, data of the "
                                + "blocks it made. The blocks themselves are in cells.") List<Lost> lost) {}

    private static Grown grown(Generators.Grown made) {
        return new Grown(blocks(made.blocks()), made.lost().stream().map(l -> new Lost(l.kind(), l.name(), l.pos())).toList());
    }

    /** 地物或结构的位置与名字。 */
    public record Generated(@Doc("Where it grows: a tree's trunk cell, a structure's chunk.") BlockPos at,
                            @Doc("Its id in the game's registry, minecraft:oak (a mod's own ones work too).") String id) {}

    @Fn("What one of the game's own configured features (a tree, an ore vein, a lake, a patch of flowers…) would write if it "
            + "generated at a position (WorldEdit's //feature).")
    @Example("numen.shape.feature({x = 100, y = 64, z = -20}, \"minecraft:oak\")")
    @Note("Instant and read-only: the game's generator runs on a recorded level that reads the loaded world and writes "
            + "nothing to it. Returns {cells, lost}: cells is the blocks it would write (liquids are in them as the game "
            + "wrote them), lost is the entities it added and the data of block entities, which Cells cannot hold. It "
            + "fails if the feature's own placement rules find no place at that position. Every feature the game or a "
            + "mod registers works. " + READS)
    @SeeAlso({"numen.shape.structure", "numen.build.place"})
    public static Grown feature(ServerCall call, Generated args) {
        return grown(Generators.feature(world(call), args.at(), args.id()));
    }

    @Fn("What one of the game's own structures (a village, a desert pyramid, a shipwreck…) would write if it generated in "
            + "the chunk of a position (WorldEdit's //structure).")
    @Example("numen.shape.structure({x = 100, y = 64, z = -20}, \"minecraft:desert_pyramid\")")
    @Note("Instant and read-only: the game's generator runs on a recorded level that reads the loaded world and writes "
            + "nothing to it. Returns {cells, lost} like numen.shape.feature. The structure's height follows the game's "
            + "terrain generator, as in WorldEdit, and it can reach into the chunks around: all of them must be loaded. "
            + "Every structure the game or a mod registers works. " + READS)
    @SeeAlso({"numen.shape.feature", "numen.build.place"})
    public static Grown structure(ServerCall call, Generated args) {
        return grown(Generators.structure(world(call), args.at(), args.id()));
    }

    /** 树林或花草的范围与密度。 */
    public record Planted(@Doc("The columns to plant in: Cells or any list of Pos (only x and z count).") List<BlockPos> columns,
                          @Doc("The lowest y to look for ground at.") int minY, @Doc("The highest y to look for ground from.") int maxY,
                          @Doc("The tree: a placed feature of the game that grows a tree (minecraft:oak_checked, "
                                  + "minecraft:birch_checked, …, mods' too), worldedit:pine, or worldedit:random.")
                          @Omitted("minecraft:oak_checked") Optional<String> tree,
                          @Doc("The share of columns that get one, in percent from 0 to 100.") @Omitted("5") Optional<Double> density) {}

    private static double percent(Optional<Double> density, String function) {
        double value = density.orElse(5.0);
        if (value < 0 || value > 100) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "density is from 0 to 100; got " + value,
                    Call.of(function, List.of(new BlockPos(0, 64, 0)), 60, 80, 5));
        }
        return value / 100;
    }

    @Fn("What a forest would write over some columns: each column, at the share density says, gets a tree on its highest "
            + "non-air block (WorldEdit's //forest).")
    @Example("numen.shape.forest({{x = 100, y = 0, z = -20}, {x = 101, y = 0, z = -20}}, 60, 80, {tree = \"minecraft:birch_checked\", density = 50})")
    @Note("Instant and read-only; returns {cells, lost} like numen.shape.feature. A tree is tried up to 10 times in a column "
            + "(the game's trees often do not fit) and the column is left alone if none grows. Region:forest does it for "
            + "a selection, numen.shape.forestgen for a square round a position. " + READS)
    @SeeAlso({"numen.shape.feature", "numen.shape.flora"})
    public static Grown forest(ServerCall call, Planted args) {
        return grown(Generators.forest(world(call), args.columns(), args.minY(), args.maxY(),
                args.tree().orElse(Generators.DEFAULT_TREE), percent(args.density(), "numen.shape.forest")));
    }

    @Fn("What flora would write over some columns: grass and small flowers on grass blocks, dead bushes and cacti on sand, one "
            + "cell above the ground, at the share density says (WorldEdit's //flora).")
    @Example("numen.shape.flora({{x = 100, y = 0, z = -20}, {x = 101, y = 0, z = -20}}, 60, 80, {density = 50})")
    @Note("Instant and read-only; Cells of Blocks (only the cells that change). Region:flora does it for a selection. " + READS)
    @SeeAlso({"numen.shape.forest"})
    public static Design flora(ServerCall call, Planted args) {
        return blocks(Generators.flora(world(call), args.columns(), args.minY(), args.maxY(),
                percent(args.density(), "numen.shape.flora")));
    }

    /** 南瓜田的位置与大小。 */
    public record Pumpkins(@Doc("The centre.") BlockPos at,
                           @Doc("How far round it, in blocks.") @Omitted("10") @Positional Optional<Integer> radius) {}

    @Fn("What pumpkin patches would write round a position: here and there on grass, a ring of leaves with oak logs and "
            + "pumpkins (WorldEdit's //pumpkins).")
    @Example("numen.shape.pumpkins({x = 100, y = 64, z = -20}, 10)")
    @Note("Instant and read-only; Cells of Blocks. Ground is looked for from 10 layers above the centre down to 5 below. " + READS)
    public static Design pumpkins(ServerCall call, Pumpkins args) {
        return blocks(Generators.pumpkins(world(call), args.at(), Math.max(0, args.radius().orElse(10))));
    }
}
