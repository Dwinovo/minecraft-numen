package com.dwinovo.numen.area;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 格子的小节位图:构造、查询、并差交、按方块筛、外扩、最近一格、存盘。凡是跨小节的都拿逐格算的结果对照。
 * 需要 MC 引导(方块状态与存盘)。
 */
@Tag("mc")
class CellsTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        booted = MinecraftBoot.boot();
    }

    @AfterAll
    static void restore() {
        MinecraftBoot.restoreBrigadierWords();
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过区域钉桩");
    }

    /** 逐格列出来,拿去和逐格算的集合比。 */
    private static Set<BlockPos> listed(Cells cells) {
        Set<BlockPos> out = new HashSet<>();
        cells.forEach((x, y, z, seen) -> out.add(new BlockPos(x, y, z)));
        return out;
    }

    private static Set<BlockPos> boxSet(int x0, int y0, int z0, int x1, int y1, int z1) {
        Set<BlockPos> out = new HashSet<>();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    out.add(new BlockPos(x, y, z));
                }
            }
        }
        return out;
    }

    // ==================== 构造与查询 ====================

    @Test
    void aBoxAcrossSectionsHasEveryCellAndNothingElse() {
        Cells box = Cells.box(new BlockPos(20, 70, 5), new BlockPos(-3, 60, -17));
        assertEquals(24L * 11 * 23, box.size());
        assertEquals(boxSet(-3, 60, -17, 20, 70, 5), listed(box), "逐格列出与盒子一致");
        assertTrue(box.contains(-3, 60, -17) && box.contains(20, 70, 5) && box.contains(0, 64, 0));
        assertFalse(box.contains(-4, 60, -17) || box.contains(21, 70, 5) || box.contains(0, 71, 0));
        assertEquals(new BoundingBox(-3, 60, -17, 20, 70, 5), box.bounds());
    }

    @Test
    void aSphereIsTheCellsWithinTheRadiusLikeTheWorkArea() {
        BlockPos c = new BlockPos(7, 64, -1);
        Cells ball = Cells.sphere(c, 6);
        Set<BlockPos> want = new HashSet<>();
        for (BlockPos p : boxSet(1, 58, -7, 13, 70, 5)) {
            if (c.distSqr(p) <= 36) {
                want.add(p);
            }
        }
        assertEquals(want, listed(ball));
        assertEquals(1, Cells.sphere(c, 0).size(), "半径 0 就是中心那一格");
    }

    @Test
    void emptyCellsAnswerEveryQueryAndOperation() {
        Cells box = Cells.box(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
        assertTrue(Cells.EMPTY.isEmpty());
        assertNull(Cells.EMPTY.bounds());
        assertNull(Cells.EMPTY.nearest(BlockPos.ZERO));
        assertNull(Cells.EMPTY.center());
        assertTrue(box.minus(box).isEmpty(), "自己减自己是空的");
        assertEquals(Cells.EMPTY, box.minus(box));
        assertTrue(box.intersect(Cells.box(new BlockPos(10, 10, 10), new BlockPos(12, 12, 12))).isEmpty());
        assertEquals(box, box.union(Cells.EMPTY));
        assertEquals(box, Cells.EMPTY.union(box));
        assertTrue(Cells.EMPTY.grow(3).isEmpty());
        assertSame(box, box.grow(0));
    }

    // ==================== 并差交 ====================

    @Test
    void unionMinusAndIntersectMatchCellByCellAcrossSections() {
        Set<BlockPos> a = boxSet(-5, 10, -5, 18, 20, 3);
        Set<BlockPos> b = boxSet(14, 15, -20, 33, 40, 0);
        b.add(new BlockPos(-100, -60, 300));
        Cells ca = Cells.of(a);
        Cells cb = Cells.of(b);

        Set<BlockPos> union = new HashSet<>(a);
        union.addAll(b);
        Set<BlockPos> minus = new HashSet<>(a);
        minus.removeAll(b);
        Set<BlockPos> both = new HashSet<>(a);
        both.retainAll(b);

        assertEquals(union, listed(ca.union(cb)));
        assertEquals(union.size(), ca.union(cb).size());
        assertEquals(minus, listed(ca.minus(cb)));
        assertEquals(minus.size(), ca.minus(cb).size());
        assertEquals(both, listed(ca.intersect(cb)));
        assertEquals(both.size(), ca.intersect(cb).size());
        assertEquals(ca.union(cb), cb.union(ca), "并与次序无关");
    }

    // ==================== 外扩 ====================

    @Test
    void growingByOneFromASectionCornerSpillsIntoTheNeighbours() {
        Cells grown = Cells.point(new BlockPos(15, 63, -16)).grow(1);
        assertEquals(boxSet(14, 62, -17, 16, 64, -15), listed(grown), "立方体 3×3×3,跨进七个相邻小节");
    }

    @Test
    void growingMatchesTheCubeAroundEveryCell() {
        List<BlockPos> scattered = List.of(new BlockPos(0, 0, 0), new BlockPos(-17, 5, 31), new BlockPos(40, -3, -9),
                new BlockPos(41, -3, -9), new BlockPos(15, 15, 15));
        for (int n : new int[]{1, 2, 5, 17}) {
            Set<BlockPos> want = new HashSet<>();
            for (BlockPos p : scattered) {
                want.addAll(boxSet(p.getX() - n, p.getY() - n, p.getZ() - n, p.getX() + n, p.getY() + n, p.getZ() + n));
            }
            assertEquals(want, listed(Cells.of(scattered).grow(n)), "外扩 " + n);
        }
    }

    @Test
    void growingKeepsTheSeenBlocksAndAddsBareCells() {
        BlockPos ore = new BlockPos(3, 12, 3);
        Cells seen = Cells.seen(Map.of(ore, Blocks.IRON_ORE.defaultBlockState()), 40L);
        Cells grown = seen.grow(2);
        assertEquals(125, grown.size());
        assertEquals(new Cells.Seen(Blocks.IRON_ORE.defaultBlockState(), 40L), grown.seenAt(ore));
        assertNull(grown.seenAt(ore.offset(2, 0, 0)), "扩进来的格不附带方块");
    }

    // ==================== 按方块筛 ====================

    @Test
    void filteringLooksAtTheSeenBlocksAndDropsBareCells() {
        Map<BlockPos, BlockState> seen = new LinkedHashMap<>();
        seen.put(new BlockPos(0, 64, 0), Blocks.OAK_LOG.defaultBlockState());
        seen.put(new BlockPos(0, 65, 0), Blocks.BIRCH_LOG.defaultBlockState());
        seen.put(new BlockPos(16, 64, 0), Blocks.STONE.defaultBlockState());
        Cells scanned = Cells.seen(seen, 100L);
        Cells framed = Cells.box(new BlockPos(-2, 63, -2), new BlockPos(2, 66, 2));
        Cells house = scanned.union(framed);

        Cells logs = house.filter(s -> s.is(Blocks.OAK_LOG) || s.is(Blocks.BIRCH_LOG));
        assertEquals(Set.of(new BlockPos(0, 64, 0), new BlockPos(0, 65, 0)), listed(logs),
                "框出来的格不知道是什么,不算原木");
        assertEquals(Blocks.BIRCH_LOG.defaultBlockState(), logs.seenAt(new BlockPos(0, 65, 0)).state());
        assertTrue(framed.filter(s -> true).isEmpty(), "没附带方块的一格也不留");
    }

    @Test
    void whereBothSidesSawACellTheNewerSightStays() {
        BlockPos cell = new BlockPos(5, 5, 5);
        Cells old = Cells.seen(Map.of(cell, Blocks.IRON_ORE.defaultBlockState()), 10L);
        Cells fresh = Cells.seen(Map.of(cell, Blocks.STONE.defaultBlockState()), 20L);
        assertEquals(Blocks.STONE.defaultBlockState(), old.union(fresh).seenAt(cell).state());
        assertEquals(Blocks.STONE.defaultBlockState(), fresh.union(old).seenAt(cell).state());
        assertEquals(Blocks.STONE.defaultBlockState(), old.intersect(fresh).seenAt(cell).state());
        assertEquals(Blocks.IRON_ORE.defaultBlockState(), old.minus(Cells.EMPTY).seenAt(cell).state());
    }

    // ==================== 最近一格与中心 ====================

    @Test
    void nearestAndCenterStayInsideTheCells() {
        Cells ring = Cells.box(new BlockPos(-10, 64, -10), new BlockPos(10, 64, 10))
                .minus(Cells.box(new BlockPos(-9, 64, -9), new BlockPos(9, 64, 9)));
        assertEquals(new BlockPos(10, 64, 3), ring.nearest(new BlockPos(40, 64, 3)));
        assertEquals(new BlockPos(-10, 64, -10), ring.nearest(new BlockPos(-200, -50, -200)));
        BlockPos middle = ring.center();
        assertTrue(ring.contains(middle), "环的中心落在环上: " + middle);
        assertEquals(new BlockPos(5, 6, 7),
                Cells.box(new BlockPos(3, 4, 5), new BlockPos(7, 8, 9)).center(), "盒子的中心是正中那一格");
    }

    /** 最近的几格:由近到远,和逐格排出来的一样;不超过要的格数;格子不够就全给。 */
    @Test
    void theNearestFewCellsComeNearestFirstAndNoMore() {
        Cells box = Cells.box(new BlockPos(-20, 60, -20), new BlockPos(20, 70, 20));
        BlockPos from = new BlockPos(45, 65, 3);
        List<BlockPos> near = box.nearest(from, 30);
        assertEquals(30, near.size());
        List<Double> byCell = listed(box).stream().map(p -> p.distSqr(from)).sorted().limit(30).toList();
        assertEquals(byCell, near.stream().map(p -> p.distSqr(from)).toList(), "与逐格排出来的最近 30 格一样远");
        assertTrue(near.stream().allMatch(box::contains));
        assertEquals(box.nearest(from), near.get(0));
        assertEquals(1, Cells.point(from).nearest(new BlockPos(0, 0, 0), 64).size(), "格子不够就全给");
        assertTrue(Cells.EMPTY.nearest(from, 5).isEmpty());
    }

    // ==================== 大区域 ====================

    @Test
    void aFourMillionCellBaseIsStoredAndOperatedOnBySections() {
        Cells base = Cells.box(new BlockPos(-80, -40, -80), new BlockPos(79, 119, 79));
        assertEquals(4_096_000L, base.size());
        Cells shell = base.minus(Cells.box(new BlockPos(-79, -39, -79), new BlockPos(78, 118, 78)));
        assertEquals(4_096_000L - 158L * 158 * 158, shell.size());
        assertFalse(shell.contains(0, 40, 0));
        assertTrue(shell.contains(-80, 0, 0) && shell.contains(79, 119, 79));
        Cells buffer = base.grow(2);
        assertEquals(164L * 164 * 164, buffer.size());
        assertEquals(new BoundingBox(-82, -42, -82, 81, 121, 81), buffer.bounds());
        assertEquals(shell, shell.union(shell.intersect(base)));
        assertEquals(base, Cells.load(base.save(), BuiltInRegistries.BLOCK.asLookup()));
        List<BlockPos> corner = base.nearest(new BlockPos(500, 200, 500), 64);
        assertEquals(64, corner.size());
        assertEquals(new BlockPos(79, 119, 79), corner.get(0), "四百万格里最近的几格只翻离那一点近的几节");
    }

    // ==================== 存盘 ====================

    @Test
    void savedCellsReadBackTheSameWithTheirSeenBlocks() {
        Map<BlockPos, BlockState> seen = new LinkedHashMap<>();
        seen.put(new BlockPos(1, 2, 3), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState());
        seen.put(new BlockPos(-20, 2, 3), Blocks.OAK_LOG.defaultBlockState()
                .setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS, net.minecraft.core.Direction.Axis.X));
        Cells cells = Cells.seen(seen, 1234L)
                .union(Cells.box(new BlockPos(0, 0, 0), new BlockPos(17, 3, 4)))
                .union(Cells.seen(Map.of(new BlockPos(2, 2, 2), Blocks.GOLD_ORE.defaultBlockState()), 99L));
        Cells back = Cells.load(cells.save(), BuiltInRegistries.BLOCK.asLookup());
        assertEquals(cells, back);
        assertEquals(new Cells.Seen(seen.get(new BlockPos(-20, 2, 3)), 1234L), back.seenAt(new BlockPos(-20, 2, 3)),
                "方块状态连属性一起存");
        assertNull(back.seenAt(new BlockPos(17, 3, 4)));
        assertEquals(Cells.EMPTY, Cells.load(Cells.EMPTY.save(), BuiltInRegistries.BLOCK.asLookup()));
    }
}
