package com.dwinovo.numen.pathing.drive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.pathing.plan.Maneuver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.dwinovo.numen.pathing.Vanilla;

/** 复原:同一步走不下去几次收场、走成了的步一笔勾销、半程路线不再变近就收场;不经 {@link Driver}。 */
class RecoveryTest {

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static Blockage because(Maneuver m) {
        return new Blockage(m.to(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), m.kind(), null,
                Blockage.Hitch.STUCK);
    }

    @Test
    void theThirdFailureOfTheSameStepGivesUp() {
        Maneuver step = TrackerTest.straightCourse(true, 5).at(0);
        Recovery recovery = new Recovery();
        Recovery.Verdict first = recovery.failed(step, because(step));
        assertEquals(1, first.count());
        assertFalse(first.giveUp());
        assertFalse(recovery.failed(step, because(step)).giveUp());
        Recovery.Verdict third = recovery.failed(step, because(step));
        assertEquals(Recovery.STRIKES, third.count());
        assertTrue(third.giveUp());
    }

    @Test
    void differentStepsCountSeparately() {
        Course course = TrackerTest.straightCourse(true, 5);
        Recovery recovery = new Recovery();
        for (int i = 0; i < Recovery.STRIKES - 1; i++) {
            recovery.failed(course.at(0), because(course.at(0)));
        }
        assertFalse(recovery.failed(course.at(1), because(course.at(1))).giveUp(), "另一步从头数");
    }

    @Test
    void aStepThatWasWalkedIsForgiven() {
        Maneuver step = TrackerTest.straightCourse(true, 5).at(0);
        Recovery recovery = new Recovery();
        recovery.failed(step, because(step));
        recovery.failed(step, because(step));
        recovery.walked(step);
        assertNull(recovery.lastBlockage(), "走成了一步,上一次没走成的原因不再作数");
        assertEquals(1, recovery.failed(step, because(step)).count(), "勾销之后从头数");
    }

    @Test
    void theLastBlockageIsKeptUntilAStepIsWalked() {
        Maneuver step = TrackerTest.straightCourse(true, 5).at(0);
        Recovery recovery = new Recovery();
        Blockage why = because(step);
        recovery.failed(step, why);
        assertEquals(why, recovery.lastBlockage());
    }

    @Test
    void partialRoutesThatDoNotGetCloserEventuallyStop() {
        Recovery recovery = new Recovery();
        assertFalse(recovery.stalled(100), "第一段总算数更近");
        assertFalse(recovery.stalled(60), "离目标更近就重新数");
        for (int i = 0; i < Recovery.STALE_PARTIALS - 1; i++) {
            assertFalse(recovery.stalled(60), "没更近,第 " + (i + 1) + " 段");
        }
        assertTrue(recovery.stalled(60), "连续 " + Recovery.STALE_PARTIALS + " 段没更近就收场");
    }

    @Test
    void gettingCloserByLessThanOneDoesNotCount() {
        Recovery recovery = new Recovery();
        recovery.stalled(100);
        recovery.stalled(99.5);
        recovery.stalled(99.2);
        assertTrue(recovery.stalled(99.0), "每段近不到一格不算更近");
    }

    @Test
    void aNewGoalStartsTheComparisonOver() {
        Recovery recovery = new Recovery();
        recovery.stalled(10);
        recovery.stalled(10);
        recovery.stalled(10);
        recovery.retarget();
        assertFalse(recovery.stalled(500), "目标换了,远不远重新比");
    }
}
