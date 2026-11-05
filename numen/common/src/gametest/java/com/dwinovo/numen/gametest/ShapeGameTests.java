package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.world.Difficulty;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * {@code numen.shape} 里读世界的那些,从她的入口跑,场景用代码搭:copy、replace、hollow,copy 转了再 paste 经 place 放下,维护里的 snow,
 * 和读不到没加载的区块时整个调用失败。判据是交回的格:读不写,世界在这些调用之后原样。
 */
public class ShapeGameTests {

    // 每个批次都自己定下世界(难度、时刻、天气、模拟距离、权限设置),不继承上一批留下的:批次的先后说不准
    @BeforeBatch(batch = BATCH)
    public static void prepareShapeBatch(ServerLevel level) {
        GameTestKit.settleWorld(level, Difficulty.PEACEFUL, GameTestKit.NOON);
    }

    private static final String BATCH = "numen_shape";

    private static JsonArray list(ToolRun run) {
        return run.data().get("returned").getAsJsonArray();
    }

    private static Set<String> positions(JsonArray cells) {
        Set<String> out = new HashSet<>();
        for (var c : cells) {
            JsonObject pos = c.getAsJsonObject().has("pos") ? c.getAsJsonObject().getAsJsonObject("pos") : c.getAsJsonObject();
            out.add(pos.get("x").getAsInt() + "," + pos.get("y").getAsInt() + "," + pos.get("z").getAsInt());
        }
        return out;
    }

    private static String key(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static void block(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 2);
    }

    /** replace:只有过蒙版的格,各带图案里的方块。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void replace_takes_the_cells_that_pass_the_mask(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_replacer", new BlockPos(2, 2, 2), false);
        BlockPos o = helper.absolutePos(new BlockPos(5, 2, 5));
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                block(level, o.offset(dx, 0, dz), (dx + dz) % 2 == 0 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState());
            }
        }
        ToolRun run = lua(her, "return numen.shape.cuboid(" + xyz(o) + ", " + xyz(o.offset(2, 0, 2)) + "):replace(\"dirt\", \"gravel\")");
        helper.assertTrue(run.ranToTheEnd(), "replace failed: " + run.receipt());
        Set<String> expected = new HashSet<>();
        for (int dx = 0; dx < 3; dx++) {
            for (int dz = 0; dz < 3; dz++) {
                if ((dx + dz) % 2 == 0) {
                    expected.add(key(o.offset(dx, 0, dz)));
                }
            }
        }
        helper.assertTrue(positions(list(run)).equals(expected), "only the five dirt cells: " + list(run));
        helper.assertTrue(list(run).asList().stream().allMatch(c -> c.getAsJsonObject().get("name").getAsString().equals("gravel")),
                "each takes the pattern's block: " + list(run));
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
    }

    /** hollow:一个实心的方块,选区带一圈空气,里面的 3 × 3 × 3 被掏成空气。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void hollow_empties_the_inside_of_a_solid_block(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_hollower", new BlockPos(2, 2, 2), false);
        BlockPos o = helper.absolutePos(new BlockPos(4, 3, 4));
        for (BlockPos p : BlockPos.betweenClosed(o, o.offset(4, 4, 4))) {
            block(level, p.immutable(), Blocks.OAK_PLANKS.defaultBlockState());
        }
        ToolRun run = lua(her, "return numen.shape.cuboid(" + xyz(o.offset(-1, -1, -1)) + ", " + xyz(o.offset(5, 5, 5)) + "):hollow()");
        helper.assertTrue(run.ranToTheEnd(), "hollow failed: " + run.receipt());
        Set<String> inside = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(o.offset(1, 1, 1), o.offset(3, 3, 3))) {
            inside.add(key(p));
        }
        helper.assertTrue(positions(list(run)).equals(inside), "the 3 x 3 x 3 inside a one-thick shell: " + list(run));
        helper.assertTrue(list(run).asList().stream().allMatch(c -> c.getAsJsonObject().get("name").getAsString().equals("air")),
                "emptied with air: " + list(run));
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
    }

    /**
     * copy → rotate → paste → place:一排朝向不同的方块抄下来、转 90°、对到另一处放下,每格的方块状态等于原物经原版 {@code rotate} 转出来的那一个,
     * 位置是原位置绕她站的那一格转过去的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void a_copied_row_rotated_and_pasted_is_built_as_the_original_turned(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_cloner", new BlockPos(2, 2, 2), true);
        BlockPos from = helper.absolutePos(new BlockPos(5, 2, 5));
        BlockState[] original = {
                Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING,
                        net.minecraft.core.Direction.NORTH).setValue(net.minecraft.world.level.block.StairBlock.HALF,
                        net.minecraft.world.level.block.state.properties.Half.TOP),
                Blocks.AIR.defaultBlockState(),
                Blocks.OAK_LOG.defaultBlockState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,
                        net.minecraft.core.Direction.Axis.X),
                Blocks.AIR.defaultBlockState(),
                Blocks.OAK_SIGN.defaultBlockState().setValue(net.minecraft.world.level.block.StandingSignBlock.ROTATION, 3)};
        for (int i = 0; i < original.length; i++) {
            if (!original[i].isAir()) {
                block(level, from.east(i), original[i]);
            }
        }
        BlockPos standing = her.blockPosition();
        BlockPos to = helper.absolutePos(new BlockPos(12, 2, 6));
        ToolRun build = lua(her, "numen.build.place(numen.shape.copy(numen.shape.cuboid(" + xyz(from) + ", " + xyz(from.east(4))
                + ")):rotate(90):paste(" + xyz(to) + "))");

        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.succeeded(), "build failed: " + build.outcome());
            for (int i = 0; i < original.length; i++) {
                if (original[i].isAir()) {
                    continue;
                }
                // the cell's offset from her turns clockwise seen from above: (dx, dz) becomes (-dz, dx)
                int dx = from.east(i).getX() - standing.getX();
                int dy = from.getY() - standing.getY();
                int dz = from.getZ() - standing.getZ();
                BlockPos at = to.offset(-dz, dy, dx);
                BlockState expected = original[i].rotate(Rotation.CLOCKWISE_90);
                helper.assertTrue(level.getBlockState(at) == expected, "cell " + i + " at " + at.toShortString() + " is "
                        + level.getBlockState(at) + ", the original turned is " + expected);
            }
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** snow:平台顶上每列一层雪,平台里不落。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void snow_lands_on_the_ground_of_each_column(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_snower", new BlockPos(2, 2, 2), false);
        BlockPos o = helper.absolutePos(new BlockPos(6, 2, 6));
        block(level, o, Blocks.STONE.defaultBlockState());
        ToolRun run = lua(her, "return numen.shape.snow(" + xyz(o.offset(0, 1, 0)) + ", 1, {height = 2})");
        helper.assertTrue(run.ranToTheEnd(), "snow failed: " + run.receipt());
        helper.assertTrue(positions(list(run)).contains(key(o.above())), "snow on the stone: " + list(run));
        helper.assertTrue(list(run).asList().stream().allMatch(c -> c.getAsJsonObject().get("name").getAsString().equals("minecraft:snow")),
                "a layer of snow each: " + list(run));
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
    }

    /** 读不到的区块:整个调用失败,说出是哪一片。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void reading_a_cell_in_an_unloaded_chunk_fails_the_whole_call_and_says_where(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_far_reader", new BlockPos(2, 2, 2), false);
        BlockPos near = helper.absolutePos(new BlockPos(5, 2, 5));
        ToolRun run = lua(her, "local ok, err = pcall(numen.shape.read, {" + xyz(near) + ", {x = 100000, y = 64, z = 100000}})\n"
                + "return {ok = ok, kind = err.kind, message = err.message}");
        helper.assertTrue(run.ranToTheEnd(), "the program itself failed: " + run.receipt());
        JsonObject got = run.data().getAsJsonObject("returned");
        helper.assertTrue(!got.get("ok").getAsBoolean() && got.get("kind").getAsString().equals("not_found")
                        && got.get("message").getAsString().contains("not loaded")
                        && got.get("message").getAsString().contains("x 100000 to 100015, z 100000 to 100015"),
                "the call fails and names the chunk: " + got);
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
    }
}
