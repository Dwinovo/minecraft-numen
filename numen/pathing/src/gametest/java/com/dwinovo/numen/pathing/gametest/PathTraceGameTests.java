package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.Trace;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * 寻路把一趟"怎么收的场"报给宿主：结局是哪一类、卡在哪一格、按原因分的重搜次数。
 *
 * <p>这些以前只在日志里出现一次，说完就没了——要复盘"她为什么一动不动"只能靠复现。
 */
public class PathTraceGameTests {

    /** 记下每一条的端口。 */
    static final class Recorder implements Trace {
        final List<PathEvent> events = new ArrayList<>();

        @Override
        public void path(PathEvent event) {
            events.add(event);
        }
    }

    /** 四面与头顶都是基岩：搜完没有路，收场那条要带着诊断出来的那一类与卡住的那一格。 */
    @GameTest(template = Trial.ARENA, timeoutTicks = 600)
    public static void aSealedBoxSheCannotLeaveIsReportedOnGiveUp(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 0, 4, 8, 4, 8, Blocks.BEDROCK);
        t.fill(5, 1, 5, 7, 3, 7, Blocks.AIR);
        Recorder trace = new Recorder();
        t.trace = trace;
        TestBody body = t.body(6, 1, 6);

        t.go(body, NavRequest.to(Goals.at(t.at(15, 1, 15)), RouteSpec.defaults())).within(400)
                .fails(Outcome.NoRoute.class)
                .then(run -> {
                    helper.assertTrue(trace.events.size() == 1, "一趟报一条,却报了 " + trace.events.size());
                    Trace.PathEvent e = trace.events.get(0);
                    helper.assertTrue("NoRoute".equals(e.outcome()),
                            "收场那条该说诊断出的是哪一类: " + e);
                    helper.assertTrue(e.cell() != null, "没有路却说不出卡在哪一格: " + e);
                    helper.assertTrue(e.at() != null, "收场那条没有身体的坐标: " + e);
                    helper.assertTrue(e.ticks() > 0, "收场那条没记用了几刻: " + e);
                });
    }

    /** 一条走得通的路：到了也报一条，说的是到了，不是收场。 */
    @GameTest(template = Trial.ARENA, timeoutTicks = 600)
    public static void aWalkThatWorksReportsArrived(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        Recorder trace = new Recorder();
        t.trace = trace;
        TestBody body = t.body(2, 1, 12);

        t.go(body, NavRequest.to(Goals.at(t.at(10, 1, 12)), RouteSpec.defaults())).within(400)
                .arrives()
                .then(run -> {
                    helper.assertTrue(trace.events.size() == 1, "一趟报一条,却报了 " + trace.events.size());
                    Trace.PathEvent e = trace.events.get(0);
                    helper.assertTrue("Arrived".equals(e.outcome()), "走到了却报 " + e.outcome() + ": " + e);
                    helper.assertTrue(e.steps() > 0, "走到了却说一步没走: " + e);
                });
    }

    /** 没人听的时候什么都不发生：{@link Trace#NONE} 是默认。 */
    @GameTest(template = Trial.ARENA, timeoutTicks = 600)
    public static void withoutAListenerTheWalkIsUnaffected(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(2, 1, 12);

        t.go(body, NavRequest.to(Goals.at(t.at(10, 1, 12)), RouteSpec.defaults())).within(400).arrives();
    }
}
