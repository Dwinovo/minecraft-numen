package com.dwinovo.numen.core.follow;

import static com.dwinovo.numen.core.follow.FollowStage7ATestSupport.reload;
import static com.dwinovo.numen.core.follow.FollowStage7ATestSupport.state;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

class FollowStatePersistenceRegressionTest {

    @Test
    void emptyStoreRoundTripsThroughRealNbt() {
        FollowStateStore original = new FollowStateStore();

        CompoundTag saved = original.save(new CompoundTag());
        FollowStateStore loaded = FollowStateStore.load(saved);

        assertEquals(Set.of("entries"), saved.getAllKeys());
        assertEquals(0, saved.getList("entries", Tag.TAG_COMPOUND).size());
        assertEquals(0, loaded.size());
        assertEquals(0, loaded.runtimeControlCount());
    }

    @Test
    void multipleCompanionsAndOverridesRoundTripTogether() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        FollowStateStore original = new FollowStateStore();
        FollowState firstState = state(true, false, 2.5, 6.5);
        FollowState secondState = state(true, true, null, null);
        FollowState thirdState = state(false, false, 3.0, 7.0);
        original.put(first, firstState);
        original.put(second, secondState);
        original.put(third, thirdState);

        FollowStateStore loaded = reload(original);

        assertEquals(3, loaded.size());
        assertEquals(firstState, loaded.getOrDefault(first));
        assertEquals(secondState, loaded.getOrDefault(second));
        assertEquals(thirdState, loaded.getOrDefault(third));
    }

    @Test
    void removingOneCompanionBeforeSaveDoesNotAffectOtherEntries() {
        UUID retainedA = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        UUID retainedC = UUID.randomUUID();
        FollowStateStore original = new FollowStateStore();
        original.put(retainedA, state(true, false, 2.5, 6.5));
        original.put(removed, state(true, true, null, null));
        original.put(retainedC, FollowState.defaults());

        original.remove(removed);
        FollowStateStore loaded = reload(original);

        assertEquals(2, loaded.size());
        assertEquals(state(true, false, 2.5, 6.5),
                loaded.getOrDefault(retainedA));
        assertTrue(loaded.find(removed).isEmpty());
        assertEquals(FollowState.defaults(), loaded.getOrDefault(retainedC));
    }

    @Test
    void invalidUuidEntryIsDiscardedBesideValidEntry() {
        UUID validUuid = UUID.randomUUID();
        CompoundTag valid = validEntry(
                validUuid, true, false, FollowState.CURRENT_SCHEMA_VERSION);
        CompoundTag invalidUuid = new CompoundTag();
        invalidUuid.putString("companionUuid", "not-a-uuid");
        invalidUuid.putBoolean("enabled", true);
        invalidUuid.putInt("schemaVersion", FollowState.CURRENT_SCHEMA_VERSION);

        FollowStateStore loaded =
                FollowStateStore.load(rootWith(valid, invalidUuid));

        assertEquals(1, loaded.size());
        assertTrue(loaded.getOrDefault(validUuid).enabled());
    }

    @Test
    void savedNbtContainsOnlyApprovedPersistentFields() {
        UUID companion = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        store.put(companion, state(true, false, 2.5, 6.5));
        FollowStage7ATestSupport.TrackingControl runtime =
                new FollowStage7ATestSupport.TrackingControl(
                        companion,
                        new FollowRuntimeSnapshot(
                                FollowRuntimeState.FOLLOWING,
                                FollowWaitingReason.NONE,
                                true, true, true, true, 250L, 150L));
        store.bindRuntime(companion, runtime);
        FollowControlResult result = FollowService.apply(
                store,
                FollowStage7ATestSupport.subject(
                        companion, true, true, true, true,
                        java.util.OptionalDouble.of(8.0), 100L),
                FollowAction.STATUS,
                FollowConfig.defaults());

        CompoundTag saved = store.save(new CompoundTag());
        ListTag entries = saved.getList("entries", Tag.TAG_COMPOUND);
        CompoundTag entry = entries.getCompound(0);
        String text = saved.toString().toLowerCase(Locale.ROOT);

        assertEquals(Set.of("entries"), saved.getAllKeys());
        assertEquals(Set.of(
                "companionUuid",
                "enabled",
                "manualPaused",
                "schemaVersion",
                "stopDistanceOverride",
                "startDistanceOverride"), entry.getAllKeys());
        for (String forbidden : List.of(
                "owner",
                "runtime",
                "waiting",
                "navigation",
                "cooldown",
                "following",
                "sprint",
                "catch",
                "lost",
                "allow_",
                "follow_disabled",
                result.code().toLowerCase(Locale.ROOT))) {
            assertFalse(text.contains(forbidden), forbidden);
        }
    }

    @Test
    void sameUuidRetainsPersistentStateButDropsBoundRuntimeOnReload() {
        UUID companion = UUID.randomUUID();
        FollowState expected = state(true, true, 2.5, 6.5);
        FollowStateStore original = new FollowStateStore();
        original.put(companion, expected);
        original.bindRuntime(
                companion,
                new FollowStage7ATestSupport.TrackingControl(
                        companion,
                        new FollowRuntimeSnapshot(
                                FollowRuntimeState.FAILED_COOLDOWN,
                                FollowWaitingReason.NONE,
                                true, false, false, false, 500L, 400L)));

        FollowStateStore loaded = reload(original);

        assertEquals(expected, loaded.getOrDefault(companion));
        assertEquals(0, loaded.runtimeControlCount());
        assertTrue(loaded.runtimeControl(companion).isEmpty());
    }

    private static CompoundTag validEntry(
            UUID uuid, boolean enabled, boolean paused, int schemaVersion) {
        CompoundTag entry = new CompoundTag();
        entry.putUUID("companionUuid", uuid);
        entry.putBoolean("enabled", enabled);
        entry.putBoolean("manualPaused", paused);
        entry.putInt("schemaVersion", schemaVersion);
        return entry;
    }

    private static CompoundTag rootWith(CompoundTag... values) {
        ListTag entries = new ListTag();
        for (CompoundTag value : values) {
            entries.add(value);
        }
        CompoundTag root = new CompoundTag();
        root.put("entries", entries);
        return root;
    }
}
