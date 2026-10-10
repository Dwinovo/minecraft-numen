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
import com.dwinovo.numen.pathing.plan.Stance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
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

    /** 先平走三步再爬一架八格高的梯子到顶:没到目标的半程路线。 */
    private static Course ladderCourse() {
        TestWorld world = new TestWorld().floor(-4, -4, 12, 4, Y - 1).fill(4, Y, -1, 4, Y + 8, 1, Blocks.STONE.defaultBlockState());
        for (int y = Y; y < Y + 8; y++) {
            world.set(3, y, 0, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST));
        }
        Route route = Fixtures.search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(0, Y, 0),
                Goals.at(new BlockPos(3, Y + 7, 0))).route();
        Course course = new Course();
        course.install(route, false, m -> 10);
        return course;
    }

    @Test
    void theBodyNeverWaitsForTheNextSegmentOnALadder() {
        Course course = ladderCourse();
        assertTrue(course.size() > Continuation.TAIL + 3, "路线够长,末尾那几步落在梯子上");
        long climbing = java.util.stream.IntStream.range(0, course.size()).filter(i -> course.at(i).start().kind() == Stance.Kind.CLIMBING).count();
        assertTrue(climbing >= Continuation.TAIL, "末尾那几步的起点全是攀着的");
        Continuation continuation = new Continuation();
        int cur = 0;
        while (continuation.settled(course)) {
            cur++;
            course.advanceTo(cur);
        }
        assertTrue(cur < course.size() - Continuation.TAIL, "比不退让早停下");
        assertTrue(course.at(cur).start().kind() != Stance.Kind.CLIMBING, "停下来等的这一步,起点待得住:" + course.at(cur).start());
    }

    @Test
    void aRouteThatStartsOnALadderCanOnlyWaitThere() {
        Course course = ladderCourse();
        course.advanceTo(course.size() - Continuation.TAIL);
        assertFalse(new Continuation().settled(course), "没处可退,就在这一步的起点上等");
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
