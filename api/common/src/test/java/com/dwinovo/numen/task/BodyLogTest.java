package com.dwinovo.numen.task;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the {@link BodyLog} producer core — no Minecraft (the transport
 * is a stub sink). Covers the 即报即发 contract (constitution §4, 收件箱修订版):
 * every report ships immediately as ONE {@code body_log} event; the only reason
 * entries linger is a refused transport (owner offline), and a later flush
 * retries them merged; the offline backlog is bounded.
 */
class BodyLogTest {

    private boolean sinkAccepts = true;
    private final List<String> emitted = new ArrayList<>();
    private final BodyLog log = new BodyLog(xml -> {
        if (!sinkAccepts) return false;
        emitted.add(xml);
        return true;
    });

    // ---- 即报即发 ----

    @Test
    void reportShipsImmediatelyAsOneEvent() {
        log.report("broke a 12-block fall with water");
        assertEquals(1, emitted.size());
        String xml = emitted.get(0);
        assertTrue(xml.startsWith("<event kind=\"body_log\">"));
        assertTrue(xml.endsWith("</event>"));
        assertTrue(xml.contains("broke a 12-block fall with water"));
        assertTrue(log.isEmpty());
    }

    @Test
    void everyReportIsItsOwnEventWhenTheSinkIsUp() {
        log.report("was attacked by a zombie and killed it");
        log.report("got hungry and ate a bread");
        assertEquals(2, emitted.size());
        assertTrue(emitted.get(0).contains("zombie"));
        assertTrue(emitted.get(1).contains("bread"));
        assertTrue(log.isEmpty());
    }

    // ---- 拒收滞留:主人离线 ----

    @Test
    void refusedReportIsHeldForRetry() {
        sinkAccepts = false;
        log.report("nobody was listening");
        assertTrue(emitted.isEmpty());
        assertEquals(1, log.size());          // held, not lost
        sinkAccepts = true;
        log.flush();                          // the idle-tick retry
        assertEquals(1, emitted.size());
        assertTrue(log.isEmpty());
    }

    @Test
    void offlineBacklogMergesIntoOneEventOnRetry() {
        sinkAccepts = false;
        log.report("was attacked by a witch and killed it");
        log.report("broke a 9-block fall with a hay bale");
        sinkAccepts = true;
        log.report("fled from a creeper to safety");   // this report flushes the whole box
        assertEquals(1, emitted.size());
        String xml = emitted.get(0);
        int witch = xml.indexOf("witch");
        int hay = xml.indexOf("hay bale");
        int creeper = xml.indexOf("creeper");
        assertTrue(witch >= 0 && hay > witch && creeper > hay);   // oldest first
        assertTrue(log.isEmpty());
    }

    @Test
    void boundedBacklogDropsTheOldestWhileOffline() {
        sinkAccepts = false;
        for (int i = 1; i <= BodyLog.MAX_ENTRIES + 1; i++) {
            log.report("episode " + i);
        }
        assertEquals(BodyLog.MAX_ENTRIES, log.size());
        sinkAccepts = true;
        log.flush();
        assertEquals(1, emitted.size());
        assertTrue(!emitted.get(0).contains("episode 1,")
                && !emitted.get(0).contains("episode 1;")
                && !emitted.get(0).endsWith("episode 1</event>"));   // "episode 1" fell off
        assertTrue(emitted.get(0).contains("episode 2"));
        assertTrue(emitted.get(0).contains("episode " + (BodyLog.MAX_ENTRIES + 1)));
    }

    // ---- hygiene ----

    @Test
    void flushOnEmptyBoxIsANoOp() {
        log.flush();
        assertTrue(emitted.isEmpty());
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
