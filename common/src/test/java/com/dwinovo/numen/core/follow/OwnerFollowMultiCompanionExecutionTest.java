package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.OwnerFollowChainTestHarness.activeAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;

class OwnerFollowMultiCompanionExecutionTest {

    @Test
    void interruptFailurePauseDeathReplacementAndShutdownStayUuidIsolated() {
        UUID firstUuid = UUID.randomUUID();
        UUID secondUuid = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        store.put(firstUuid, OwnerFollowChainTestHarness.enabled());
        store.put(secondUuid, OwnerFollowChainTestHarness.enabled());
        FollowState firstIntent = store.getOrDefault(firstUuid);
        FollowState secondIntent = store.getOrDefault(secondUuid);

        OwnerFollowChainTestHarness first =
                new OwnerFollowChainTestHarness(activeAt(24.0, 10L));
        OwnerFollowChainTestHarness second =
                new OwnerFollowChainTestHarness(activeAt(8.0, 10L));
        first.chain.tick(null);
        second.chain.tick(null);
        Stage7BExecutionTestSupport.BoundChainControl firstControl =
                new Stage7BExecutionTestSupport.BoundChainControl(
                        firstUuid, first.chain);
        Stage7BExecutionTestSupport.BoundChainControl secondControl =
                new Stage7BExecutionTestSupport.BoundChainControl(
                        secondUuid, second.chain);
        store.bindRuntime(firstUuid, firstControl);
        store.bindRuntime(secondUuid, secondControl);
        OwnerFollowChainTestHarness.FakeNavigation secondNavigation =
                second.factory.last();

        assertTrue(first.chain.snapshot(10L).sprintAllowed());
        assertTrue(first.chain.snapshot(10L).catchingUp());
        assertFalse(second.chain.snapshot(10L).sprintAllowed());
        assertFalse(second.chain.snapshot(10L).catchingUp());

        OwnerFollowChainTestHarness.FakeNavigation interrupted =
                first.factory.last();
        first.chain.onInterrupt(null);
        assertEquals(1, interrupted.stops);
        assertEquals(0, secondNavigation.stops);
        first.chain.tick(null);
        OwnerFollowChainTestHarness.FakeNavigation failed =
                first.factory.last();
        assertNotSame(interrupted, failed);
        assertSame(FollowContextProvider.INSTANCE, failed.contextProvider);
        failed.status = PlayerNav.Status.FAILED;
        first.access.snapshot = activeAt(24.0, 20L);
        first.chain.tick(null);
        assertEquals(120L, first.chain.snapshot(20L).failedUntilTick());
        assertTrue(second.chain.snapshot(20L).navigationActive());
        assertEquals(1, secondNavigation.ticks);

        FollowService.apply(
                store,
                Stage7BExecutionTestSupport.subject(firstUuid, 24.0, 21L),
                FollowAction.PAUSE,
                FollowConfig.defaults());
        assertTrue(store.getOrDefault(firstUuid).manualPaused());
        assertEquals(secondIntent, store.getOrDefault(secondUuid));
        assertSame(secondControl,
                store.runtimeControl(secondUuid).orElseThrow());
        assertEquals(0, secondNavigation.stops);

        OwnerFollowChainTestHarness replacement =
                new OwnerFollowChainTestHarness(activeAt(8.0, 22L));
        replacement.chain.tick(null);
        Stage7BExecutionTestSupport.BoundChainControl replacementControl =
                new Stage7BExecutionTestSupport.BoundChainControl(
                        firstUuid, replacement.chain);
        store.bindRuntime(firstUuid, replacementControl);
        assertSame(replacementControl,
                store.runtimeControl(firstUuid).orElseThrow());
        assertEquals(1, replacement.factory.created.size());
        assertSame(FollowContextProvider.INSTANCE,
                replacement.factory.last().contextProvider);
        assertEquals(0, secondNavigation.stops);

        store.removeRuntime(
                firstUuid, replacementControl,
                FollowReleaseReason.COMPANION_DEATH);
        assertFalse(store.runtimeControl(firstUuid).isPresent());
        assertEquals(1, replacement.factory.last().stops);
        assertSame(secondControl,
                store.runtimeControl(secondUuid).orElseThrow());
        assertEquals(0, secondNavigation.stops);

        assertEquals(0,
                store.releaseAllRuntime(FollowReleaseReason.SERVER_STOPPING));
        assertEquals(0, store.runtimeControlCount());
        assertEquals(1, secondNavigation.stops);
        assertEquals(firstIntent.enabled(),
                store.getOrDefault(firstUuid).enabled());
        assertTrue(store.getOrDefault(firstUuid).manualPaused());
        assertEquals(secondIntent, store.getOrDefault(secondUuid));
    }
}
