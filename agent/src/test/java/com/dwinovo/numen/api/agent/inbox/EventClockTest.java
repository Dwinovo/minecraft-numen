package com.dwinovo.numen.api.agent.inbox;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 条目上的游戏日期与钟点怎么写。 */
class EventClockTest {

    @Test
    void gameClockStartsAtSixInTheMorning() {
        // 原版 0 刻 = 早上 6 点;模型看 "18:20" 才知道天要黑了
        assertEquals("06:00", EventClock.clockOf(0L));
        assertEquals("12:00", EventClock.clockOf(6000L));
        assertEquals("18:00", EventClock.clockOf(12000L));
        assertEquals("00:00", EventClock.clockOf(18000L));
    }

    @Test
    void clockWrapsAcrossDaysAndSurvivesNegativeTime() {
        assertEquals("06:00", EventClock.clockOf(24000L), "第二天早上还是 6 点");
        assertEquals("12:00", EventClock.clockOf(24000L * 7 + 6000L));
        assertEquals("06:00", EventClock.clockOf(-24000L), "/time set 能把它调成负数");
    }

    @Test
    void stampIsTheDayAndTheClock() {
        assertEquals(" day=\"3\" t=\"21:15\"", EventClock.stamp(3 * 24000L + 15250L));
    }
}
