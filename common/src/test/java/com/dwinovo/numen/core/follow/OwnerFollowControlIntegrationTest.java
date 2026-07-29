package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OwnerFollowControlIntegrationTest {

    @Test
    void controlsOnlyReleaseImmediatelyWhileSchedulerCreatesFreshNavigation() {
        UUID uuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        OwnerFollowChainTestHarness harness =
                new OwnerFollowChainTestHarness(OwnerFollowChainTestHarness.snapshot(
                        FollowState.defaults(),
                        true, true, true, true, true, 8.0, 10L));
        Stage7BExecutionTestSupport.SchedulerDriver scheduler =
                new Stage7BExecutionTestSupport.SchedulerDriver();
        Stage7BExecutionTestSupport.BoundChainControl control =
                new Stage7BExecutionTestSupport.BoundChainControl(
                        uuid, harness.chain);

        FollowControlResult on = apply(store, uuid, FollowAction.ON, 10L);
        assertEquals("ENABLED", on.code());
        assertEquals(0, harness.factory.created.size());
        Stage7BExecutionTestSupport.syncIntent(
                harness, store, uuid, 8.0, 10L);
        assertSame(harness.chain, scheduler.step(List.of(harness.chain)));
        OwnerFollowChainTestHarness.FakeNavigation first =
                harness.factory.last();
        store.bindRuntime(uuid, control);

        FollowRuntimeSnapshot beforeStatus = harness.chain.snapshot(10L);
        store.setDirty(false);
        FollowControlResult status =
                apply(store, uuid, FollowAction.STATUS, 10L);
        assertEquals("STATUS", status.code());
        assertFalse(status.changed());
        assertFalse(store.isDirty());
        assertSame(harness.chain, scheduler.running());
        assertSame(first, harness.factory.last());
        assertEquals(1, first.ticks);
        assertEquals(beforeStatus, harness.chain.snapshot(10L));

        FollowControlResult pause =
                apply(store, uuid, FollowAction.PAUSE, 11L);
        assertEquals("PAUSED", pause.code());
        assertEquals(1, first.stops);
        assertFalse(harness.chain.hasNavigation());
        assertSame(control, store.runtimeControl(uuid).orElseThrow());
        Stage7BExecutionTestSupport.syncIntent(
                harness, store, uuid, 8.0, 11L);
        assertEquals(null, scheduler.step(List.of(harness.chain)));
        assertEquals(1, harness.factory.created.size());

        FollowControlResult resume =
                apply(store, uuid, FollowAction.RESUME, 12L);
        assertEquals("RESUMED", resume.code());
        assertEquals(1, harness.factory.created.size());
        Stage7BExecutionTestSupport.syncIntent(
                harness, store, uuid, 8.0, 12L);
        assertSame(harness.chain, scheduler.step(List.of(harness.chain)));
        OwnerFollowChainTestHarness.FakeNavigation second =
                harness.factory.last();
        assertNotSame(first, second);

        FollowControlResult off =
                apply(store, uuid, FollowAction.OFF, 13L);
        assertEquals("DISABLED", off.code());
        assertEquals(1, second.stops);
        Stage7BExecutionTestSupport.syncIntent(
                harness, store, uuid, 8.0, 13L);
        assertEquals(null, scheduler.step(List.of(harness.chain)));
        assertSame(control, store.runtimeControl(uuid).orElseThrow());

        FollowControlResult repeatedOff =
                apply(store, uuid, FollowAction.OFF, 14L);
        assertEquals("ALREADY_DISABLED", repeatedOff.code());
        assertEquals(1, second.stops);

        apply(store, uuid, FollowAction.ON, 15L);
        assertEquals(2, harness.factory.created.size());
        Stage7BExecutionTestSupport.syncIntent(
                harness, store, uuid, 8.0, 15L);
        assertSame(harness.chain, scheduler.step(List.of(harness.chain)));
        OwnerFollowChainTestHarness.FakeNavigation third =
                harness.factory.last();
        assertNotSame(second, third);
        assertEquals(3, harness.factory.created.size());
        assertTrue(harness.chain.snapshot(15L).navigationActive());
        assertEquals(List.of(
                FollowReleaseReason.MANUAL_PAUSE,
                FollowReleaseReason.FOLLOW_DISABLED,
                FollowReleaseReason.FOLLOW_DISABLED), control.reasons());
    }

    private static FollowControlResult apply(
            FollowStateStore store,
            UUID uuid,
            FollowAction action,
            long gameTime) {
        return FollowService.apply(
                store,
                Stage7BExecutionTestSupport.subject(uuid, 8.0, gameTime),
                action,
                FollowConfig.defaults());
    }
}
