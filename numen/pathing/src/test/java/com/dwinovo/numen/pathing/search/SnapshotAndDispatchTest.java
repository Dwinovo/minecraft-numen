package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 搜索视图是派发那一刻的拷贝,之后世界怎么变都不影响它;搜索经唯一的派发口在工作线程上跑。 */
class SnapshotAndDispatchTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 原点所在区块:y=64 那一层铺满石头,(1, 65, 1) 另放一块。 */
    private static LevelChunkSection[] chunk(TestWorld heights) {
        LevelChunkSection[] sections = new LevelChunkSection[heights.getSectionsCount()];
        LevelChunkSection section = new LevelChunkSection(new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES), null);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                section.setBlockState(x, Y & 15, z, Blocks.STONE.defaultBlockState());
            }
        }
        section.setBlockState(1, (Y + 1) & 15, 1, Blocks.STONE.defaultBlockState());
        sections[heights.getSectionIndex(Y)] = section;
        return sections;
    }

    @Test
    void theSnapshotIsACopyTakenAtDispatch() {
        TestWorld heights = new TestWorld();
        LevelChunkSection[] live = chunk(heights);
        WorldBorder border = new WorldBorder();
        border.setSize(1000);
        WorldSnapshot snapshot = WorldSnapshot.capture(heights, border, false, (cx, cz) -> cx == 0 && cz == 0 ? live : null, 0, 0, 1);

        BlockPos marked = new BlockPos(1, Y + 1, 1);
        assertEquals(Blocks.STONE, snapshot.getBlockState(marked).getBlock());
        live[heights.getSectionIndex(Y)].setBlockState(1, (Y + 1) & 15, 1, Blocks.DIRT.defaultBlockState());
        border.setSize(10);
        assertEquals(Blocks.STONE, snapshot.getBlockState(marked).getBlock(), "拷下来之后世界再变,快照不变");
        assertEquals(1000, snapshot.border().getSize());

        assertTrue(snapshot.isLoaded(5, 5));
        assertFalse(snapshot.isLoaded(20, 5), "没加载的邻区块");
        assertFalse(snapshot.isLoaded(100, 5), "拷贝范围之外");
        BlockState air = snapshot.getBlockState(new BlockPos(20, Y, 5));
        assertTrue(air.isAir(), "快照之外读出来是空气");
    }

    /** 按状态逐格找:找到的每一格带着它的层;没有这种状态的方块段不逐格找。 */
    @Test
    void theSnapshotFindsTheBlocksAStateHasWhereverTheyAre() {
        TestWorld heights = new TestWorld();
        LevelChunkSection[] live = chunk(heights);
        live[heights.getSectionIndex(Y)].setBlockState(2, (Y + 1) & 15, 2, Blocks.OAK_STAIRS.defaultBlockState());
        WorldSnapshot snapshot = WorldSnapshot.capture(heights, new WorldBorder(), false, (cx, cz) -> cx == 0 && cz == 0 ? live : null, 0, 0, 1);
        java.util.List<Integer> levels = new java.util.ArrayList<>();
        snapshot.forEachBlock(snapshot.getMinBuildHeight(), snapshot.getMinBuildHeight() + snapshot.getHeight(), state -> state.is(Blocks.OAK_STAIRS), (state, y) -> levels.add(y));
        assertEquals(java.util.List.of(Y + 1), levels);
        java.util.List<Integer> inRange = new java.util.ArrayList<>();
        snapshot.forEachBlock(Y + 1, Y + 2, state -> state.is(Blocks.OAK_STAIRS), (state, y) -> inRange.add(y));
        assertEquals(java.util.List.of(Y + 1), inRange, "范围正好含着这一层");
        java.util.List<Integer> outside = new java.util.ArrayList<>();
        snapshot.forEachBlock(Y + 2, Y + 40, state -> state.is(Blocks.OAK_STAIRS), (state, y) -> outside.add(y));
        snapshot.forEachBlock(Y - 40, Y + 1, state -> state.is(Blocks.OAK_STAIRS), (state, y) -> outside.add(y));
        assertTrue(outside.isEmpty(), "范围之外的层不找");
        java.util.List<Integer> none = new java.util.ArrayList<>();
        snapshot.forEachBlock(snapshot.getMinBuildHeight(), snapshot.getMinBuildHeight() + snapshot.getHeight(), state -> state.is(Blocks.WATER), (state, y) -> none.add(y));
        assertTrue(none.isEmpty());
    }

    /** 读一格的结果与拷下来的方块段一模一样:每个区段、每一层、每一格,重复读也一样;建筑高度之外与没加载的区块是空气。 */
    @Test
    void everyCellReadsBackWhatWasCopied() {
        TestWorld heights = new TestWorld();
        LevelChunkSection[] live = chunk(heights);
        java.util.Random random = new java.util.Random(7);
        BlockState[] some = {Blocks.DIRT.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState(), Blocks.WATER.defaultBlockState()};
        for (int i = 0; i < 300; i++) {
            live[heights.getSectionIndex(Y)].setBlockState(random.nextInt(16), random.nextInt(16), random.nextInt(16),
                    some[random.nextInt(some.length)]);
        }
        WorldSnapshot snapshot = WorldSnapshot.capture(heights, new WorldBorder(), false, (cx, cz) -> cx == 0 && cz == 0 ? live : null, 0, 0, 1);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int pass = 0; pass < 2; pass++) {
            for (int y = heights.getMinBuildHeight(); y < heights.getMinBuildHeight() + heights.getHeight(); y++) {
                LevelChunkSection section = live[heights.getSectionIndex(y)];
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState expected = section == null ? air : section.getBlockState(x, y & 15, z);
                        assertEquals(expected, snapshot.getBlockState(new BlockPos(x, y, z)), x + "," + y + "," + z);
                    }
                }
            }
        }
        assertTrue(snapshot.getBlockState(new BlockPos(3, heights.getMinBuildHeight() - 1, 3)).isAir());
        assertTrue(snapshot.getBlockState(new BlockPos(3, heights.getMinBuildHeight() + heights.getHeight(), 3)).isAir());
        assertTrue(snapshot.getBlockState(new BlockPos(-1, Y, 3)).isAir(), "没加载的区块");
    }

    /** 方块段里状态多到用上全局调色板时,调色板不问谓词、直接答"也许有",照样逐格找得全。 */
    @Test
    void aSectionWithAGlobalPaletteStillGivesUpItsBlocks() {
        TestWorld heights = new TestWorld();
        LevelChunkSection[] live = chunk(heights);
        List<BlockState> states = new ArrayList<>(Block.BLOCK_STATE_REGISTRY.size());
        Block.BLOCK_STATE_REGISTRY.forEach(states::add);
        LevelChunkSection section = live[heights.getSectionIndex(Y)];
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            BlockState state = states.get((int) ((long) i * 7919 % states.size()));
            section.setBlockState(i & 15, (i >> 8) & 15, (i >> 4) & 15, state);
        }
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (section.getBlockState(x, y, z).getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
                        expected.add(((heights.getSectionIndex(Y) + heights.getMinSection()) << 4) + y);
                    }
                }
            }
        }
        assertTrue(expected.size() > 20, "场景里有这种方块");
        WorldSnapshot snapshot = WorldSnapshot.capture(heights, new WorldBorder(), false, (cx, cz) -> cx == 0 && cz == 0 ? live : null, 0, 0, 1);
        List<Integer> levels = new ArrayList<>();
        snapshot.forEachBlock(snapshot.getMinBuildHeight(), snapshot.getMinBuildHeight() + snapshot.getHeight(), state -> state.getBlock() instanceof net.minecraft.world.level.block.StairBlock, (state, y) -> levels.add(y));
        assertEquals(expected, levels);
    }

    /** 叫停时还在队里排着的搜索直接从队里拿走,它握着的世界快照不必等到有线程空出来才放掉。 */
    @Test
    void aCancelledSearchStillInTheQueueLetsGoOfItsInputs() throws InterruptedException {
        // 把工作线程占满:池子大小不超过处理器数,这么多件不收手的活一定排满它
        List<Pending<Object>> blockers = new ArrayList<>();
        for (int i = 0; i < Runtime.getRuntime().availableProcessors() + 2; i++) {
            blockers.add(Searches.submit(cancelled -> {
                while (!cancelled.getAsBoolean()) {
                    Thread.onSpinWait();
                }
                return null;
            }));
        }
        try {
            TestWorld world = new TestWorld().floor(-2, -2, 12, 2, Y - 1);
            java.lang.ref.WeakReference<TestWorld> seen = new java.lang.ref.WeakReference<>(world);
            Pending<SearchResult> waiting = Searches.submit(new Search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(0, Y, 0),
                    Goals.at(new BlockPos(10, Y, 0)), Fixtures.BUDGET, Favoring.NONE), Searches.Urgency.NOW);
            waiting.cancel();
            waiting = null;
            world = null;
            for (int i = 0; i < 100 && seen.get() != null; i++) {
                System.gc();
                Thread.sleep(20);
            }
            assertEquals(null, seen.get(), "叫停了的搜索不该还握着它的快照");
        } finally {
            blockers.forEach(Pending::cancel);
        }
    }

    @Test
    void aSearchOnTheSnapshotStopsAtItsEdge() {
        TestWorld heights = new TestWorld();
        LevelChunkSection[] live = chunk(heights);
        WorldSnapshot snapshot = WorldSnapshot.capture(heights, new WorldBorder(), false,
                (cx, cz) -> cx == 0 && cz == 0 ? live : null, 0, 0, 2);
        Search search = new Search(snapshot, Fixtures.model(RouteSpec.defaults()), new BlockPos(3, Y + 1, 3),
                Goals.at(new BlockPos(40, Y + 1, 3)), Fixtures.BUDGET, Favoring.NONE);
        SearchResult result = Fixtures.await(Searches.submit(search, Searches.Urgency.NOW));
        assertEquals(SearchResult.Stop.UNLOADED, result.stop());
        // 走进区块最东一列 x = 15 要看边外那一格伤不伤身,不知道就不走,停在它前面一列
        assertEquals(14, result.route().end().getX());
    }

    @Test
    void searchesGoThroughTheOneDispatcher() {
        TestWorld world = new TestWorld().floor(-2, -2, 12, 2, Y - 1);
        Goal goal = Goals.at(new BlockPos(10, Y, 0));
        Search search = new Search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(0, Y, 0), goal,
                Fixtures.BUDGET, Favoring.NONE);
        Pending<SearchResult> pending = Searches.submit(search, Searches.Urgency.NOW);
        assertTrue(Fixtures.await(pending).arrived());
        assertTrue(pending.poll().arrived(), "跑完之后每次交出同一个结论");
    }
}
