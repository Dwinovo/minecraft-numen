package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
 * {@code numen.shape} 里读世界的那些,从她的入口跑,场景用代码搭:copy、replace、overlay、hollow,copy 转了再 paste 经 place 放下,几个有代表性的维护
 * (fill、drain、snow、naturalize、gravity),和读不到没加载的区块时整个调用失败。判据是交回的格:读不写,世界在这些调用之后原样。
 */
public class ShapeGameTests {

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

    /** copy:选区里每一格此刻的方块(空气也在),原点是她站的那一格;带状态的方块连状态一起写下来。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void copy_takes_every_cell_with_its_state_and_stands_at_her_cell(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_copier", new BlockPos(2, 2, 2), false);
        BlockPos from = helper.absolutePos(new BlockPos(5, 2, 5));
        block(level, from, Blocks.OAK_STAIRS.defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING,
                net.minecraft.core.Direction.EAST));
        block(level, from.east(), Blocks.OAK_PLANKS.defaultBlockState());
        ToolRun run = lua(her, "local c = numen.shape.copy(numen.shape.cuboid(" + xyz(from) + ", " + xyz(from.offset(2, 0, 0))
                + "))\nlocal o = c:origin()\nreturn {n = #c, first = c[1].name, second = c[2].name, third = c[3].name, "
                + "ox = o.x, oy = o.y, oz = o.z}");
        helper.assertTrue(run.ranToTheEnd(), "copy failed: " + run.receipt());
        JsonObject got = run.data().getAsJsonObject("returned");
        BlockPos standing = her.blockPosition();
        helper.assertTrue(got.get("n").getAsInt() == 3 && got.get("first").getAsString().equals("minecraft:oak_stairs[facing=east]")
                        && got.get("second").getAsString().equals("minecraft:oak_planks")
                        && got.get("third").getAsString().equals("minecraft:air"),
                "copy is the three cells as they are, the empty one as air: " + got);
        helper.assertTrue(got.get("ox").getAsInt() == standing.getX() && got.get("oy").getAsInt() == standing.getY()
                        && got.get("oz").getAsInt() == standing.getZ(), "the origin is the cell she stands in: " + got);
        helper.assertTrue(level.getBlockState(from.east()).is(Blocks.OAK_PLANKS), "reading changed the world");
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
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

    /** overlay:每一列最上面那块头上一格。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void overlay_is_the_cell_above_the_top_block_of_each_column(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_overlayer", new BlockPos(2, 2, 2), false);
        BlockPos o = helper.absolutePos(new BlockPos(5, 2, 5));
        block(level, o, Blocks.STONE.defaultBlockState());
        block(level, o.above(), Blocks.STONE.defaultBlockState());
        block(level, o.east(), Blocks.STONE.defaultBlockState());
        ToolRun run = lua(her, "return numen.shape.cuboid(" + xyz(o) + ", " + xyz(o.offset(1, 2, 0)) + "):overlay(\"grass_block\")");
        helper.assertTrue(run.ranToTheEnd(), "overlay failed: " + run.receipt());
        helper.assertTrue(positions(list(run)).equals(Set.of(key(o.offset(0, 2, 0)), key(o.offset(1, 1, 0)))),
                "a cell over the stone of each column: " + list(run));
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
    @GameTest(template = "floor16", timeoutTicks = 1749, batch = BATCH)
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

    /** fill:坑与坑口那一层的空气,石头不填。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void fill_finds_the_pit_and_the_level_of_its_mouth(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_filler", new BlockPos(2, 2, 2), false);
        BlockPos o = helper.absolutePos(new BlockPos(5, 2, 5));
        for (BlockPos p : BlockPos.betweenClosed(o, o.offset(4, 0, 4))) {
            block(level, p.immutable(), Blocks.STONE.defaultBlockState());
        }
        for (BlockPos p : BlockPos.betweenClosed(o.offset(1, 0, 1), o.offset(3, 0, 3))) {
            block(level, p.immutable(), Blocks.AIR.defaultBlockState());
        }
        BlockPos centre = o.offset(2, 1, 2);
        ToolRun run = lua(her, "return numen.shape.fill(" + xyz(centre) + ", \"water\", 4, 2)");
        helper.assertTrue(run.ranToTheEnd(), "fill failed: " + run.receipt());
        Set<String> got = positions(list(run));
        int pit = 0;
        for (BlockPos p : BlockPos.betweenClosed(o.offset(1, 0, 1), o.offset(3, 0, 3))) {
            pit += got.contains(key(p.immutable())) ? 1 : 0;
        }
        helper.assertTrue(pit == 9, "the nine cells of the pit are filled: " + got);
        helper.assertTrue(!got.contains(key(o)) && !got.contains(key(o.offset(4, 0, 4))), "the stone rim is not: " + got);
        helper.assertTrue(got.contains(key(centre)) && got.contains(key(centre.offset(0, 0, 3))),
                "the level over the pit spreads sideways: " + got);
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
    }

    /** drain:水塘连着的水换成空气,够不着的水不动。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void drain_takes_the_water_of_the_pool_round_a_point(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_drainer", new BlockPos(2, 2, 2), false);
        BlockPos o = helper.absolutePos(new BlockPos(5, 2, 5));
        for (BlockPos p : BlockPos.betweenClosed(o, o.offset(4, 0, 4))) {
            block(level, p.immutable(), Blocks.STONE.defaultBlockState());
        }
        for (BlockPos p : BlockPos.betweenClosed(o.offset(1, 0, 1), o.offset(3, 0, 3))) {
            block(level, p.immutable(), Blocks.WATER.defaultBlockState());
        }
        ToolRun run = lua(her, "return numen.shape.drain(" + xyz(o.offset(2, 1, 2)) + ", 3)");
        helper.assertTrue(run.ranToTheEnd(), "drain failed: " + run.receipt());
        Set<String> expected = new HashSet<>();
        for (BlockPos p : BlockPos.betweenClosed(o.offset(1, 0, 1), o.offset(3, 0, 3))) {
            expected.add(key(p.immutable()));
        }
        helper.assertTrue(positions(list(run)).equals(expected), "the nine water cells: " + list(run));
        helper.assertTrue(list(run).asList().stream().allMatch(c -> c.getAsJsonObject().get("name").getAsString().equals("minecraft:air")),
                "each becomes air: " + list(run));
        helper.assertTrue(level.getBlockState(o.offset(2, 0, 2)).is(Blocks.WATER), "draining only reads");
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
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

    /** naturalize:草、三层泥土、石头。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void naturalize_lays_grass_then_three_dirt_then_stone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_naturalizer", new BlockPos(2, 2, 2), false);
        BlockPos o = helper.absolutePos(new BlockPos(6, 2, 6));
        for (int y = 0; y < 6; y++) {
            block(level, o.above(y), Blocks.STONE.defaultBlockState());
        }
        ToolRun run = lua(her, "return numen.shape.cuboid(" + xyz(o) + ", " + xyz(o.offset(0, 6, 0)) + "):naturalize()");
        helper.assertTrue(run.ranToTheEnd(), "naturalize failed: " + run.receipt());
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (var c : list(run)) {
            JsonObject b = c.getAsJsonObject();
            JsonObject pos = b.getAsJsonObject("pos");
            names.put(pos.get("x").getAsInt() + "," + pos.get("y").getAsInt() + "," + pos.get("z").getAsInt(), b.get("name").getAsString());
        }
        helper.assertTrue("minecraft:grass_block".equals(names.get(key(o.above(5)))) && "minecraft:dirt".equals(names.get(key(o.above(4))))
                        && "minecraft:dirt".equals(names.get(key(o.above(3)))) && "minecraft:dirt".equals(names.get(key(o.above(2))))
                        && names.size() == 4, "grass on top, three dirt below it, the stone below that already is: " + names);
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
    }

    /** gravity:悬空的方块落到这一列最低的空气上。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void gravity_lets_a_floating_block_fall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_faller", new BlockPos(2, 2, 2), false);
        BlockPos o = helper.absolutePos(new BlockPos(6, 2, 6));
        block(level, o.above(3), Blocks.GOLD_BLOCK.defaultBlockState());
        ToolRun run = lua(her, "return numen.shape.gravity(" + xyz(o.above(2)) + ", 0, {height = 2})");
        helper.assertTrue(run.ranToTheEnd(), "gravity failed: " + run.receipt());
        java.util.Map<String, String> names = new java.util.HashMap<>();
        for (var c : list(run)) {
            JsonObject b = c.getAsJsonObject();
            JsonObject pos = b.getAsJsonObject("pos");
            names.put(pos.get("x").getAsInt() + "," + pos.get("y").getAsInt() + "," + pos.get("z").getAsInt(), b.get("name").getAsString());
        }
        helper.assertTrue("minecraft:gold_block".equals(names.get(key(o))) && "minecraft:air".equals(names.get(key(o.above(3))))
                && names.size() == 2, "the gold block lands where the ground ends and leaves air behind: " + names);
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
