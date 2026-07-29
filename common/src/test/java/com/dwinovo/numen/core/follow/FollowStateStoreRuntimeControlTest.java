package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;

class FollowStateStoreRuntimeControlTest {

    @Test
    void bindMakesControlDiscoverableByCompanionUuid() {
        FollowStateStore store = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());

        store.bindRuntime(control.companionUuid(), control);

        assertSame(control, store.runtimeControl(control.companionUuid()).orElseThrow());
        assertEquals(1, store.runtimeControlCount());
    }

    @Test
    void duplicateBindingOfSameControlIsNoOp() {
        FollowStateStore store = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());

        store.bindRuntime(control.companionUuid(), control);
        store.bindRuntime(control.companionUuid(), control);

        assertEquals(List.of(), control.reasons);
        assertEquals(1, store.runtimeControlCount());
    }

    @Test
    void replacementIsInstalledBeforeOldControlIsReleased() {
        FollowStateStore store = new FollowStateStore();
        UUID uuid = UUID.randomUUID();
        FakeControl oldControl = new FakeControl(uuid);
        FakeControl newControl = new FakeControl(uuid);
        oldControl.onRelease = () ->
                assertSame(newControl, store.runtimeControl(uuid).orElseThrow());
        store.bindRuntime(uuid, oldControl);

        store.bindRuntime(uuid, newControl);

        assertSame(newControl, store.runtimeControl(uuid).orElseThrow());
        assertEquals(List.of(FollowReleaseReason.RUNTIME_REPLACED), oldControl.reasons);
        assertEquals(List.of(), newControl.reasons);
    }

    @Test
    void differentCompanionUuidsRemainIsolated() {
        FollowStateStore store = new FollowStateStore();
        FakeControl first = new FakeControl(UUID.randomUUID());
        FakeControl second = new FakeControl(UUID.randomUUID());
        store.bindRuntime(first.companionUuid(), first);
        store.bindRuntime(second.companionUuid(), second);

        store.releaseRuntime(first.companionUuid(),
                FollowReleaseReason.SCHEDULER_INTERRUPT);

        assertEquals(1, first.reasons.size());
        assertEquals(0, second.reasons.size());
        assertSame(second, store.runtimeControl(second.companionUuid()).orElseThrow());
    }

    @Test
    void releaseRuntimeRetainsBinding() {
        FollowStateStore store = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());
        store.bindRuntime(control.companionUuid(), control);

        store.releaseRuntime(control.companionUuid(),
                FollowReleaseReason.FOLLOW_DISABLED);

        assertSame(control, store.runtimeControl(control.companionUuid()).orElseThrow());
        assertEquals(List.of(FollowReleaseReason.FOLLOW_DISABLED), control.reasons);
    }

    @Test
    void removeRuntimeRemovesBeforeRelease() {
        FollowStateStore store = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());
        control.onRelease = () ->
                assertTrue(store.runtimeControl(control.companionUuid()).isEmpty());
        store.bindRuntime(control.companionUuid(), control);

        store.removeRuntime(control.companionUuid(), control,
                FollowReleaseReason.COMPANION_REMOVED);

        assertEquals(0, store.runtimeControlCount());
        assertEquals(List.of(FollowReleaseReason.COMPANION_REMOVED), control.reasons);
    }

    @Test
    void missingRuntimeReleaseAndRemoveAreSafeNoOps() {
        FollowStateStore store = new FollowStateStore();
        UUID missing = UUID.randomUUID();

        store.releaseRuntime(missing, FollowReleaseReason.FOLLOW_DISABLED);
        store.removeRuntime(
                missing, new Object(), FollowReleaseReason.COMPANION_REMOVED);

        assertEquals(0, store.runtimeControlCount());
        assertFalse(store.isDirty());
    }

    @Test
    void releaseAllClearsMapBeforeReleasingEveryControl() {
        FollowStateStore store = new FollowStateStore();
        FakeControl first = new FakeControl(UUID.randomUUID());
        FakeControl second = new FakeControl(UUID.randomUUID());
        first.onRelease = () -> assertEquals(0, store.runtimeControlCount());
        second.onRelease = () -> assertEquals(0, store.runtimeControlCount());
        store.bindRuntime(first.companionUuid(), first);
        store.bindRuntime(second.companionUuid(), second);

        int failures = store.releaseAllRuntime(FollowReleaseReason.SERVER_STOPPING);

        assertEquals(0, failures);
        assertEquals(0, store.runtimeControlCount());
        assertEquals(List.of(FollowReleaseReason.SERVER_STOPPING), first.reasons);
        assertEquals(List.of(FollowReleaseReason.SERVER_STOPPING), second.reasons);
    }

    @Test
    void releaseAllContinuesAfterOneControlThrows() {
        FollowStateStore store = new FollowStateStore();
        FakeControl faulty = new FakeControl(UUID.randomUUID());
        FakeControl healthy = new FakeControl(UUID.randomUUID());
        faulty.throwOnRelease = true;
        store.bindRuntime(faulty.companionUuid(), faulty);
        store.bindRuntime(healthy.companionUuid(), healthy);

        int failures = store.releaseAllRuntime(FollowReleaseReason.SERVER_STOPPING);

        assertEquals(1, failures);
        assertEquals(1, faulty.reasons.size());
        assertEquals(1, healthy.reasons.size());
        assertEquals(0, store.runtimeControlCount());
    }

    @Test
    void runtimeOperationsNeverMarkSavedDataDirty() {
        FollowStateStore store = new FollowStateStore();
        FakeControl first = new FakeControl(UUID.randomUUID());
        FakeControl replacement = new FakeControl(first.companionUuid());

        store.bindRuntime(first.companionUuid(), first);
        store.bindRuntime(replacement.companionUuid(), replacement);
        store.releaseRuntime(replacement.companionUuid(),
                FollowReleaseReason.SCHEDULER_INTERRUPT);
        store.removeRuntime(replacement.companionUuid(), replacement,
                FollowReleaseReason.COMPANION_REMOVED);
        store.releaseAllRuntime(FollowReleaseReason.SERVER_STOPPING);

        assertFalse(store.isDirty());
    }

    @Test
    void runtimeRegistryIsAbsentFromSavedNbt() {
        FollowStateStore store = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());
        store.bindRuntime(control.companionUuid(), control);

        CompoundTag saved = store.save(new CompoundTag());

        assertEquals(1, saved.getAllKeys().size());
        assertTrue(saved.contains("entries"));
        assertFalse(saved.toString().contains(control.companionUuid().toString()));
    }

    @Test
    void loadedStoreAlwaysStartsWithEmptyRuntimeRegistry() {
        FollowStateStore original = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());
        original.bindRuntime(control.companionUuid(), control);

        FollowStateStore loaded =
                FollowStateStore.load(original.save(new CompoundTag()));

        assertEquals(0, loaded.runtimeControlCount());
    }

    @Test
    void registryIsPerStoreInstanceRatherThanGlobal() {
        FollowStateStore firstStore = new FollowStateStore();
        FollowStateStore secondStore = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());

        firstStore.bindRuntime(control.companionUuid(), control);

        assertEquals(1, firstStore.runtimeControlCount());
        assertEquals(0, secondStore.runtimeControlCount());
    }

    @Test
    void runtimeSnapshotDelegatesWithoutRemovingBinding() {
        FollowStateStore store = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());
        store.bindRuntime(control.companionUuid(), control);

        FollowRuntimeSnapshot snapshot =
                store.runtimeSnapshot(control.companionUuid(), 75L).orElseThrow();

        assertEquals(control.snapshot, snapshot);
        assertEquals(75L, control.lastSnapshotTick);
        assertSame(control, store.runtimeControl(control.companionUuid()).orElseThrow());
    }

    @Test
    void nullAndMismatchedBindingsAreExplicitlyRejected() {
        FollowStateStore store = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());

        assertThrows(NullPointerException.class,
                () -> store.bindRuntime(null, control));
        assertThrows(NullPointerException.class,
                () -> store.bindRuntime(control.companionUuid(), null));
        assertThrows(IllegalArgumentException.class,
                () -> store.bindRuntime(UUID.randomUUID(), control));
    }

    @Test
    void deathThenRemoveOnlyReleasesTheRuntimeOnce() {
        FollowStateStore store = new FollowStateStore();
        FakeControl control = new FakeControl(UUID.randomUUID());
        store.bindRuntime(control.companionUuid(), control);

        store.removeRuntime(control.companionUuid(), control,
                FollowReleaseReason.COMPANION_DEATH);
        store.removeRuntime(control.companionUuid(), control,
                FollowReleaseReason.COMPANION_REMOVED);

        assertEquals(List.of(FollowReleaseReason.COMPANION_DEATH), control.reasons);
        assertEquals(0, store.runtimeControlCount());
    }

    @Test
    void newRuntimeCanBindSameUuidAfterLifecycleRemoval() {
        FollowStateStore store = new FollowStateStore();
        UUID uuid = UUID.randomUUID();
        FakeControl oldControl = new FakeControl(uuid);
        FakeControl respawnedControl = new FakeControl(uuid);
        store.bindRuntime(uuid, oldControl);
        store.removeRuntime(
                uuid, oldControl, FollowReleaseReason.COMPANION_DEATH);

        store.bindRuntime(uuid, respawnedControl);

        assertSame(respawnedControl, store.runtimeControl(uuid).orElseThrow());
        assertEquals(1, store.runtimeControlCount());
    }

    @Test
    void serverStoppingLeavesPersistentFollowIntentUnchanged() {
        FollowStateStore store = new FollowStateStore();
        UUID uuid = UUID.randomUUID();
        FollowState intent = new FollowState(
                true, true, FollowState.CURRENT_SCHEMA_VERSION, 3.0, 6.0);
        store.put(uuid, intent);
        store.setDirty(false);
        FakeControl control = new FakeControl(uuid);
        store.bindRuntime(uuid, control);

        store.releaseAllRuntime(FollowReleaseReason.SERVER_STOPPING);

        assertEquals(intent, store.getOrDefault(uuid));
        assertFalse(store.isDirty());
        assertEquals(0, store.runtimeControlCount());
    }

    private static final class FakeControl implements FollowRuntimeControl {
        private final UUID uuid;
        private final List<FollowReleaseReason> reasons = new ArrayList<>();
        private final FollowRuntimeSnapshot snapshot = new FollowRuntimeSnapshot(
                FollowRuntimeState.DISABLED,
                FollowWaitingReason.NONE,
                false,
                false,
                false,
                false,
                FollowDecisions.NO_FAILED_COOLDOWN,
                0L);
        private Runnable onRelease = () -> {};
        private boolean throwOnRelease;
        private long lastSnapshotTick = Long.MIN_VALUE;

        private FakeControl(UUID uuid) {
            this.uuid = uuid;
        }

        @Override
        public UUID companionUuid() {
            return uuid;
        }

        @Override
        public void release(FollowReleaseReason reason) {
            reasons.add(reason);
            onRelease.run();
            if (throwOnRelease) {
                throw new IllegalStateException("deliberate test failure");
            }
        }

        @Override
        public FollowRuntimeSnapshot snapshot(long currentGameTime) {
            lastSnapshotTick = currentGameTime;
            return snapshot;
        }
    }
}
