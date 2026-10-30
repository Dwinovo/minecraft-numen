package com.dwinovo.numen.pathing.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.search.SearchView;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 一趟的规划那一半({@link Trip}):不带身体,身体与世界由测试经 {@link Trip.Ground} 给。 */
class TripTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 身体站在可变的一格上的地面:测试在搜索飞着的时候挪它。 */
    private static final class Standing implements Trip.Ground {

        private final TestWorld world;
        private final CostModel model;
        BlockPos at;

        Standing(TestWorld world, RouteSpec spec, BlockPos at) {
            this.world = world;
            this.model = Fixtures.model(spec);
            this.at = at;
        }

        @Override
        public SearchView snapshot(BlockPos from) {
            return world;
        }

        @Override
        public CostModel model() {
            return model;
        }

        @Override
        public Trip.Standing standing() {
            Stance stance = Stance.at(world, model.body().stats(), at);
            return stance == null ? null : new Trip.Standing(at, stance);
        }

        @Override
        public String body() {
            return at.toShortString();
        }
    }

    private static Trip trip(Standing ground, Goal goal) {
        return new Trip(ground, "test", new Journal(), goal, RouteSpec.defaults(), 0, SearchClock.STEADY);
    }

    private static Trip.Event arrive(Trip trip) throws InterruptedException {
        while (!trip.ready()) {
            Thread.sleep(0, 100_000);
        }
        return trip.poll();
    }

    /**
     * 去处是一片压力板(出厂规格排除机关格,搜索永远走不进去),身体在搜索飞着的时候被推进了那一片:这次搜索回来说没路,可她已经站在
     * 去处里了——收下的是一条"已经到了"的空路线,不收场。同样的搜索回来时她不在去处里,就照实收场。
     */
    @Test
    void aFailingSearchThatReturnsAfterTheBodySlidInIsArrival() throws InterruptedException {
        TestWorld world = new TestWorld().floor(0, 0, 20, 10, Y - 1);
        world.fill(10, Y, 3, 14, Y, 7, Blocks.STONE_PRESSURE_PLATE.defaultBlockState());
        Goal goal = Goals.within(Goals.at(new BlockPos(12, Y, 5)), 0, 2);
        BlockPos start = new BlockPos(8, Y, 5);

        Standing slid = new Standing(world, RouteSpec.defaults(), start);
        Trip arrives = trip(slid, goal);
        arrives.fromBody(start);
        while (!arrives.ready()) {
            Thread.sleep(0, 100_000);
        }
        slid.at = new BlockPos(12, Y, 5);
        assertEquals(Trip.Event.EXTENDED, arrives.poll());
        assertNull(arrives.halt());
        assertTrue(arrives.course().complete() && arrives.course().finished(), "该是一条到了的空路线");

        Standing stayed = new Standing(world, RouteSpec.defaults(), start);
        Trip gives = trip(stayed, goal);
        gives.fromBody(start);
        assertEquals(Trip.Event.HALTED, arrive(gives));
        assertNotNull(gives.halt());
    }
}
