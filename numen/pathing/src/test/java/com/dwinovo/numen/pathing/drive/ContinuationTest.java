package com.dwinovo.numen.pathing.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 接续:何时提前搜下一段、末尾几步何时能开始走、怎么拼;不经 {@link Driver}。 */
class ContinuationTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 接在 {@link TrackerTest#straightCourse} 那条路线终点后面的下一段(再向东八步)。 */
    private static Route nextSegment(Course course) {
        TestWorld world = new TestWorld().floor(-4, -4, 40, 4, Y - 1);
        BlockPos from = course.end();
        return Fixtures.search(world, Fixtures.model(RouteSpec.defaults()), from, Goals.at(from.offset(8, 0, 0))).route();
    }

    @Test
    void theNextSegmentIsSearchedWhenTheMinimumTicksLeftDropBelowTheLookahead() {
        // 十二步,每步最短 10 刻:剩 120 刻时还早,剩 90 刻(走了三步)就该搜
        Course course = TrackerTest.straightCourse(false, 10);
        Continuation continuation = new Continuation();
        assertFalse(continuation.due(course), "剩 120 刻 ≥ " + Continuation.LOOKAHEAD_TICKS);
        course.advanceTo(3);
        assertTrue(continuation.due(course), "剩 90 刻 < " + Continuation.LOOKAHEAD_TICKS);
    }

    @Test
    void theTimingFollowsTheMinimumTicksNotTheStepCount() {
        Course quick = TrackerTest.straightCourse(false, 2);
        assertTrue(new Continuation().due(quick), "十二步每步 2 刻,剩 24 刻,一开始就该搜");
    }

    @Test
    void aRouteThatReachesTheGoalOrHasNoRouteAfterIsNeverSearchedPast() {
        Continuation continuation = new Continuation();
        assertFalse(continuation.due(TrackerTest.straightCourse(true, 2)), "已到目标");
        Course course = TrackerTest.straightCourse(false, 2);
        continuation.splice(course, null, false, m -> 2);
        assertFalse(continuation.due(course), "接不上之后不再提前搜");
        assertTrue(continuation.failed());
    }

    @Test
    void theLastStepsWaitForWhatComesAfter() {
        Course course = TrackerTest.straightCourse(false, 10);
        Continuation continuation = new Continuation();
        int firstUnsettled = course.size() - Continuation.TAIL;
        course.advanceTo(firstUnsettled - 1);
        assertTrue(continuation.settled(course), "后面还有 " + Continuation.TAIL + " 步以上,放心走");
        course.advanceTo(firstUnsettled);
        assertFalse(continuation.settled(course), "再往后就得等接续段");
        course.advanceTo(course.size());
        assertFalse(continuation.settled(course), "走完了而后面没定下来,更要等");
    }

    @Test
    void whatComesAfterIsSettledOnceTheRouteIsCompleteOrCannotContinue() {
        Course tail = TrackerTest.straightCourse(true, 10);
        tail.advanceTo(tail.size() - 1);
        assertTrue(new Continuation().settled(tail), "路线到了目标,末尾几步照走");
        Course open = TrackerTest.straightCourse(false, 10);
        open.advanceTo(open.size() - 1);
        Continuation continuation = new Continuation();
        assertFalse(continuation.settled(open));
        continuation.splice(open, null, false, m -> 10);
        assertTrue(continuation.settled(open), "确认接不上,末尾几步照走(到终点再从脚下搜)");
    }

    @Test
    void aSplicedSegmentExtendsTheCourseAndSettlesTheTail() {
        Course course = TrackerTest.straightCourse(false, 10);
        course.advanceTo(course.size() - 1);
        Continuation continuation = new Continuation();
        assertFalse(continuation.settled(course));
        Route next = nextSegment(course);
        int before = course.size();
        assertTrue(continuation.splice(course, next, true, m -> 10));
        assertEquals(before + next.legs().size(), course.size());
        assertTrue(course.complete(), "接上的一段到了目标");
        assertEquals(next.end(), course.end());
        assertTrue(continuation.settled(course));
    }

    @Test
    void aSegmentThatDoesNotStartWhereTheRouteEndsIsRefused() {
        Course course = TrackerTest.straightCourse(false, 10);
        Continuation continuation = new Continuation();
        TestWorld world = new TestWorld().floor(-4, -4, 40, 4, Y - 1);
        Route elsewhere = Fixtures.search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(0, Y, 2),
                Goals.at(new BlockPos(6, Y, 2))).route();
        int before = course.size();
        assertFalse(continuation.splice(course, elsewhere, true, m -> 10));
        assertEquals(before, course.size());
        assertTrue(continuation.failed());
        continuation.reset();
        assertFalse(continuation.failed(), "换了新路线重新开始");
    }
}
