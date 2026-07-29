package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.activeAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.task.TaskChain;

class OwnerFollowSchedulerSequenceTest {

    @Test
    void completeWinnerSequenceInterruptsBeforeTickAndResumesFreshNavigation() {
        List<String> events = new ArrayList<>();
        OwnerFollowChainTestHarness follow =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        Stage7BExecutionTestSupport.MutableChain speaking =
                new Stage7BExecutionTestSupport.MutableChain("speaking", events);
        Stage7BExecutionTestSupport.MutableChain llm =
                new Stage7BExecutionTestSupport.MutableChain("llm", events);
        Stage7BExecutionTestSupport.MutableChain survival =
                new Stage7BExecutionTestSupport.MutableChain("survival", events);
        List<TaskChain> chains =
                List.of(follow.chain, speaking, llm, survival);
        Stage7BExecutionTestSupport.SchedulerDriver scheduler =
                new Stage7BExecutionTestSupport.SchedulerDriver();

        assertSame(follow.chain, scheduler.step(chains));
        OwnerFollowChainTestHarness.FakeNavigation first =
                follow.factory.last();
        first.onStop = () -> events.add("follow.stop");

        speaking.priority(-1.0F);
        assertSame(speaking, scheduler.step(chains));

        llm.priority(0.0F);
        assertSame(llm, scheduler.step(chains));
        assertSame(llm, scheduler.step(chains));

        survival.priority(10.0F);
        assertSame(survival, scheduler.step(chains));

        speaking.priority(Float.NEGATIVE_INFINITY);
        llm.priority(Float.NEGATIVE_INFINITY);
        survival.priority(Float.NEGATIVE_INFINITY);
        follow.access.snapshot = OwnerFollowChainTestHarness.snapshot(
                FollowState.defaults(),
                true, true, true, true, true, 8.0, 15L);
        assertNull(scheduler.step(chains));
        assertNull(scheduler.running());

        follow.access.snapshot = activeAt(8.0, 16L);
        assertSame(follow.chain, scheduler.step(chains));
        OwnerFollowChainTestHarness.FakeNavigation resumed =
                follow.factory.last();

        assertEquals(List.of(
                "follow.stop",
                "speaking.tick",
                "speaking.interrupt",
                "llm.tick",
                "llm.tick",
                "llm.interrupt",
                "survival.tick",
                "survival.interrupt"), events);
        assertEquals(1, speaking.ticks());
        assertEquals(1, speaking.interrupts());
        assertEquals(2, llm.ticks());
        assertEquals(1, llm.interrupts());
        assertEquals(1, survival.ticks());
        assertEquals(1, survival.interrupts());
        assertEquals(6, scheduler.winnerTicks());
        assertEquals(1, first.ticks);
        assertEquals(1, first.stops);
        assertEquals(1, resumed.ticks);
        assertNotSame(first, resumed);
        assertEquals(2, follow.factory.created.size());
    }
}
