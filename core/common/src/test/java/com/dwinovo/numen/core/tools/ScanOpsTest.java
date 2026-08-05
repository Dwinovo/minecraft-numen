package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.scan.ScanBlocksJob;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanOpsTest {

    private static ScanBlocksJob.ScanResult result(int scanned, int unloaded, int total, boolean deadlineHit) {
        return new ScanBlocksJob.ScanResult(List.of(), scanned, unloaded, total, deadlineHit, false);
    }

    @Test
    void aScanThatCoveredEverythingSaysNothingExtra() {
        assertNull(ScanOps.coverageNote(result(625, 0, 625, false)));
    }

    @Test
    void skippedColumnsReadAsUnknownNotAsEmpty() {
        String note = ScanOps.coverageNote(result(25, 600, 625, false));
        assertTrue(note.contains("600 of 625"), note);
        assertTrue(note.contains("UNKNOWN, not absent"), note);
    }

    @Test
    void aDeadlineSaysHowFarTheWalkGot() {
        String note = ScanOps.coverageNote(result(180, 0, 625, true));
        assertTrue(note.contains("180/625"), note);
        assertTrue(note.contains("time budget"), note);
    }

    /** Both limits can bite in one scan; neither may silently swallow the other. */
    @Test
    void aDeadlineDoesNotHideTheUnsearchedColumns() {
        String note = ScanOps.coverageNote(result(180, 300, 625, true));
        assertTrue(note.contains("time budget"), note);
        assertTrue(note.contains("300 of 625"), note);
    }

    /**
     * Stopping early is a proof, not a shortcut: the nearest ones are already closer than
     * anything the unwalked rings could hold, so warning about coverage would be a lie.
     */
    @Test
    void stoppingEarlyOnTheRingBoundWarnsAboutNothing() {
        assertNull(ScanOps.coverageNote(
                new ScanBlocksJob.ScanResult(List.of(), 41, 0, 625, false, true)));
    }

    /** Unloaded ground is still unloaded even when the quota was met early. */
    @Test
    void stoppingEarlyStillReportsGroundNobodyLookedAt() {
        String note = ScanOps.coverageNote(
                new ScanBlocksJob.ScanResult(List.of(), 41, 12, 625, false, true));
        assertTrue(note.contains("12 of 625"), note);
    }
}
