package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;

import java.util.List;

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
            + "put together with Cells:union) and place each piece.")
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
}
