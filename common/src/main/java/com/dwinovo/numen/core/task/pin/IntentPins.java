package com.dwinovo.numen.core.task.pin;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One companion's intent pins (constitution §5): {@code equipment slot →
 * item fingerprint}, each pin the durable trace of an EXPLICIT {@code equip_item}
 * — "this slot wears this because I was told to". Reflexes (armor upkeep, tool
 * swap) skip a pinned slot; the pin dies at its natural endpoints — explicit
 * re-equip or {@code item_id="auto"} return, the item breaking, or the item
 * leaving the slot — checked by fingerprint at every scan
 * ({@link #validate}), deliberately with NO timer.
 *
 * <p>Pure JDK on purpose (headless-tested, {@code IntentPinsTest}); slots are
 * the vanilla slot-name strings ({@code EquipmentSlot.getName()}), fingerprints
 * are whatever opaque string {@code Fingerprints} produces. The Minecraft
 * persistence lives in {@link IntentPinsData}; mutations report through the
 * injected {@code onChange} (production: {@code SavedData.setDirty}).
 *
 * <p>Also owns the yield-diary debounce: when a reflex gives way to a pin it
 * diaries ONCE per pin lifetime ({@link #shouldReportYield}); re-pinning or
 * unpinning re-arms the report.
 */
public final class IntentPins {

    /** The one slot whose pin is task-scoped (released when the LLM task chain
     *  goes idle — see {@code HandPinRelease}); armor pins live by §5 alone. */
    public static final String SLOT_MAINHAND = "mainhand";

    /** slot name → item fingerprint. Insertion order kept for stable saves. */
    private final Map<String, String> pins = new LinkedHashMap<>();
    /** Slots whose yield has been diaried for the CURRENT pin (transient). */
    private final Set<String> yieldReported = new HashSet<>();
    private final Runnable onChange;

    /** Detached pins (tests, or a body with no server) — changes go nowhere. */
    public IntentPins() {
        this(() -> {});
    }

    public IntentPins(Runnable onChange) {
        this.onChange = onChange;
    }

    /** Restore from a persisted snapshot. */
    public static IntentPins fromSnapshot(Map<String, String> snapshot, Runnable onChange) {
        IntentPins p = new IntentPins(onChange);
        snapshot.forEach((slot, fp) -> {
            if (fp != null && !fp.isEmpty()) p.pins.put(slot, fp);
        });
        return p;
    }

    /** Fall a pin: this slot now wears {@code fingerprint} by explicit intent.
     *  Re-pinning (even the same item) re-arms the yield diary. */
    public void pin(String slot, String fingerprint) {
        if (slot == null || fingerprint == null || fingerprint.isEmpty()) return;
        pins.put(slot, fingerprint);
        yieldReported.remove(slot);
        onChange.run();
    }

    /** Release a slot's pin (explicit "auto" return, task end for the hand, or
     *  expiry). Returns whether a pin was actually there. */
    public boolean unpin(String slot) {
        boolean removed = pins.remove(slot) != null;
        if (removed) {
            yieldReported.remove(slot);
            onChange.run();
        }
        return removed;
    }

    public boolean isPinned(String slot) {
        return pins.containsKey(slot);
    }

    public String fingerprint(String slot) {
        return pins.get(slot);
    }

    /**
     * Scan-time check-and-expire, the ONLY liveness rule a pin has: {@code true}
     * iff the slot is pinned and what it currently holds matches the pinned
     * fingerprint. A mismatch (item broke, item left the slot) expires the pin
     * on the spot and returns {@code false} — the reflex may then act.
     */
    public boolean validate(String slot, String currentFingerprint) {
        String pinned = pins.get(slot);
        if (pinned == null) return false;
        if (pinned.equals(currentFingerprint)) return true;
        unpin(slot);
        return false;
    }

    /**
     * Yield-diary debounce: {@code true} exactly once per pin lifetime — the
     * first time a reflex reports "I gave way to your pin on this slot". Resets
     * when the slot is re-pinned or unpinned.
     */
    public boolean shouldReportYield(String slot) {
        if (!pins.containsKey(slot)) return false;
        return yieldReported.add(slot);
    }

    public boolean isEmpty() {
        return pins.isEmpty();
    }

    /** Persistable copy of the pin table. */
    public Map<String, String> snapshot() {
        return new LinkedHashMap<>(pins);
    }
}
