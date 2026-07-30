package com.dwinovo.numen.core.scaffold;

import org.junit.jupiter.api.Test;

public final class TemporaryScaffoldReclaimProgressTest {
    @Test
    void finishesCompleteBlockedOrStalledWithoutLoopingForever() {
        TemporaryScaffoldReclaimProgress progress = new TemporaryScaffoldReclaimProgress(4, 100L);

        require(
            progress.observe(4, true, 699L) == TemporaryScaffoldReclaimProgress.State.RUNNING,
            "an actionable cleanup may keep navigating within the bounded no-progress window"
        );
        require(
            progress.observe(3, true, 699L) == TemporaryScaffoldReclaimProgress.State.RUNNING,
            "removing a tracked block must reset the no-progress window"
        );
        require(progress.reclaimed(3) == 1, "the result must count only removed ledger entries");
        require(
            progress.observe(3, false, 700L) == TemporaryScaffoldReclaimProgress.State.BLOCKED,
            "a sweep with only unsafe or unreachable entries must end instead of waiting forever"
        );

        TemporaryScaffoldReclaimProgress stalled = new TemporaryScaffoldReclaimProgress(2, 1_000L);
        require(
            stalled.observe(2, true, 1_600L) == TemporaryScaffoldReclaimProgress.State.STALLED,
            "an apparently actionable sweep must stop after 30 seconds without removing an entry"
        );
        require(
            stalled.observe(0, false, 1_601L) == TemporaryScaffoldReclaimProgress.State.COMPLETE,
            "an empty ledger must always finish successfully"
        );
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
