package com.dwinovo.numen.api.network;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UplinkBudgetTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final long SECOND = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong();
    private final UplinkBudget budget = new UplinkBudget(1000, 100, clock::get);

    @Test
    void aBurstUpToTheBucketIsAdmittedAndTheNextByteIsNot() {
        assertTrue(budget.admit(A, 600));
        assertTrue(budget.admit(A, 400));
        assertFalse(budget.admit(A, 1), "桶空了,多一个字节也不收");
    }

    @Test
    void theBucketRefillsAtTheSustainedRateAndNeverPastItsCapacity() {
        budget.admit(A, 1000);
        clock.addAndGet(5 * SECOND);
        assertTrue(budget.admit(A, 500), "5 秒回 500 字节");
        assertFalse(budget.admit(A, 1));

        clock.addAndGet(3600 * SECOND);
        assertTrue(budget.admit(A, 1000));
        assertFalse(budget.admit(A, 1), "歇多久也只回到桶满");
    }

    @Test
    void aRefusedBurstTakesNothingAndEachPlayerHasHisOwnBucket() {
        budget.admit(A, 900);
        assertFalse(budget.admit(A, 200));
        assertTrue(budget.admit(A, 100), "被拒的那次没有扣");
        assertTrue(budget.admit(B, 1000), "别的玩家不受影响");
    }

    @Test
    void aPlayerWhoLeftComesBackWithAFullBucket() {
        budget.admit(A, 1000);
        budget.forget(A);
        assertTrue(budget.admit(A, 1000));
    }
}
