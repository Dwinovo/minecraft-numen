package com.dwinovo.numen.pathing.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 认步:身体落在哪个节点,就认到路线上哪一步;不经 {@link Driver}。 */
class TrackerTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    /** 平地上笔直向东的十二步路线,装进一份路线进度。 */
    static Course straightCourse(boolean arrived, double ticksPerStep) {
        TestWorld world = new TestWorld().floor(-4, -4, 40, 4, Y - 1);
        Route route = Fixtures.search(world, Fixtures.model(RouteSpec.defaults()), new BlockPos(0, Y, 0),
                Goals.at(new BlockPos(12, Y, 0))).route();
        assertEquals(12, route.legs().size(), "平地笔直十二步");
        Course course = new Course();
        course.install(route, arrived, m -> ticksPerStep);
        return course;
    }

    @Test
    void aBodyOnTheCurrentStepStaysThere() {
        Course course = straightCourse(true, 5);
        Tracker tracker = new Tracker();
        assertInstanceOf(Tracker.Fix.Here.class, tracker.locate(course, course.current().from(), kind -> true));
        assertInstanceOf(Tracker.Fix.Here.class, tracker.locate(course, course.current().to(), kind -> false),
                "落点还没稳住也不算走完,仍在这一步上");
    }

    @Test
    void aBodySettledOnALaterLandingIsRecognizedThere() {
        Course course = straightCourse(true, 5);
        Tracker tracker = new Tracker();
        Tracker.Fix fix = tracker.locate(course, course.at(2).to(), kind -> true);
        assertEquals(new Tracker.Fix.Advanced(3), fix, "冲劲把她带过了两步,认到第三步的落点");
        assertInstanceOf(Tracker.Fix.Here.class, tracker.locate(course, course.at(2).to(), kind -> false), "没稳住就不认");
    }

    @Test
    void aLandingBeyondTheWindowIsNotRecognized() {
        Course course = straightCourse(true, 5);
        Tracker tracker = new Tracker();
        Tracker.Fix fix = tracker.locate(course, course.at(Tracker.WINDOW + 1).to(), kind -> true);
        assertInstanceOf(Tracker.Fix.Here.class, fix, "窗口之外的节点不认成走完");
    }

    @Test
    void aBodyPushedBackToAnEarlierStartFellBack() {
        Course course = straightCourse(true, 5);
        course.advanceTo(3);
        Tracker tracker = new Tracker();
        Tracker.Fix fix = tracker.locate(course, course.at(1).from(), kind -> true);
        assertEquals(new Tracker.Fix.FellBack(course.at(1)), fix);
    }

    @Test
    void aBodyOffTheRouteForLongEnoughIsLost() {
        Course course = straightCourse(true, 5);
        Tracker tracker = new Tracker();
        BlockPos aside = new BlockPos(3, Y, 3);
        for (int i = 0; i < Tracker.OFF_ROUTE_TICKS; i++) {
            assertInstanceOf(Tracker.Fix.Here.class, tracker.locate(course, aside, kind -> true), "第 " + (i + 1) + " 刻还不算丢");
        }
        assertInstanceOf(Tracker.Fix.Lost.class, tracker.locate(course, aside, kind -> true));
        assertInstanceOf(Tracker.Fix.Here.class, tracker.locate(course, aside, kind -> true), "丢了之后重新计数");
    }

    @Test
    void comingBackOnTheRouteResetsTheStray() {
        Course course = straightCourse(true, 5);
        Tracker tracker = new Tracker();
        BlockPos aside = new BlockPos(3, Y, 3);
        for (int i = 0; i < Tracker.OFF_ROUTE_TICKS; i++) {
            tracker.locate(course, aside, kind -> true);
        }
        tracker.locate(course, course.current().from(), kind -> true);
        assertInstanceOf(Tracker.Fix.Here.class, tracker.locate(course, aside, kind -> true), "回到过路线,计数从头");
    }

    @Test
    void aBodyThatCannotBePlacedSaysNothing() {
        Course course = straightCourse(true, 5);
        assertInstanceOf(Tracker.Fix.Here.class, new Tracker().locate(course, null, kind -> true));
    }

    @Test
    void theCourseSumsTheMinimumTicksOfTheStepsLeft() {
        Course course = straightCourse(false, 7);
        assertEquals(84, course.remainingTicks(), 1e-9);
        course.advanceTo(5);
        assertEquals(49, course.remainingTicks(), 1e-9);
        assertEquals(course.at(6), course.ahead(1).get(0), "紧接着的下一步");
        assertEquals(Stance.Kind.GROUND, course.startStance().kind());
    }
}
