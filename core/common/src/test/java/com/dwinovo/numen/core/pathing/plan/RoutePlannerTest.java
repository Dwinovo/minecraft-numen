package com.dwinovo.numen.core.pathing.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.core.pathing.astar.Favoring;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.astar.PathCalcResult;
import com.dwinovo.numen.core.pathing.bridge.SearchDispatcher;
import com.dwinovo.numen.core.pathing.bridge.SearchHandle;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.pathing.plan.PlanTestSupport.line;
import static com.dwinovo.numen.core.pathing.plan.PlanTestSupport.neverGoal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规划查询的钉桩:惩罚法出备选(第二次搜索的规格给上一条路的格子加了价、候选带的是原规格)、
 * 重叠太多的备选丢弃、一次无路即收工、最多三条。派发器是假的:每次提交按队列交出一条
 * 预先写好的路径。纯 JVM。
 */
class RoutePlannerTest {

    private static final BlockPos START = new BlockPos(0, 64, 0);

    /** 按队列交路径的假派发器;记下每次提交时的规格。 */
    private static final class ScriptedDispatcher implements SearchDispatcher {
        final Deque<NavPath> script = new ArrayDeque<>();
        final List<RouteSpec> specsSeen = new ArrayList<>();
        int submissions;

        @Override
        public SearchHandle submit(BlockPos realStart, BlockPos start, Goal goal, CalculationContext context,
                                   Favoring favoring, long primaryMs, long failureMs) {
            submissions++;
            NavPath path = script.pollFirst();
            PathCalcResult result = path == null
                    ? new PathCalcResult(PathCalcResult.Type.FAILURE)
                    : new PathCalcResult(PathCalcResult.Type.SUCCESS_TO_GOAL, path);
            return new SearchHandle() {
                @Override public PathCalcResult poll() {
                    return result;
                }

                @Override public boolean isDone() {
                    return true;
                }

                @Override public void cancel() {}

                @Override public Optional<NavPath> bestPathSoFar() {
                    return Optional.empty();
                }

                @Override public Optional<NavPath> mostRecentConsidered() {
                    return Optional.empty();
                }
            };
        }
    }

    private static RoutePlanner planner(ScriptedDispatcher dispatcher) {
        // 上下文函数只记规格:假派发器不读上下文
        return new RoutePlanner(dispatcher, spec -> {
            dispatcher.specsSeen.add(spec);
            return null;
        }, null);
    }

    @Test
    void singleRouteNeedsOneSearchAndCarriesTheCallersSpec() {
        ScriptedDispatcher d = new ScriptedDispatcher();
        d.script.add(line(0, 10, 0));
        RouteSpec spec = RouteSpec.defaults().withParkour(true);
        RoutePlanner.Query q = planner(d).plan(START, START, neverGoal(), spec, 1);
        List<RoutePlanner.Candidate> out = q.poll();
        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals(1, d.submissions);
        assertSame(spec, out.get(0).spec());
        assertTrue(out.get(0).reachesGoal());
        assertEquals(10, out.get(0).bill().blocks());
        assertTrue(out.get(0).bill().isEmpty());
        assertTrue(q.isDone());
        assertSame(out, q.poll());
    }

    @Test
    void alternativesPenalizeTheCellsOfEveryEarlierRoute() {
        ScriptedDispatcher d = new ScriptedDispatcher();
        d.script.add(line(0, 10, 0));    // r1: z=0
        d.script.add(line(0, 10, 5));    // r2: z=5,与 r1 不重叠
        d.script.add(line(0, 10, 10));   // r3
        RoutePlanner.Query q = planner(d).plan(START, START, neverGoal(), RouteSpec.defaults(), 3);
        // 假派发器即交即得,但每次 poll 只收一条结论、派下一次
        assertNull(q.poll());
        assertNull(q.poll());
        List<RoutePlanner.Candidate> out = q.poll();
        assertNotNull(out);
        assertEquals(3, out.size());
        assertEquals(3, d.specsSeen.size());

        double extra = (NavSettings.get().routeAlternativePenaltyFactor - 1.0) * ActionCosts.WALK_ONE_BLOCK_COST;
        long onFirst = new BlockPos(3, 64, 0).asLong();
        long onSecond = new BlockPos(3, 64, 5).asLong();
        long elsewhere = new BlockPos(3, 64, 7).asLong();
        // 第一次搜索:没有惩罚
        assertEquals(0.0, d.specsSeen.get(0).positions().stand(onFirst));
        // 第二次:r1 的格子加了价
        assertEquals(extra, d.specsSeen.get(1).positions().stand(onFirst), 1e-9);
        assertEquals(0.0, d.specsSeen.get(1).positions().stand(onSecond));
        // 第三次:r1 与 r2 的格子都加了价,别处不加
        assertEquals(extra, d.specsSeen.get(2).positions().stand(onFirst), 1e-9);
        assertEquals(extra, d.specsSeen.get(2).positions().stand(onSecond), 1e-9);
        assertEquals(0.0, d.specsSeen.get(2).positions().stand(elsewhere));
        // 候选带的是原规格,不带惩罚
        for (RoutePlanner.Candidate c : out) {
            assertTrue(c.spec().positions().isEmpty());
        }
    }

    @Test
    void anAlternativeThatMostlyRetracesIsDropped() {
        ScriptedDispatcher d = new ScriptedDispatcher();
        d.script.add(line(0, 10, 0));    // r1: x 0..9
        d.script.add(line(1, 10, 0));    // 9/10 格与 r1 重合
        RoutePlanner.Query q = planner(d).plan(START, START, neverGoal(), RouteSpec.defaults(), 2);
        assertNull(q.poll());
        List<RoutePlanner.Candidate> out = q.poll();
        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals(2, d.submissions);
    }

    @Test
    void noPathEndsTheQueryEvenWhenMoreWereWanted() {
        ScriptedDispatcher d = new ScriptedDispatcher();
        d.script.add(line(0, 10, 0));
        // 第二次搜索无路
        RoutePlanner.Query q = planner(d).plan(START, START, neverGoal(), RouteSpec.defaults(), 3);
        assertNull(q.poll());
        List<RoutePlanner.Candidate> out = q.poll();
        assertNotNull(out);
        assertEquals(1, out.size());
        assertEquals(2, d.submissions);
    }

    @Test
    void wantedIsClampedToTheMaximum() {
        ScriptedDispatcher d = new ScriptedDispatcher();
        for (int i = 0; i < 6; i++) {
            d.script.add(line(0, 10, i * 5));
        }
        RoutePlanner.Query q = planner(d).plan(START, START, neverGoal(), RouteSpec.defaults(), 9);
        List<RoutePlanner.Candidate> out = null;
        while (out == null) {
            out = q.poll();
        }
        assertEquals(RoutePlanner.MAX_ALTERNATIVES, out.size());
        assertEquals(RoutePlanner.MAX_ALTERNATIVES, d.submissions);
    }

    @Test
    void firstSearchWithoutPathYieldsNoCandidates() {
        ScriptedDispatcher d = new ScriptedDispatcher();
        RoutePlanner.Query q = planner(d).plan(START, START, neverGoal(), RouteSpec.defaults(), 2);
        List<RoutePlanner.Candidate> out = q.poll();
        assertNotNull(out);
        assertTrue(out.isEmpty());
        assertEquals(1, d.submissions);
    }
}
