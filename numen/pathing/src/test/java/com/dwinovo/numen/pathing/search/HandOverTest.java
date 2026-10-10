package com.dwinovo.numen.pathing.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 交出策略:交哪一截、什么时候交;不经 {@code Driver}。 */
class HandOverTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 平走八格,再往下掉三格(回不了头),再平走八格。 */
    private static TestWorld cliff() {
        return new TestWorld().floor(-2, -4, 8, 4, Y - 1).floor(9, -4, 20, 4, Y - 4);
    }

    private static CostModel loose() {
        return Fixtures.model(RouteSpec.defaults().edit().maxFallHeightNoWater(10).build());
    }

    private static Route full(TestWorld world, BlockPos from, BlockPos to) {
        SearchResult result = Fixtures.search(world, loose(), from, Goals.at(to));
        assertTrue(result.arrived(), result.stop().toString());
        return result.route();
    }

    @Test
    void aCutStopsAtTheLastNodeThatCanBeGoneBackFrom() {
        Route route = full(cliff(), new BlockPos(0, Y, 0), new BlockPos(18, Y - 3, 0));
        int fall = -1;
        for (int i = 0; i < route.legs().size(); i++) {
            if (route.legs().get(i).maneuver().kind() == MoveKind.FALL) {
                fall = i;
            }
        }
        assertTrue(fall > 5, "路线里有一步回不了头的下落,前面够远");
        Route cut = HandOver.cut(route);
        assertNotNull(cut);
        assertEquals(fall, cut.legs().size(), "截到下落之前");
        assertEquals(route.legs().get(fall).maneuver().from(), cut.end(), "终点是下落那一步的起点");
    }

    @Test
    void aRouteWhoseFirstStepCannotBeUndoneHasNothingToHandOver() {
        // 站在崖边,第一步就是三格的下落
        Route route = full(cliff(), new BlockPos(8, Y, 0), new BlockPos(18, Y - 3, 0));
        assertEquals(MoveKind.FALL, route.legs().get(0).maneuver().kind());
        assertNull(HandOver.cut(route));
        assertNull(HandOver.pick(List.of(route)));
    }

    @Test
    void aCutThatEndsTooCloseToTheStartIsNotHandedOver() {
        // 离崖边只有三格:截完离起点不到 MIN_PARTIAL
        Route route = full(cliff(), new BlockPos(5, Y, 0), new BlockPos(18, Y - 3, 0));
        assertTrue(route.legs().stream().anyMatch(l -> l.maneuver().kind() == MoveKind.FALL));
        assertNull(HandOver.cut(route));
    }

    @Test
    void aCutIsTheWholeRouteWhenEveryStepCanBeUndone() {
        TestWorld flat = new TestWorld().floor(-2, -4, 20, 4, Y - 1);
        Route route = full(flat, new BlockPos(0, Y, 0), new BlockPos(12, Y, 0));
        assertEquals(route, HandOver.cut(route));
    }

    @Test
    void aSearchThatCanOnlyHandOverAnIrreversibleStretchKeepsSearching() {
        // 起点就在崖边,一条路也要先跳下去:预算用完时没有可交的半程,不交一截回不了头的路
        SearchResult result = Fixtures.search(cliff(), loose(), new BlockPos(8, Y, 0), Goals.at(new BlockPos(18, Y - 3, 0)), 3);
        assertEquals(SearchResult.Stop.BUDGET, result.stop());
        assertNull(result.route());
    }

    @Test
    void theHandedOverStretchAtTheEdgeOfTheLoadedChunksStopsWhereItCanBeGoneBackFrom() {
        // 原点所在区块东到 x = 15。崖在 x = 11(往下三格),区块边外是什么不知道:走不进去
        TestWorld world = new TestWorld().floor(0, 0, 10, 4, Y - 1).floor(11, 0, 40, 4, Y - 4).loadedWithin(0);
        SearchResult edge = Fixtures.search(world, loose(), new BlockPos(0, Y, 1), Goals.at(new BlockPos(38, Y - 3, 1)));
        assertEquals(SearchResult.Stop.UNLOADED, edge.stop());
        assertNotNull(edge.route());
        BlockPos end = edge.route().end();
        assertEquals(Y, end.getY(), "没有走下那道回不了头的崖");
        assertEquals(10, end.getX(), "停在崖沿上,回得了头的最后一个节点");
        // 区块加载了:从那里接着搜,到目标
        TestWorld loaded = new TestWorld().floor(0, 0, 10, 4, Y - 1).floor(11, 0, 40, 4, Y - 4);
        SearchResult onward = Fixtures.search(loaded, loose(), end, Goals.at(new BlockPos(38, Y - 3, 1)));
        assertTrue(onward.arrived());
    }

    @Test
    void anUnloadedColumnIsNeverWalkedIntoAndTheSnapshotReadsItAsAir() {
        // 现状:没加载的列读出来是空气,可那是读不到,不是测量——读到它的前提不成立,这一步不走,结论记成"未加载"
        TestWorld world = new TestWorld().floor(0, 0, 40, 3, Y - 1).loadedWithin(0);
        SearchResult result = Fixtures.search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(1, Y, 1), Goals.at(new BlockPos(40, Y, 1)));
        assertEquals(SearchResult.Stop.UNLOADED, result.stop());
        assertTrue(result.route().end().getX() <= 14, "停在加载区块的边上:" + result.route().end());
    }
}
