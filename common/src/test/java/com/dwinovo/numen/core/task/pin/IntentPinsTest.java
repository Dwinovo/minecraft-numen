package com.dwinovo.numen.core.task.pin;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link IntentPins} table — no Minecraft (fingerprints are
 * plain strings here; same style as {@code FoodPolicyTest}/{@code BodyLogTest}).
 * Covers the constitutional pin contract (§5): 落钉 / 归还 / 指纹过期 / 让位日记
 * 去抖, plus the persistence snapshot round-trip and the dirty callback.
 */
class IntentPinsTest {

    private static final String CHEST = "chest";
    private static final String LEATHER = "minecraft:leather_chestplate";
    private static final String IRON = "minecraft:iron_chestplate";

    private final IntentPins pins = new IntentPins();

    // ---- 落钉 / 归还 ----

    @Test
    void pinThenQuery() {
        pins.pin(CHEST, LEATHER);
        assertTrue(pins.isPinned(CHEST));
        assertEquals(LEATHER, pins.fingerprint(CHEST));
        assertFalse(pins.isPinned("head"));
    }

    @Test
    void unpinReleases() {
        pins.pin(CHEST, LEATHER);
        assertTrue(pins.unpin(CHEST));
        assertFalse(pins.isPinned(CHEST));
        assertNull(pins.fingerprint(CHEST));
    }

    @Test
    void unpinWithoutPinReportsNothingRemoved() {
        assertFalse(pins.unpin(CHEST));
    }

    @Test
    void blankFingerprintNeverPins() {
        pins.pin(CHEST, "");
        pins.pin(CHEST, null);
        assertFalse(pins.isPinned(CHEST));
    }

    // ---- validate: the scan-time keep-or-expire rule ----

    @Test
    void validateKeepsAMatchingPin() {
        pins.pin(CHEST, LEATHER);
        assertTrue(pins.validate(CHEST, LEATHER));
        assertTrue(pins.isPinned(CHEST));   // still standing
    }

    @Test
    void validateExpiresOnItemChange() {
        pins.pin(CHEST, LEATHER);
        // The slot now wears something else (reflex ran while unpinned? owner
        // hand-swapped? doesn't matter): the pinned intent is gone.
        assertFalse(pins.validate(CHEST, IRON));
        assertFalse(pins.isPinned(CHEST));
    }

    @Test
    void validateExpiresOnItemGone() {
        pins.pin(CHEST, LEATHER);
        // Item broke or left the slot: current fingerprint is "" (empty stack).
        assertFalse(pins.validate(CHEST, ""));
        assertFalse(pins.isPinned(CHEST));
    }

    @Test
    void validateOnUnpinnedSlotIsFalseAndHarmless() {
        assertFalse(pins.validate(CHEST, LEATHER));
        assertFalse(pins.isPinned(CHEST));
    }

    // ---- 让位日记去抖: once per pin lifetime ----

    @Test
    void yieldReportsOncePerPin() {
        pins.pin(CHEST, LEATHER);
        assertTrue(pins.shouldReportYield(CHEST));
        assertFalse(pins.shouldReportYield(CHEST));   // debounced
        assertFalse(pins.shouldReportYield(CHEST));
    }

    @Test
    void rePinRearmsTheYieldReport() {
        pins.pin(CHEST, LEATHER);
        assertTrue(pins.shouldReportYield(CHEST));
        pins.pin(CHEST, IRON);   // a NEW pin — its first yield deserves a fresh line
        assertTrue(pins.shouldReportYield(CHEST));
        assertFalse(pins.shouldReportYield(CHEST));
    }

    @Test
    void unpinnedSlotNeverReportsYield() {
        assertFalse(pins.shouldReportYield(CHEST));
        pins.pin(CHEST, LEATHER);
        pins.unpin(CHEST);
        assertFalse(pins.shouldReportYield(CHEST));
    }

    @Test
    void expiryRearmsTheYieldReport() {
        pins.pin(CHEST, LEATHER);
        assertTrue(pins.shouldReportYield(CHEST));
        assertFalse(pins.validate(CHEST, ""));   // expired
        pins.pin(CHEST, LEATHER);                 // pinned anew
        assertTrue(pins.shouldReportYield(CHEST));
    }

    // ---- persistence snapshot ----

    @Test
    void snapshotRoundTrips() {
        pins.pin(CHEST, LEATHER);
        pins.pin("mainhand", "minecraft:wooden_pickaxe");
        IntentPins restored = IntentPins.fromSnapshot(pins.snapshot(), () -> {});
        assertTrue(restored.validate(CHEST, LEATHER));
        assertTrue(restored.validate("mainhand", "minecraft:wooden_pickaxe"));
        assertFalse(restored.isPinned("head"));
    }

    @Test
    void snapshotSkipsBlankEntries() {
        IntentPins restored = IntentPins.fromSnapshot(Map.of(CHEST, ""), () -> {});
        assertTrue(restored.isEmpty());
    }

    // ---- dirty callback: every real mutation reports, reads don't ----

    @Test
    void onChangeFiresOnMutationsOnly() {
        AtomicInteger dirty = new AtomicInteger();
        IntentPins tracked = new IntentPins(dirty::incrementAndGet);

        tracked.pin(CHEST, LEATHER);              // 1
        tracked.validate(CHEST, LEATHER);         // match: read-only, no fire
        assertEquals(1, dirty.get());

        tracked.validate(CHEST, IRON);            // expiry unpins → 2
        assertEquals(2, dirty.get());

        tracked.unpin(CHEST);                     // nothing pinned: no fire
        assertEquals(2, dirty.get());

        tracked.pin(CHEST, IRON);                 // 3
        tracked.unpin(CHEST);                     // 4
        assertEquals(4, dirty.get());
    }
}
