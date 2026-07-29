package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

class FollowStateStoreTest {

    @Test
    void roundTripsPersistedIntentAndApprovedDistanceOverrides() {
        UUID companion = UUID.randomUUID();
        FollowState expected = new FollowState(true, true,
                FollowState.CURRENT_SCHEMA_VERSION, 3.25, 6.0);
        FollowStateStore original = new FollowStateStore();
        original.put(companion, expected);

        CompoundTag encoded = original.save(new CompoundTag());
        FollowStateStore decoded = FollowStateStore.load(encoded);

        assertEquals(expected, decoded.find(companion).orElseThrow());
        assertEquals(1, decoded.size());
    }

    @Test
    void missingFieldsUseSafeDefaults() {
        UUID companion = UUID.randomUUID();
        CompoundTag entry = new CompoundTag();
        entry.putUUID("companionUuid", companion);

        FollowStateStore decoded = FollowStateStore.load(rootWith(entry));

        assertEquals(FollowState.defaults(), decoded.find(companion).orElseThrow());
        assertFalse(decoded.getOrDefault(companion).enabled());
        assertFalse(decoded.getOrDefault(companion).manualPaused());
    }

    @Test
    void unknownFieldsAreIgnoredAndNotWrittenBack() {
        UUID companion = UUID.randomUUID();
        CompoundTag entry = validEntry(companion, true, false);
        entry.putString("futureField", "ignored");
        entry.putUUID("ownerUuid", UUID.randomUUID());
        CompoundTag root = rootWith(entry);
        root.putString("futureRoot", "ignored");

        FollowStateStore decoded = FollowStateStore.load(root);
        CompoundTag rewritten = decoded.save(new CompoundTag());

        assertTrue(decoded.getOrDefault(companion).enabled());
        assertEquals(Set.of("entries"), rewritten.getAllKeys());
        assertFalse(rewritten.toString().contains("futureField"));
        assertFalse(rewritten.toString().toLowerCase().contains("owner"));
    }

    @Test
    void malformedEntryDoesNotDiscardOtherCompanions() {
        UUID first = UUID.randomUUID();
        UUID malformed = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        CompoundTag bad = validEntry(malformed, true, false);
        bad.putString("enabled", "not-a-boolean");
        FollowStateStore decoded = FollowStateStore.load(rootWith(
                validEntry(first, true, false),
                bad,
                validEntry(second, false, true)));

        assertEquals(2, decoded.size());
        assertTrue(decoded.getOrDefault(first).enabled());
        assertTrue(decoded.getOrDefault(second).manualPaused());
        assertTrue(decoded.find(malformed).isEmpty());
    }

    @Test
    void malformedTopLevelAndEmptyFirstLoadFallBackToEmptyStore() {
        FollowStateStore empty = FollowStateStore.load(new CompoundTag());
        assertEquals(0, empty.size());
        assertEquals(FollowState.defaults(), empty.getOrDefault(UUID.randomUUID()));

        CompoundTag malformed = new CompoundTag();
        malformed.putString("entries", "not-a-list");
        assertEquals(0, FollowStateStore.load(malformed).size());
    }

    @Test
    void everyStateMutationMarksSavedDataDirty() {
        UUID companion = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        assertFalse(store.isDirty());

        store.setEnabled(companion, true);
        assertTrue(store.isDirty());

        store.setDirty(false);
        store.setManualPaused(companion, true);
        assertTrue(store.isDirty());

        store.setDirty(false);
        store.setDistanceOverrides(companion, 3.0, 5.5);
        assertTrue(store.isDirty());

        store.setDirty(false);
        store.remove(companion);
        assertTrue(store.isDirty());
    }

    @Test
    void savedDataSchemaIsIndependentFromExistingNumenData() {
        UUID companion = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        store.setEnabled(companion, true);

        CompoundTag encoded = store.save(new CompoundTag());

        assertEquals("numen_auto_follow", FollowStateStore.DATA_NAME);
        assertNotEquals("numen_companions", FollowStateStore.DATA_NAME);
        assertEquals(Set.of("entries"), encoded.getAllKeys());
        assertFalse(encoded.toString().contains("NumenOwner"));
        assertFalse(encoded.toString().toLowerCase().contains("owner"));
    }

    private static CompoundTag validEntry(UUID uuid, boolean enabled, boolean manualPaused) {
        CompoundTag entry = new CompoundTag();
        entry.putUUID("companionUuid", uuid);
        entry.putBoolean("enabled", enabled);
        entry.putBoolean("manualPaused", manualPaused);
        entry.putInt("schemaVersion", FollowState.CURRENT_SCHEMA_VERSION);
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
