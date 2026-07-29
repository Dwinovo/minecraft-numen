package com.dwinovo.numen.core.follow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class FollowStateIsolationTest {

    @Test
    void companionUuidKeysKeepStatesIsolated() {
        UUID companionA = UUID.randomUUID();
        UUID companionB = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();

        store.setEnabled(companionA, true);
        store.setManualPaused(companionB, true);
        store.setDistanceOverrides(companionA, 3.0, 5.5);

        assertTrue(store.getOrDefault(companionA).enabled());
        assertFalse(store.getOrDefault(companionA).manualPaused());
        assertEquals(3.0, store.getOrDefault(companionA).stopDistanceOverride());

        assertFalse(store.getOrDefault(companionB).enabled());
        assertTrue(store.getOrDefault(companionB).manualPaused());
        assertEquals(null, store.getOrDefault(companionB).stopDistanceOverride());
    }

    @Test
    void snapshotCannotMutateStore() {
        UUID companion = UUID.randomUUID();
        FollowStateStore store = new FollowStateStore();
        store.setEnabled(companion, true);

        Map<UUID, FollowState> snapshot = store.snapshot();

        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.put(UUID.randomUUID(), FollowState.defaults()));
        assertEquals(1, store.size());
    }

    @Test
    void followStoreHasNoGlobalStaticStateContainer() {
        for (Field field : FollowStateStore.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            Class<?> type = field.getType();
            assertFalse(Map.class.isAssignableFrom(type),
                    () -> "static map would create global follow state: " + field.getName());
            assertFalse(type == FollowState.class || type == FollowStateStore.class,
                    () -> "static follow object would create global state: " + field.getName());
        }
    }
}
