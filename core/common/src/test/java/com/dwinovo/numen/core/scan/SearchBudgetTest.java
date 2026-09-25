package com.dwinovo.numen.core.scan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每刻的时间上限只数搜索自己花的时间:两段搜索之间世界在跑的那些毫秒不记在后一段的账上,
 * 否则排在后面的搜索(方块搜索在刻末,定位在实体刻里)会被前面的世界刻白白吃掉额度。
 */
class SearchBudgetTest {

    @Test
    void timeBetweenSlicesIsNotChargedToTheNextOne() throws InterruptedException {
        try (SearchBudget.Slice first = SearchBudget.slice(1)) {
            assertTrue(SearchBudget.withinTime());
        }
        Thread.sleep(6);   // 比整刻的上限还长:这段是世界在跑,不是搜索
        try (SearchBudget.Slice second = SearchBudget.slice(1)) {
            assertTrue(SearchBudget.withinTime());
        }
    }

    @Test
    void timeSpentInsideSlicesAddsUpWithinATick() {
        try (SearchBudget.Slice first = SearchBudget.slice(2)) {
            spin(2_500_000L);
        }
        try (SearchBudget.Slice second = SearchBudget.slice(2)) {
            spin(2_000_000L);
            assertFalse(SearchBudget.withinTime());
        }
        try (SearchBudget.Slice nextTick = SearchBudget.slice(3)) {
            assertTrue(SearchBudget.withinTime());
        }
    }

    @Test
    void searchWorkOutsideASliceIsABug() {
        assertThrows(IllegalStateException.class, SearchBudget::withinTime);
    }

    private static void spin(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }
}
