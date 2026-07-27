package com.dwinovo.numen.core.mining;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.mining.MiningAttemptLedger;
import com.dwinovo.numen.core.mining.MiningAttemptLedger.Decision;
import com.dwinovo.numen.core.mining.MiningGeometry.Point;

public final class MiningAttemptLedgerTest {
    @Test
    void verifiedRuntimeBehavior() {
        MiningAttemptLedger ledger = new MiningAttemptLedger(3);
        Point first = new Point(10, 70, 10);
        Point second = new Point(30, 72, -5);

        expect(Decision.DEFER, ledger.recordFailure(first, "no path"), "first attempt");
        expectTrue(ledger.isDeferred(first), "failed target must yield to other targets");
        ledger.startNextRound();
        expectFalse(ledger.isDeferred(first), "new round must retry deferred targets");

        expect(Decision.DEFER, ledger.recordFailure(first, "still blocked"), "second attempt");
        ledger.startNextRound();
        expect(Decision.FINAL_FAILURE, ledger.recordFailure(first, "no legal stance"), "third attempt");
        expect(Decision.DEFER, ledger.recordFailure(second, "hazard"), "other target first attempt");
        ledger.startNextRound();
        expect(Decision.DEFER, ledger.recordFailure(second, "hazard"), "other target second attempt");
        ledger.startNextRound();
        expect(Decision.FINAL_FAILURE, ledger.recordFailure(second, "protected"), "other target third attempt");

        expect(2, ledger.failures().size(), "every final target failure must be retained");
        expect("no legal stance", ledger.failures().get(first), "latest first-target reason");
        expect("protected", ledger.failures().get(second), "latest second-target reason");
        expectTrue(MiningAttemptLedger.isComplete(3, 3), "all requested targets completed");
        expectFalse(MiningAttemptLedger.isComplete(2, 3), "partial work must never report complete");
    }

    private static void expect(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void expectTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void expectFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }
}
