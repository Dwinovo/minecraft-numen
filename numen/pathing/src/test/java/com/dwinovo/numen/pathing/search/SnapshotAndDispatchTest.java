package com.dwinovo.numen.pathing.search;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.spec.RouteSpec;

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

    @Test
    void aSearchOnTheSnapshotStopsAtItsEdge() {
        TestWorld heights = new TestWorld();
        LevelChunkSection[] live = chunk(heights);
        WorldSnapshot snapshot = WorldSnapshot.capture(heights, new WorldBorder(), false,
                (cx, cz) -> cx == 0 && cz == 0 ? live : null, 0, 0, 2);
        Search search = new Search(snapshot, Fixtures.model(RouteSpec.defaults()), new BlockPos(3, Y + 1, 3),
                Goals.at(new BlockPos(40, Y + 1, 3)), Fixtures.BUDGET, Favoring.NONE);
        SearchResult result = Searches.submit(search).join();
        assertEquals(SearchResult.Stop.UNLOADED, result.stop());
        // 走进区块最东一列 x = 15 要看边外那一格伤不伤身,不知道就不走,停在它前面一列
        assertEquals(14, result.route().end().getX());
    }

    @Test
    void searchesAndRouteQueriesGoThroughTheOneDispatcher() {
        TestWorld world = new TestWorld().floor(-2, -2, 12, 2, Y - 1);
        Goal goal = Goals.at(new BlockPos(10, Y, 0));
        Search search = new Search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(0, Y, 0), goal,
                Fixtures.BUDGET, Favoring.NONE);
        Pending<SearchResult> pending = Searches.submit(search);
        assertTrue(pending.join().arrived());
        assertTrue(pending.poll().arrived(), "跑完之后每次交出同一个结论");
        RoutePlanner.Plan plan = Searches.submit(new RoutePlanner.Query(world, Fixtures.model(RouteSpec.defaults()),
                new BlockPos(0, Y, 0), goal, Fixtures.BUDGET, 1)).join();
        assertEquals(1, plan.candidates().size());
    }
}
