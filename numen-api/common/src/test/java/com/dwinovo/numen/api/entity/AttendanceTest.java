package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.entity.Attendance.Move;
import com.dwinovo.numen.api.entity.Attendance.Outside;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 她该不该在线的判据表:该在线 = 主人在场 且 没有被留在外面;世界与它不一致时往该有的样子修。 */
class AttendanceTest {

    @Test
    void ownerPresentAndNotOutsideMeansOnline() {
        assertEquals(Move.BRING, Attendance.move(Outside.NONE, true, false));
        assertEquals(Move.STAY, Attendance.move(Outside.NONE, true, true));
    }

    @Test
    void ownerAbsentMeansLoggedOutWhateverElse() {
        assertEquals(Move.LOG_OUT, Attendance.move(Outside.NONE, false, true));
        assertEquals(Move.STAY, Attendance.move(Outside.NONE, false, false));
    }

    @Test
    void leftOutsideStaysOutEvenWithTheOwnerThere() {
        for (Outside outside : new Outside[] {Outside.byOwner(), Outside.byServer("cheating at cards")}) {
            assertEquals(Move.STAY, Attendance.move(outside, true, false), "谁也不替主人把她带回来:" + outside);
            assertEquals(Move.LOG_OUT, Attendance.move(outside, true, true), "还在世界里的要登出:" + outside);
        }
    }

    @Test
    void leftOutsideAndOwnerAbsentStaysOut() {
        for (Outside outside : new Outside[] {Outside.byOwner(), Outside.byServer("x")}) {
            assertEquals(Move.STAY, Attendance.move(outside, false, false));
            assertEquals(Move.LOG_OUT, Attendance.move(outside, false, true));
        }
    }
}
