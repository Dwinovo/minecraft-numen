package com.dwinovo.numen.tools.shape;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.sdk.BlockAt;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读世界的形状操作,在一个小世界({@link FakeBlocks}:地面以下石头、以上空气,上面摆几块)里逐个判:每个操作交回哪几格、写什么。判据是 WorldEdit
 * 的算法,期望值按它的规则手算,或另写一份对照(球里的格、卷积)。世界里的方块一律只读;交回的"要写什么"由测试自己写进世界去看结果。
 */
class TerrainReadsTest {

    @BeforeAll
    static void install() {
        NumenApiFixture.install();
    }

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Set<String> keys(List<BlockPos> cells) {
        Set<String> out = new LinkedHashSet<>();
        cells.forEach(p -> out.add(p.getX() + "," + p.getY() + "," + p.getZ()));
        return out;
    }

    private static Map<String, String> named(List<BlockAt> blocks) {
        Map<String, String> out = new LinkedHashMap<>();
        blocks.forEach(b -> out.put(b.pos().getX() + "," + b.pos().getY() + "," + b.pos().getZ(), b.name()));
        return out;
    }

    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    // ---- copy、replace、trim 用的读 ----

    @Test
    void readGivesTheBlockOfEveryCellAndAMaskTurnsTheRestToAir() {
        FakeBlocks world = FakeBlocks.flat(-1).put(1, 0, 0, "oak_stairs[facing=east,half=top]");
        List<BlockPos> cells = List.of(at(0, -1, 0), at(0, 0, 0), at(1, 0, 0));
        assertEquals(Map.of("0,-1,0", "minecraft:stone", "0,0,0", "minecraft:air",
                "1,0,0", "minecraft:oak_stairs[facing=east,half=top]"), named(TerrainReads.read(world, cells, null, 0)));
        assertEquals(Map.of("0,-1,0", "minecraft:stone", "0,0,0", "minecraft:air", "1,0,0", "minecraft:air"),
                named(TerrainReads.read(world, cells, ShapeMasks.parse("stone"), 0)));
    }

    @Test
    void matchingKeepsTheCellsThatPassTheMaskInOrder() {
        FakeBlocks world = FakeBlocks.flat(-1).put(1, 0, 0, "dirt");
        List<BlockPos> cells = List.of(at(1, 0, 0), at(0, 0, 0), at(0, -1, 0));
        assertEquals(List.of(key(1, 0, 0), key(0, -1, 0)), List.copyOf(keys(TerrainReads.matching(world, cells,
                ShapeMasks.parse("stone,dirt"), 0))));
    }

    @Test
    void whatOneCellsHoldsHasALimit() {
        FakeBlocks world = FakeBlocks.flat(-1);
        List<BlockPos> many = new ArrayList<>();
        for (int i = 0; i < 16385; i++) {
            many.add(at(i, 0, 0));
        }
        ApiError error = assertThrows(ApiError.class, () -> TerrainReads.read(world, many, null, 0));
        assertEquals(ErrorKind.BAD_ARGUMENT, error.kind());
        assertTrue(error.getMessage().contains("more than 16384 cells"), error.getMessage());
    }

    // ---- hollow ----

    private static List<BlockPos> box(int x1, int y1, int z1, int x2, int y2, int z2) {
        List<BlockPos> out = new ArrayList<>();
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    out.add(at(x, y, z));
                }
            }
        }
        return out;
    }

    /** 一格空气围着的 5 × 5 × 5 石块,选区是连空气圈在内的 7 × 7 × 7:空气是"外面",石块往里数厚度。 */
    @Test
    void hollowLeavesAShellAsThickAsAskedMeasuredAsAWalkerSteps() {
        FakeBlocks world = FakeBlocks.flat(-20).fill(1, 1, 1, 5, 5, 5, "stone");
        List<BlockPos> region = box(0, 0, 0, 6, 6, 6);
        assertEquals(27, TerrainReads.hollow(world, region, 0).size(), "the 3 x 3 x 3 inside a one-thick shell");
        assertEquals(27, TerrainReads.hollow(world, region, 1).size());
        assertEquals(Set.of(key(3, 3, 3)), keys(TerrainReads.hollow(world, region, 2)), "two thick leaves the middle");
        assertEquals(0, TerrainReads.hollow(world, region, 3).size());
    }

    @Test
    void aShapeSolidAllRoundHasNoOutsideSoAllOfItComesBack() {
        FakeBlocks world = FakeBlocks.flat(-20).fill(0, 0, 0, 2, 2, 2, "stone");
        assertEquals(27, TerrainReads.hollow(world, box(0, 0, 0, 2, 2, 2), 0).size());
    }

    @Test
    void hollowDoesNotCountTheAirOfASealedRoomAsOutside() {
        // a stone block 5 wide with a sealed 1-cell room in the middle: the room is not open air, so it is not "outside"
        FakeBlocks world = FakeBlocks.flat(-20).fill(1, 1, 1, 5, 5, 5, "stone").put(3, 3, 3, "air");
        Set<String> inside = keys(TerrainReads.hollow(world, box(0, 0, 0, 6, 6, 6), 0));
        assertEquals(27, inside.size());
        assertTrue(inside.contains(key(3, 3, 3)));
    }

    /** 两块相隔十万格的格:代价按格数,不按它们外包盒的面积;每块各自当"外面"。 */
    @Test
    void cellsFarApartCostTheirOwnCountNotTheAreaBetween() {
        FakeBlocks world = FakeBlocks.flat(-20).fill(0, 0, 0, 2, 2, 2, "stone").fill(100000, 0, 100000, 100002, 2, 100002, "stone");
        List<BlockPos> cells = new ArrayList<>(box(0, 0, 0, 2, 2, 2));
        cells.addAll(box(100000, 0, 100000, 100002, 2, 100002));
        assertEquals(54, TerrainReads.read(world, cells, null, 0).size());
        assertEquals(54, TerrainReads.matching(world, cells, ShapeMasks.parse("stone"), 0).size());
        int hollowed = TerrainReads.hollow(world, cells, 0).size();
        assertTrue(hollowed > 0 && hollowed <= 54, "the flood starts from the box's faces next to the cells only: " + hollowed);
    }

    @Test
    void anAreaOfMoreThanOneCallsColumnsIsRefusedBeforeReading() {
        ApiError error = assertThrows(ApiError.class, () -> new LoadedBlocks(null, BlockPos.ZERO).require(0, 0, 1024, 1024));
        assertEquals(ErrorKind.BAD_ARGUMENT, error.kind());
    }

    // ---- overlay、naturalize ----

    @Test
    void overlayIsTheCellAboveTheHighestBlockOfEachColumnAndSkipsColumnsBuriedUnderSomething() {
        FakeBlocks world = new FakeBlocks((x, z) -> x == 0 ? 5 : 3).put(2, 11, 0, "stone");
        List<BlockPos> columns = List.of(at(0, 0, 0), at(1, 0, 0), at(2, 0, 0), at(3, 0, 0));
        assertEquals(Set.of(key(0, 6, 0), key(1, 4, 0), key(3, 4, 0)), keys(TerrainReads.overlay(world, columns, 0, 10)));
        // a column that is all air has no top
        assertEquals(Set.of(), keys(TerrainReads.overlay(FakeBlocks.flat(-100), List.of(at(0, 0, 0)), 0, 10)));
    }

    @Test
    void naturalizeIsGrassThenThreeDirtThenStoneFromTheHighestOfThem() {
        FakeBlocks world = FakeBlocks.flat(12).put(0, 10, 0, "dirt").put(0, 8, 0, "air");
        Map<String, String> got = named(TerrainReads.naturalize(world, List.of(at(0, 0, 0)), 5, 20));
        // y 12 is the top: grass; 11, 10, 9 dirt (10 already is); below that stone, which it already is; the air at 8 stays
        assertEquals(Map.of("0,12,0", "minecraft:grass_block", "0,11,0", "minecraft:dirt", "0,9,0", "minecraft:dirt"), got);
    }

    // ---- smooth ----

    /** 另写的一份核:5 的半径、sigma 1.0 的高斯,float 的算法,加 0.5 再取整。 */
    private static int[] blur(int[] heights, int width, int length, int passes) {
        double total = 0;
        for (int a = -5; a <= 5; a++) {
            for (int b = -5; b <= 5; b++) {
                total += Math.exp(-(a * a + b * b) / 2.0);
            }
        }
        int[] now = heights.clone();
        for (int pass = 0; pass < passes; pass++) {
            int[] next = new int[now.length];
            for (int i = 0; i < now.length; i++) {
                int x = i % width;
                int z = i / width;
                float sum = 0f;
                for (int a = -5; a <= 5; a++) {
                    int zz = (z + a >= 0 && z + a < length) ? z + a : z;
                    for (int b = -5; b <= 5; b++) {
                        int xx = (x + b >= 0 && x + b < width) ? x + b : x;
                        float weight = (float) (Math.exp(-(a * a + b * b) / 2.0) / total);
                        sum += weight * now[zz * width + xx];
                    }
                }
                next[i] = (int) Math.floor(sum + 0.5);
            }
            now = next;
        }
        return now;
    }

    @Test
    void smoothBlursTheGroundHeightAndRaisesOrLowersEachColumnToIt() {
        FakeBlocks world = new FakeBlocks((x, z) -> x == 3 && z == 3 ? 3 : (x == 0 && z == 0 ? -2 : 0));
        List<BlockAt> writes = TerrainReads.smooth(world, at(0, -2, 0), at(6, 6, 6), 1, null, 0);
        int[] heights = new int[49];
        for (int z = 0; z < 7; z++) {
            for (int x = 0; x < 7; x++) {
                heights[z * 7 + x] = x == 3 && z == 3 ? 3 : (x == 0 && z == 0 ? -2 : 0);
            }
        }
        int[] expected = blur(heights, 7, 7, 1);
        FakeBlocks after = new FakeBlocks((x, z) -> x == 3 && z == 3 ? 3 : (x == 0 && z == 0 ? -2 : 0)).apply(writes);
        for (int z = 0; z < 7; z++) {
            for (int x = 0; x < 7; x++) {
                for (int y = -2; y <= 6; y++) {
                    boolean stone = y <= Math.min(6, expected[z * 7 + x]);
                    assertEquals(stone, !after.at(x, y, z).isAir(), "column " + x + "," + z + " at y " + y);
                }
            }
        }
        assertTrue(writes.stream().anyMatch(w -> w.pos().equals(at(3, 3, 3)) && w.name().equals("minecraft:air")),
                "the spike is cut down");
    }

    @Test
    void smoothLeavesFlatGroundAlone() {
        assertEquals(0, TerrainReads.smooth(FakeBlocks.flat(0), at(0, -2, 0), at(6, 6, 6), 2, null, 0).size());
    }

    // ---- fill、drain、fixwater ----

    private static boolean inBall(double radius, int x, int y, int z) {
        double r = radius + 0.5;
        double dx = x / r;
        double dy = y / r;
        double dz = z / r;
        return dx * dx + dy * dy + dz * dz <= 1;
    }

    /** 地面在 y = -1 以下,中间一个 3 × 3、两层深的坑。 */
    private static FakeBlocks pit() {
        return FakeBlocks.flat(-1).fill(-1, -2, -1, 1, -1, 1, "air");
    }

    @Test
    void fillFillsTheLevelOfTheStartAndWhatGoesDownFromItNotWhatSpreadsSidewaysBelow() {
        Set<String> expected = new LinkedHashSet<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (inBall(3, x, 0, z)) {
                    expected.add(key(x, 0, z));
                }
            }
        }
        FakeBlocks world = pit();
        assertEquals(expected, keys(TerrainReads.fill(world, at(0, 0, 0), 3, 1, false)), "one layer deep is the level only");
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) {
                expected.add(key(x, -1, z));
                expected.add(key(x, -2, z));
            }
        }
        assertEquals(expected, keys(TerrainReads.fill(world, at(0, 0, 0), 3, 3, false)));
        assertEquals(at(0, 0, 0), TerrainReads.fill(world, at(0, 0, 0), 3, 3, false).getFirst(), "the start comes first");
    }

    @Test
    void fillrSpreadsEverywhereTheAirGoesSoItFindsTheTunnelBesideThePit() {
        // a tunnel off the pit with stone over it: only going sideways below the start's level gets in
        FakeBlocks world = pit().put(2, -1, 0, "air").put(3, -1, 0, "air").put(2, 0, 0, "stone").put(3, 0, 0, "stone");
        Set<String> sideways = keys(TerrainReads.fill(world, at(0, 0, 0), 3, 3, false));
        Set<String> everywhere = keys(TerrainReads.fill(world, at(0, 0, 0), 3, Integer.MAX_VALUE, true));
        assertTrue(!sideways.contains(key(2, -1, 0)) && everywhere.contains(key(2, -1, 0)) && everywhere.contains(key(3, -1, 0)));
        assertTrue(everywhere.containsAll(sideways));
    }

    @Test
    void drainTakesTheConnectedWaterInTheBallAndWaterloggedBlocksOnlyWhenAsked() {
        FakeBlocks world = FakeBlocks.flat(-3).fill(-2, -2, -2, 2, -1, 2, "water").put(3, -1, 0, "oak_stairs[waterlogged=true]")
                .put(9, -1, 0, "water");
        Set<String> expected = new LinkedHashSet<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= -1; y++) {
                for (int z = -2; z <= 2; z++) {
                    if (inBall(3, x, y, z)) {
                        expected.add(key(x, y, z));
                    }
                }
            }
        }
        Map<String, String> dry = named(TerrainReads.drain(world, at(0, 0, 0), 3, false));
        assertEquals(expected, dry.keySet());
        assertEquals(Set.of("minecraft:air"), new LinkedHashSet<>(dry.values()));
        Map<String, String> all = named(TerrainReads.drain(world, at(0, 0, 0), 3, true));
        assertEquals("minecraft:oak_stairs", all.get("3,-1,0"), "the stairs lose their water and stay");
        assertTrue(!dry.containsKey("3,-1,0") && !all.containsKey("9,-1,0"), "water out of reach is left");
    }

    @Test
    void fixwaterMakesFlowingWaterStationaryAndFollowsItSidewaysAndDown() {
        FakeBlocks world = FakeBlocks.flat(-1).put(0, 0, 0, "water[level=3]");
        Map<String, String> got = named(TerrainReads.fixLiquid(world, at(0, 0, 0), 2, net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()));
        Set<String> expected = new LinkedHashSet<>();
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                if (inBall(2, x, 0, z)) {
                    expected.add(key(x, 0, z));
                }
            }
        }
        assertEquals(expected, got.keySet());
        assertEquals(Set.of("minecraft:water"), new LinkedHashSet<>(got.values()));
    }

    // ---- thaw、green、gravity、heightmap、^ ----

    @Test
    void thawTurnsTheTopIceToWaterAndTheTopSnowToAir() {
        FakeBlocks world = FakeBlocks.flat(3).put(0, 5, 0, "ice").put(1, 4, 0, "snow[layers=2]").put(2, 3, 0, "packed_ice");
        assertEquals(Map.of("0,5,0", "minecraft:water", "1,4,0", "minecraft:air"),
                named(TerrainReads.thaw(world, at(0, 5, 0), 3, 10)));
    }

    @Test
    void greenTurnsTheFirstDirtDownAColumnToGrassUnlessWaterOrSomethingSolidComesFirst() {
        FakeBlocks world = FakeBlocks.flat(3).put(0, 3, 0, "dirt").put(1, 4, 0, "water").put(1, 3, 0, "dirt")
                .put(0, 3, 1, "coarse_dirt");
        assertEquals(Map.of("0,3,0", "minecraft:grass_block"), named(TerrainReads.green(world, at(0, 5, 0), 2, 8, false)));
        assertEquals(Map.of("0,3,0", "minecraft:grass_block", "0,3,1", "minecraft:grass_block"),
                named(TerrainReads.green(world, at(0, 5, 0), 2, 8, true)));
    }

    @Test
    void gravityLetsBlocksFallToTheLowestAirOfTheirColumn() {
        FakeBlocks world = FakeBlocks.flat(-1).put(0, 3, 0, "stone").put(0, 4, 0, "dirt");
        assertEquals(Map.of("0,0,0", "minecraft:stone", "0,1,0", "minecraft:dirt", "0,3,0", "minecraft:air",
                "0,4,0", "minecraft:air"), named(TerrainReads.gravity(world, at(0, 0, 0), 0, 10)));
    }

    @Test
    void aHeightmapRaisesEachColumnByItsGridValueTimesIntensityFromTheSouthRowFirst() {
        FakeBlocks world = FakeBlocks.flat(-1);
        // the first row is the south edge: only the south-west corner (x -1, z +1) is 1, so 3 high; its first layer is the ground
        assertEquals(Map.of("-1,0,1", "minecraft:stone", "-1,1,1", "minecraft:stone"), named(TerrainReads.heightmap(world, at(0, 0, 0), 1,
                new double[][]{{1, 0, 0}, {0, 0, 0}, {0, 0, 0}}, 3, false, false, false)));
        // erase digs down instead, from the top block
        assertEquals(Map.of("-1,-1,1", "minecraft:air", "-1,-2,1", "minecraft:air", "-1,-3,1", "minecraft:air"),
                named(TerrainReads.heightmap(world, at(0, 0, 0), 1, new double[][]{{1, 0, 0}, {0, 0, 0}, {0, 0, 0}}, 3, true,
                        false, false)));
    }

    @Test
    void aCaretPatternChangesWhatIsThereAndKeepsWhatItCan() {
        FakeBlocks world = FakeBlocks.flat(-1).put(0, 0, 0, "oak_stairs[waterlogged=true,facing=east,half=top]").put(1, 0, 0, "stone");
        List<BlockPos> cells = List.of(at(0, 0, 0), at(1, 0, 0));
        assertEquals(Map.of("0,0,0", "minecraft:oak_stairs[facing=east,half=top]", "1,0,0", "minecraft:stone"),
                named(TerrainReads.restate(world, cells, "^[waterlogged=false]")));
        assertEquals(Map.of("0,0,0", "minecraft:oak_stairs[facing=west,half=top,waterlogged=true]", "1,0,0", "minecraft:stone"),
                named(TerrainReads.restate(world, cells, "^[facing=west]")));
        assertEquals(Map.of("0,0,0", "minecraft:spruce_stairs[facing=east,half=top,waterlogged=true]", "1,0,0", "minecraft:spruce_stairs"),
                named(TerrainReads.restate(world, cells, "^spruce_stairs")));
        assertEquals(Map.of("0,0,0", "minecraft:oak_stairs[facing=east,half=top,waterlogged=true]", "1,0,0", "minecraft:oak_stairs[half=top]"),
                named(TerrainReads.restate(world, cells, "^oak_stairs[half=top]")));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> TerrainReads.restate(world, cells, "^[half=sideways]"))
                .getMessage().contains("sideways"));
    }

    // ---- snowsmooth、morph、count、distr ----

    @Test
    void snowsmoothLaysOneSnowLayerOnFlatGroundAndALayerPerEighthOnASlope() {
        FakeBlocks flat = FakeBlocks.flat(-1);
        Map<String, String> laid = named(TerrainReads.snowSmooth(flat, at(0, -1, 0), at(2, 3, 2), 1, null, 1, 0));
        assertEquals(18, laid.size(), "a snow layer on each of the 3 x 3 columns, and a snow block where the ground's top was");
        assertEquals(9, laid.entrySet().stream().filter(e -> e.getKey().split(",")[1].equals("0") && e.getValue().equals("minecraft:snow")).count());
        assertEquals(9, laid.entrySet().stream().filter(e -> e.getKey().split(",")[1].equals("-1") && e.getValue().equals("minecraft:snow_block")).count());
        // a step: the column beside a raised one is blurred up by a few eighths, the raised one down
        FakeBlocks step = FakeBlocks.flat(-1).fill(0, 0, 0, 0, 0, 2, "stone");
        Map<String, String> smoothed = named(TerrainReads.snowSmooth(step, at(0, -1, 0), at(2, 3, 2), 1, null, 1, 0));
        assertTrue(smoothed.get("1,0,1").startsWith("minecraft:snow[layers="), "the neighbour of the step gets more than the flat layer: " + smoothed);
    }

    @Test
    void erodeWearsALoneBlockAwayAndDilateFillsAHoleSolidOnFiveSides() {
        // erode preset (2, 1, 5, 1): a stone with air on five sides becomes air
        FakeBlocks lone = FakeBlocks.flat(-1).put(0, 0, 0, "stone");
        assertEquals(Map.of("0,0,0", "minecraft:air"), named(TerrainReads.morph(lone, at(0, 0, 0), 2, 2, 1, 5, 1)));
        // dilate preset (5, 1, 2, 1): an empty cell with five solid faces fills with what is round it
        FakeBlocks hole = FakeBlocks.flat(-1).put(0, -1, 0, "air");
        assertEquals(Map.of("0,-1,0", "minecraft:stone"), named(TerrainReads.morph(hole, at(0, -1, 0), 2, 5, 1, 2, 1)));
        // nothing outside the ball changes
        assertTrue(TerrainReads.morph(FakeBlocks.flat(-1).put(6, 0, 0, "stone"), at(0, 0, 0), 2, 2, 1, 5, 1).isEmpty());
    }

    @Test
    void countAndDistributionOnlyCountWhatPassesTheMaskAndListTheMostCommonFirst() {
        FakeBlocks world = FakeBlocks.flat(-1).put(0, 0, 0, "dirt").put(1, 0, 0, "dirt").put(2, 0, 0, "oak_stairs[facing=east]")
                .put(3, 0, 0, "oak_stairs[facing=north]");
        List<BlockPos> cells = List.of(at(0, 0, 0), at(1, 0, 0), at(2, 0, 0), at(3, 0, 0), at(4, 0, 0));
        assertEquals(2, TerrainReads.count(world, cells, ShapeMasks.parse("dirt"), 0));
        assertEquals(4, TerrainReads.count(world, cells, ShapeMasks.parse("#existing"), 0));
        List<TerrainReads.Counted> byBlock = TerrainReads.distribution(world, cells, null, 0, false);
        assertEquals(List.of(new TerrainReads.Counted("minecraft:dirt", 2), new TerrainReads.Counted("minecraft:oak_stairs", 2),
                new TerrainReads.Counted("minecraft:air", 1)).size(), byBlock.size());
        assertEquals(2, byBlock.get(0).count());
        assertEquals(1, byBlock.get(byBlock.size() - 1).count());
        assertEquals(4, TerrainReads.distribution(world, cells, null, 0, true).size(), "by state, the two stairs are two kinds");
        assertEquals(2, TerrainReads.distribution(world, cells, ShapeMasks.parse("dirt"), 0, false).get(0).count());
    }
}
