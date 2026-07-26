package com.dwinovo.numen.core.wake;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledWakeRegistryTest {

    @Test
    void wakeBecomesDueOnlyAfterItsGameTimeDelay() {
        ScheduledWakeRegistry registry = new ScheduledWakeRegistry();
        UUID companion = UUID.randomUUID();
        ScheduledWakeRegistry.Entry entry = registry.schedule(companion, 1_000L, 30, "check furnace");

        assertTrue(registry.due(1_599L).isEmpty());
        assertEquals(entry.id(), registry.due(1_600L).getFirst().id());
        assertEquals(30L, ScheduledWakeRegistry.remainingSeconds(entry, 1_000L));
    }

    @Test
    void cancellationIsScopedToTheOwningCompanion() {
        ScheduledWakeRegistry registry = new ScheduledWakeRegistry();
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ScheduledWakeRegistry.Entry entry = registry.schedule(owner, 0L, 10, "return");

        assertFalse(registry.cancel(other, entry.id()));
        assertTrue(registry.cancel(owner, entry.id()));
        assertTrue(registry.list(owner).isEmpty());
    }

    @Test
    void persistentStateRoundTrips() {
        ScheduledWakeRegistry registry = new ScheduledWakeRegistry();
        UUID companion = UUID.randomUUID();
        ScheduledWakeRegistry.Entry entry = registry.schedule(companion, 123L, 45, "collect output");
        CompoundTag tag = registry.save(new CompoundTag(), null);

        ScheduledWakeRegistry restored = ScheduledWakeRegistry.load(tag, null);
        assertEquals(entry, restored.list(companion).getFirst());
    }

    @Test
    void perCompanionLimitBoundsRunawayScheduling() {
        ScheduledWakeRegistry registry = new ScheduledWakeRegistry();
        UUID companion = UUID.randomUUID();
        for (int i = 0; i < ScheduledWakeRegistry.MAX_PER_COMPANION; i++) {
            registry.schedule(companion, 0L, i + 1, "wake " + i);
        }
        assertThrows(IllegalStateException.class,
                () -> registry.schedule(companion, 0L, 60, "one too many"));
    }
}
