package com.dwinovo.numen.api.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 谁盯上了她:新盯上的报一次,盯着期间不再报,放弃又盯上的抖动按她挨打同一把窗口去抖。 */
class TargetingWatchTest {

    private final TargetingWatch<String> watch = new TargetingWatch<>();

    @Test
    void aNewHunterIsReportedOnceAndNotWhileItKeepsHunting() {
        assertEquals(List.of(), watch.poll(List.of(), 0));
        assertEquals(List.of("zombie"), watch.poll(List.of("zombie"), 1));
        assertEquals(List.of(), watch.poll(List.of("zombie"), 2));
        assertEquals(List.of(), watch.poll(List.of("zombie"), 500), "盯多久都不再报");
    }

    @Test
    void eachHunterIsItsOwnReport() {
        assertEquals(List.of("zombie"), watch.poll(List.of("zombie"), 1));
        assertEquals(List.of("skeleton"), watch.poll(List.of("zombie", "skeleton"), 2));
    }

    @Test
    void droppingAndRetargetingInsideTheQuietWindowIsNotReportedAgain() {
        assertEquals(List.of("zombie"), watch.poll(List.of("zombie"), 0));
        assertEquals(List.of(), watch.poll(List.of(), 10));
        assertEquals(List.of(), watch.poll(List.of("zombie"), 20), "抖动:窗口里又盯上");
        assertEquals(List.of(), watch.poll(List.of("zombie"), 300), "窗口过后它若还盯着,也不补发");
    }

    @Test
    void retargetingAfterTheQuietWindowIsReportedAgain() {
        assertEquals(List.of("zombie"), watch.poll(List.of("zombie"), 0));
        assertEquals(List.of(), watch.poll(List.of(), 10));
        assertEquals(List.of("zombie"), watch.poll(List.of("zombie"), CompanionHurtWatch.QUIET_TICKS + 1));
    }
}
