package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.seat.BodyTask;
import com.dwinovo.numen.api.seat.SeatState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** "她此刻在干什么":后台活优先;没有就是手上这个工具调用,工具有一句话说它读作什么就说那一句,否则说工具名。 */
class BodyMirrorTest {

    @Test
    void aRunningCallReadsAsItsSummaryWhenItHasOne() {
        assertEquals(new LoopStatus.Doing("numen.activity.default", List.of("Mine the iron vein")),
                new BodyMirror().doing("lua", "Mine the iron vein"));
    }

    @Test
    void aRunningCallWithNothingToSayReadsAsItsToolName() {
        assertEquals(new LoopStatus.Doing("numen.tool.lua", List.of("lua")), new BodyMirror().doing("lua", ""));
    }

    @Test
    void noCallAndNoJobIsNothing() {
        assertNull(new BodyMirror().doing(null, ""));
    }

    @Test
    void aBodyJobComesFirst() {
        BodyMirror mirror = new BodyMirror();
        mirror.take(new SeatState("", new BodyTask("t1", "numen.work.dig", false, "numen.activity.dig", List.of("oak_log")), 0, 0));
        assertEquals(new LoopStatus.Doing("numen.activity.dig", List.of("oak_log")), mirror.doing("lua", "Mine the iron vein"));
    }
}
