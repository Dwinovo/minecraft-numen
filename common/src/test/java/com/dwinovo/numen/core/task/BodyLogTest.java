package com.dwinovo.numen.core.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link BodyLog} dual-rail routing core — no Minecraft
 * (the transport is a stub sink; same style as {@code FoodPolicyTest}). Covers
 * the constitutional contract (§4): busy queues for the tool result, idle ships
 * ONE ambient event immediately, both rails share a single drain, the fallback
 * flush rescues entries stranded by a no-result termination, and a refused
 * transport (owner offline) holds entries for a retry.
 */
class BodyLogTest {

    private boolean llmActive;
    private boolean sinkAccepts = true;
    private final List<String> emitted = new ArrayList<>();
    private final BodyLog log = new BodyLog(() -> llmActive, xml -> {
        if (!sinkAccepts) return false;
        emitted.add(xml);
        return true;
    });

    // ---- idle rail: report ships immediately ----

    @Test
    void idleReportShipsImmediatelyAsOneAmbientEvent() {
        log.report("broke a 12-block fall with water");
        assertEquals(1, emitted.size());
        String xml = emitted.get(0);
        assertTrue(xml.startsWith("<event kind=\"body_log\">"));
        assertTrue(xml.endsWith("</event>"));
        assertTrue(xml.contains("broke a 12-block fall with water"));
        assertTrue(log.isEmpty());
    }

    @Test
    void idleFlushBundlesTheWholeQueueIntoOneEvent() {
        // Two episodes queue while a task runs; the task's rail never opens
        // (dropped without a result elsewhere) and a third episode lands idle:
        // the flush ships ALL of it as one event, oldest first.
        llmActive = true;
        log.report("was attacked by a zombie and killed it");
        log.report("got hungry and ate a bread");
        llmActive = false;
        log.report("fled from a creeper to safety");
        assertEquals(1, emitted.size());
        String xml = emitted.get(0);
        int zombie = xml.indexOf("zombie");
        int bread = xml.indexOf("bread");
        int creeper = xml.indexOf("creeper");
        assertTrue(zombie >= 0 && bread > zombie && creeper > bread);
        assertTrue(log.isEmpty());
    }

    // ---- busy rail: entries wait for the tool result ----

    @Test
    void busyReportQueuesInsteadOfEmitting() {
        llmActive = true;
        log.report("was attacked by a skeleton and killed it");
        log.report("got hungry and ate a steak");
        assertTrue(emitted.isEmpty());
        assertEquals(2, log.size());
    }

    @Test
    void busyRailDrainsInOrderAndClears() {
        llmActive = true;
        log.report("first");
        log.report("second");
        assertEquals(List.of("first", "second"), log.drain());
        assertTrue(log.isEmpty());
    }

    // ---- single drain: the rails never double-deliver ----

    @Test
    void drainedEntriesNeverReflushAsAmbient() {
        llmActive = true;
        log.report("rode the tool result");
        log.drain();                 // busy rail took it
        log.flushAmbient();          // ambient rail finds nothing
        assertTrue(emitted.isEmpty());
    }

    @Test
    void flushedEntriesNeverReachTheNextDrain() {
        log.report("shipped as ambient");   // idle → flushed immediately
        assertEquals(1, emitted.size());
        assertTrue(log.drain().isEmpty());  // busy rail finds nothing
    }

    // ---- fallback flush: task terminated with no result ----

    @Test
    void fallbackFlushShipsStrandedEntries() {
        llmActive = true;
        log.report("was attacked by a witch and killed it");
        log.report("broke a 9-block fall with a hay bale");
        // Death drop: the no-result path calls flushAmbient directly — the
        // stranded entries transfer to the ambient rail in one event.
        log.flushAmbient();
        assertEquals(1, emitted.size());
        assertTrue(emitted.get(0).contains("witch"));
        assertTrue(emitted.get(0).contains("hay bale"));
        assertTrue(log.isEmpty());
    }

    // ---- transport refusal: owner offline ----

    @Test
    void refusedFlushKeepsEntriesForRetry() {
        sinkAccepts = false;
        log.report("nobody was listening");   // idle, but no client to receive
        assertTrue(emitted.isEmpty());
        assertEquals(1, log.size());          // held, not lost
        sinkAccepts = true;
        log.flushAmbient();                   // the idle-tick retry
        assertEquals(1, emitted.size());
        assertTrue(log.isEmpty());
    }

    // ---- hygiene ----

    @Test
    void boundedRingDropsTheOldest() {
        llmActive = true;
        for (int i = 1; i <= BodyLog.MAX_ENTRIES + 1; i++) {
            log.report("episode " + i);
        }
        List<String> lines = log.drain();
        assertEquals(BodyLog.MAX_ENTRIES, lines.size());
        assertEquals("episode 2", lines.get(0));   // "episode 1" fell off
        assertEquals("episode " + (BodyLog.MAX_ENTRIES + 1), lines.get(lines.size() - 1));
    }

    @Test
    void blankLinesAreIgnored() {
        log.report(null);
        log.report("");
        log.report("   ");
        assertTrue(log.isEmpty());
        assertTrue(emitted.isEmpty());
    }
}
