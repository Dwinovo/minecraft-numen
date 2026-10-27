package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.build.BuildStates;
import com.dwinovo.numen.build.BuildValidity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * {@code numen.shape} 里让原版生成器跑在记录用的世界上的那些,和补上的统计与笔刷形状,从她的入口跑,场景用代码搭。判据:交回的格合理;<b>真实世界一格没变</b>
 * (方块、方块实体、高度图、光照、实体都和调用前逐格一样);再 {@code numen.build.place} 放下去,世界里的样子和交回的格一致。
 *
 * <p>放下去的那几条挑的是不含液体的地物与结构:{@code place} 现在不放液体,那是 {@code place} 的边界,生成器照实交回、不替模型删格。
 * 一致的比法:{@code place} 自己对账用的 {@link BuildValidity#valid},不另立名单。
 * 树与树林用 floor52:floor16 的顶太低,树长不下;结构用 open64:别的模板顶上有一层盖,结构会落在盖上。
 */
public class GeneratorGameTests {

    private static final String BATCH = "numen_shape";

    /** 一片世界此刻的样子:方块、有没有方块实体、两种高度图、两种光照、里面的实体数。 */
    private record Snapshot(List<BlockState> states, List<Boolean> blockEntities, List<Integer> heights, List<Integer> light,
                            int entities) {}

    private static Snapshot snapshot(ServerLevel level, BlockPos min, BlockPos max) {
        List<BlockState> states = new ArrayList<>();
        List<Boolean> blockEntities = new ArrayList<>();
        List<Integer> heights = new ArrayList<>();
        List<Integer> light = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            states.add(level.getBlockState(pos));
            blockEntities.add(level.getBlockEntity(pos) != null);
            light.add(level.getBrightness(LightLayer.BLOCK, pos) * 100 + level.getBrightness(LightLayer.SKY, pos));
        }
        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int z = min.getZ(); z <= max.getZ(); z++) {
                heights.add(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) * 1000 + level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        }
        int entities = level.getEntitiesOfClass(Entity.class, new AABB(min.getX(), min.getY(), min.getZ(), max.getX() + 1,
                max.getY() + 1, max.getZ() + 1)).size();
        return new Snapshot(states, blockEntities, heights, light, entities);
    }

    private static JsonObject returned(ToolRun run) {
        return run.data().getAsJsonObject("returned");
    }

    private static BlockPos pos(JsonObject cell) {
        JsonObject p = cell.getAsJsonObject("pos");
        return new BlockPos(p.get("x").getAsInt(), p.get("y").getAsInt(), p.get("z").getAsInt());
    }

    /** 交回的格写成一段 Lua 的 Cells,交给 numen.build.place。 */
    private static String literal(JsonArray cells) {
        StringBuilder out = new StringBuilder("{");
        for (JsonElement e : cells) {
            JsonObject cell = e.getAsJsonObject();
            BlockPos p = pos(cell);
            out.append("{name = \"").append(cell.get("name").getAsString()).append("\", pos = {x = ").append(p.getX())
                    .append(", y = ").append(p.getY()).append(", z = ").append(p.getZ()).append("}},");
        }
        return out.append("}").toString();
    }

    /** 放下之后和交回的格不一致的第一格;一致的判据就是 {@code place} 自己对账用的 {@link BuildValidity#valid}。 */
    private static String problem(ServerLevel level, JsonArray cells) {
        for (JsonElement e : cells) {
            JsonObject cell = e.getAsJsonObject();
            BlockPos p = pos(cell);
            BlockState expected = BuildStates.normalize(BuildStates.parse(cell.get("name").getAsString(), false).blockState());
            if (!expected.isAir() && BuildStates.materialItem(expected.getBlock()) == net.minecraft.world.item.Items.AIR) {
                continue;   // 没有物品放得出它的方块(盆栽)她不放,回执的 no_item 一类里说
            }
            BlockState actual = level.getBlockState(p);
            if (!BuildValidity.valid(actual, expected)) {
                return p.toShortString() + " is " + actual + ", the cell says " + expected;
            }
        }
        return null;
    }

    private static boolean noLiquid(JsonArray cells) {
        for (JsonElement e : cells) {
            String name = e.getAsJsonObject().get("name").getAsString();
            if (name.startsWith("minecraft:water") || name.startsWith("minecraft:lava") || name.contains("waterlogged=true")) {
                return false;
            }
        }
        return true;
    }

    private static void scene(ServerLevel level, BlockPos pos, net.minecraft.world.level.block.Block block) {
        level.setBlock(pos, block.defaultBlockState(), 2);
    }

    /**
     * feature:一棵原版橡树种在一块泥土上头。交回的是原木与树叶,真实世界一格没动;放下去之后一致。
     */
    @GameTest(template = "floor52", timeoutTicks = 3000, batch = BATCH)
    public static void feature_grows_a_vanilla_tree_on_the_recorded_level_and_leaves_the_world_alone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_tree", new BlockPos(2, 2, 2), true);
        BlockPos dirt = helper.absolutePos(new BlockPos(10, 2, 10));
        scene(level, dirt, Blocks.DIRT);
        Snapshot before = snapshot(level, dirt.offset(-8, -3, -8), dirt.offset(8, 14, 8));
        ToolRun run = lua(her, "return numen.shape.feature(" + xyz(dirt.above()) + ", \"minecraft:oak\")");
        helper.assertTrue(run.ranToTheEnd(), "feature failed: " + run.receipt());
        JsonArray cells = returned(run).getAsJsonArray("cells");
        long logs = cells.asList().stream().filter(c -> c.getAsJsonObject().get("name").getAsString().startsWith("minecraft:oak_log")).count();
        long leaves = cells.asList().stream().filter(c -> c.getAsJsonObject().get("name").getAsString().startsWith("minecraft:oak_leaves")).count();
        helper.assertTrue(logs >= 4 && leaves >= 10, "a tree: " + logs + " logs and " + leaves + " leaves of " + cells.size() + " cells");
        helper.assertTrue(cells.asList().stream().anyMatch(c -> pos(c.getAsJsonObject()).equals(dirt.above())
                && c.getAsJsonObject().get("name").getAsString().startsWith("minecraft:oak_log")), "the trunk stands on the cell above the dirt");
        helper.assertTrue(snapshot(level, dirt.offset(-8, -3, -8), dirt.offset(8, 14, 8)).equals(before),
                "the real world changed under the recorded one");
        helper.assertTrue(returned(run).getAsJsonArray("lost").isEmpty(), "a tree adds nothing Cells cannot hold: " + returned(run));
        ToolRun build = lua(her, "numen.build.place(" + literal(cells) + ")");
        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.succeeded(), "place failed: " + build.outcome());
            String wrong = problem(level, cells);
            helper.assertTrue(wrong == null, "after place " + wrong);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /**
     * structure:一个原版小结构(沼泽小屋)在这一块的区块里生成。交回的是方块;它生成的女巫和猫不生成、方块实体的数据不进格,都在 lost 里;
     * 真实世界一格没动;放下去之后一致。
     */
    @GameTest(template = "open64", timeoutTicks = 12000, batch = BATCH)
    public static void structure_records_a_swamp_hut_and_says_which_creatures_it_dropped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // a structure settles on the highest block of the column: the arena has no walls, but the test frame puts a lid
        // over it. Clear whatever stands above the floor and lay a stone floor of our own; the hut then sits on that floor
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                level.setBlock(helper.absolutePos(new BlockPos(x, 1, z)), Blocks.STONE.defaultBlockState(), 2);
                for (int y = 2; y < 40; y++) {
                    BlockPos up = helper.absolutePos(new BlockPos(x, y, z));
                    if (!level.getBlockState(up).isAir()) {
                        level.setBlock(up, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        }
        NumenPlayer her = spawnAt(helper, "gametest_hut", new BlockPos(2, 2, 2), true);
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        // the first chunk that lies wholly inside the arena, and the one after it: the hut reaches into both
        int cx = ((origin.getX() + 15) >> 4);
        int cz = ((origin.getZ() + 15) >> 4);
        BlockPos at = new BlockPos(cx * 16 + 8, origin.getY() + 2, cz * 16 + 8);
        BlockPos min = new BlockPos(cx * 16 - 1, origin.getY() - 3, cz * 16 - 1);
        BlockPos max = new BlockPos(cx * 16 + 33, origin.getY() + 20, cz * 16 + 33);
        Snapshot before = snapshot(level, min, max);
        ToolRun run = lua(her, "return numen.shape.structure(" + xyz(at) + ", \"minecraft:swamp_hut\")");
        helper.assertTrue(run.ranToTheEnd(), "structure failed: " + run.receipt());
        JsonArray cells = returned(run).getAsJsonArray("cells");
        int lowest = Integer.MAX_VALUE;
        int highest = Integer.MIN_VALUE;
        for (JsonElement c : cells) {
            lowest = Math.min(lowest, pos(c.getAsJsonObject()).getY());
            highest = Math.max(highest, pos(c.getAsJsonObject()).getY());
        }
        StringBuilder column = new StringBuilder();
        for (int y = origin.getY() - 4; y < origin.getY() + 40; y++) {
            if (!level.getBlockState(new BlockPos(at.getX(), y, at.getZ())).isAir()) {
                column.append(y).append(' ');
            }
        }
        helper.assertTrue(cells.size() >= 30 && lowest <= origin.getY() + 3, "a hut on the floor: " + cells.size() + " cells from y "
                + lowest + " to " + highest + ", floor at " + (origin.getY() + 1) + ", blocks in its column at y " + column);
        helper.assertTrue(noLiquid(cells), "the hut holds no liquid");
        helper.assertTrue(snapshot(level, min, max).equals(before), "the real world changed under the recorded one");
        JsonArray lost = returned(run).getAsJsonArray("lost");
        boolean witch = lost.asList().stream().anyMatch(l -> l.getAsJsonObject().get("kind").getAsString().equals("entity")
                && l.getAsJsonObject().get("name").getAsString().equals("minecraft:witch"));
        helper.assertTrue(witch, "the witch it spawned is reported, not spawned: " + lost);
        ToolRun build = lua(her, "numen.build.place(" + literal(cells) + ")");
        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.succeeded(), "place failed: " + build.outcome());
            String wrong = problem(level, cells);
            helper.assertTrue(wrong == null, "after place " + wrong);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** forest:一列、一棵原版橡树,每列掷一次骰子(密度 100)。树长在这一列最高的非空气方块上头。 */
    @GameTest(template = "floor52", timeoutTicks = 3000, batch = BATCH)
    public static void forest_plants_a_vanilla_tree_in_a_column_without_touching_the_world(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_forest", new BlockPos(2, 2, 2), true);
        BlockPos dirt = helper.absolutePos(new BlockPos(10, 2, 10));
        scene(level, dirt, Blocks.DIRT);
        Snapshot before = snapshot(level, dirt.offset(-8, -3, -8), dirt.offset(8, 14, 8));
        ToolRun run = lua(her, "return numen.shape.cuboid(" + xyz(dirt.offset(0, -1, 0)) + ", " + xyz(dirt.offset(0, 4, 0))
                + "):forest(\"minecraft:oak_checked\", 100)");
        helper.assertTrue(run.ranToTheEnd(), "forest failed: " + run.receipt());
        JsonArray cells = returned(run).getAsJsonArray("cells");
        helper.assertTrue(cells.asList().stream().anyMatch(c -> pos(c.getAsJsonObject()).equals(dirt.above())
                && c.getAsJsonObject().get("name").getAsString().startsWith("minecraft:oak_log")),
                "a tree on the dirt: " + cells.size() + " cells");
        helper.assertTrue(snapshot(level, dirt.offset(-8, -3, -8), dirt.offset(8, 14, 8)).equals(before),
                "the real world changed under the recorded one");
        ToolRun build = lua(her, "numen.build.place(" + literal(cells) + ")");
        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.succeeded(), "place failed: " + build.outcome());
            String wrong = problem(level, cells);
            helper.assertTrue(wrong == null, "after place " + wrong);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** flora:草地上每列一格草或小花(密度 100),交回之后世界没变;放下去之后一致。 */
    @GameTest(template = "floor16", timeoutTicks = 1749, batch = BATCH)
    public static void flora_covers_grass_with_plants(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_flora", new BlockPos(2, 2, 2), true);
        BlockPos from = helper.absolutePos(new BlockPos(6, 2, 6));
        for (BlockPos p : BlockPos.betweenClosed(from, from.offset(3, 0, 3))) {
            scene(level, p.immutable(), Blocks.GRASS_BLOCK);
        }
        Snapshot before = snapshot(level, from.offset(-1, -1, -1), from.offset(4, 3, 4));
        ToolRun run = lua(her, "return numen.shape.cuboid(" + xyz(from) + ", " + xyz(from.offset(3, 3, 3)) + "):flora(100)");
        helper.assertTrue(run.ranToTheEnd(), "flora failed: " + run.receipt());
        JsonArray cells = run.data().getAsJsonArray("returned");
        helper.assertTrue(cells.size() == 16 && cells.asList().stream().allMatch(c -> pos(c.getAsJsonObject()).getY() == from.getY() + 1),
                "one plant above each of the 16 grass blocks: " + cells);
        helper.assertTrue(snapshot(level, from.offset(-1, -1, -1), from.offset(4, 3, 4)).equals(before), "the real world changed");
        ToolRun build = lua(her, "numen.build.place(" + literal(cells) + ")");
        succeedWhen(helper, () -> {
            helper.assertTrue(build.done() && build.succeeded(), "place failed: " + build.outcome());
            String wrong = problem(level, cells);
            helper.assertTrue(wrong == null, "after place " + wrong);
            CompanionFactory.despawn(level.getServer(), her);
        });
    }

    /** 生成器读到没加载的区块:整个调用失败,说出是哪一片,不读一半。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void a_generator_that_reads_an_unloaded_chunk_fails_the_whole_call(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_far_tree", new BlockPos(2, 2, 2), false);
        ToolRun run = lua(her, "local ok, err = pcall(numen.shape.feature, {x = 100000, y = 64, z = 100000}, \"minecraft:oak\")\n"
                + "return {ok = ok, kind = err.kind, message = err.message}");
        helper.assertTrue(run.ranToTheEnd(), "the program itself failed: " + run.receipt());
        JsonObject got = returned(run);
        helper.assertTrue(!got.get("ok").getAsBoolean() && got.get("kind").getAsString().equals("not_found")
                        && got.get("message").getAsString().contains("not loaded")
                        && got.get("message").getAsString().contains("x 100000 to 100015, z 100000 to 100015"),
                "the call fails and names the chunk: " + got);
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
    }

    /**
     * count、distr、size 与 extinguish、erode、raise:从 Lua 入口调,各一眼。统计只读不出格;灭火是"火→空气";erode 把孤零零的方块磨掉;
     * raise 让每格取它下面那一格的方块。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = BATCH)
    public static void statistics_and_brush_shapes_read_the_world(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        NumenPlayer her = spawnAt(helper, "gametest_stats", new BlockPos(2, 2, 2), false);
        BlockPos row = helper.absolutePos(new BlockPos(5, 2, 5));
        for (int i = 0; i < 3; i++) {
            scene(level, row.east(i), Blocks.STONE);
        }
        scene(level, row.east(3), Blocks.DIRT);
        scene(level, row.east(4), Blocks.DIRT);
        BlockPos fire = helper.absolutePos(new BlockPos(10, 2, 10));
        scene(level, fire, Blocks.FIRE);
        BlockPos lone = helper.absolutePos(new BlockPos(5, 5, 9));
        scene(level, lone, Blocks.STONE);
        BlockPos pillar = helper.absolutePos(new BlockPos(9, 2, 8));
        scene(level, pillar, Blocks.STONE);
        ToolRun run = lua(her, "local r = numen.shape.cuboid(" + xyz(row) + ", " + xyz(row.east(4)) + ")\n"
                + "local size = r:size()\nlocal d = r:distr()\nlocal raised = numen.shape.raise(" + xyz(pillar.above()) + ", 0, \"cuboid\")\n"
                + "return {count = r:count(\"stone\"), first = d[1].name, first_count = d[1].count, second = d[2].name, "
                + "blocks = size.blocks, width = size.size.x, height = size.size.y, distance = size.distance, "
                + "out = numen.shape.extinguish(" + xyz(fire) + ", 3), eroded = numen.shape.erode(" + xyz(lone) + ", 2), "
                + "raised_name = raised[1].name, raised_y = raised[1].pos.y}");
        helper.assertTrue(run.ranToTheEnd(), "the program failed: " + run.receipt());
        JsonObject got = returned(run);
        helper.assertTrue(got.get("count").getAsInt() == 3 && got.get("first").getAsString().equals("minecraft:stone")
                        && got.get("first_count").getAsInt() == 3 && got.get("second").getAsString().equals("minecraft:dirt"),
                "count and distribution: " + got);
        helper.assertTrue(got.get("blocks").getAsInt() == 5 && got.get("width").getAsInt() == 5 && got.get("height").getAsInt() == 1
                && Math.abs(got.get("distance").getAsDouble() - 4) < 1e-9, "size: " + got);
        JsonArray out = got.getAsJsonArray("out");
        helper.assertTrue(out.size() == 1 && pos(out.get(0).getAsJsonObject()).equals(fire)
                && out.get(0).getAsJsonObject().get("name").getAsString().equals("air"), "the fire goes out: " + out);
        JsonArray eroded = got.getAsJsonArray("eroded");
        helper.assertTrue(eroded.asList().stream().anyMatch(c -> pos(c.getAsJsonObject()).equals(lone)
                && c.getAsJsonObject().get("name").getAsString().equals("minecraft:air")), "the lone block wears away: " + eroded);
        helper.assertTrue(got.get("raised_name").getAsString().equals("minecraft:stone") && got.get("raised_y").getAsInt() == pillar.above().getY(),
                "the cell over the pillar takes the block under it: " + got);
        helper.assertTrue(level.getBlockState(fire).is(Blocks.FIRE) && level.getBlockState(lone).is(Blocks.STONE), "reading changed the world");
        CompanionFactory.despawn(level.getServer(), her);
        helper.succeed();
    }
}
