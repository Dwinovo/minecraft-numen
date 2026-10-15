package com.dwinovo.numen.api.task;

import com.dwinovo.numen.api.entity.NumenPlayer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 空闲姿态层:各管一面、互不排斥,能做的都做,按登记序;丢掉身体时在做的各自收尾。 */
class PosesTest {

    private static final class Pose implements Task {
        private final String id;
        private final List<String> log;
        boolean ready;

        Pose(String id, boolean ready, List<String> log) {
            this.id = id;
            this.ready = ready;
            this.log = log;
        }

        @Override public boolean canRun(NumenPlayer companion) {
            return ready;
        }

        @Override public TaskState tick(NumenPlayer companion) {
            log.add("tick " + id);
            return TaskState.RUNNING;
        }

        @Override public void stop(NumenPlayer companion, StopReason why) {
            log.add("stop " + id);
        }

        @Override public String name() {
            return id;
        }
    }

    @Test
    void everyPoseThatCanRunActsInRegistrationOrder() {
        List<String> log = new ArrayList<>();
        Poses poses = new Poses(List.of(new Pose("看人", true, log), new Pose("漂浮", true, log)));
        assertTrue(poses.canRun(null));
        poses.tick(null);
        assertEquals(List.of("tick 看人", "tick 漂浮"), log, "两面同时成立就都做,后登记的后按键");
    }

    @Test
    void aPoseThatCannotRunIsSkippedAndTheLayerSleepsWhenNoneCan() {
        List<String> log = new ArrayList<>();
        Pose look = new Pose("看人", false, log);
        Pose swim = new Pose("漂浮", true, log);
        Poses poses = new Poses(List.of(look, swim));
        poses.tick(null);
        assertEquals(List.of("tick 漂浮"), log);
        swim.ready = false;
        assertFalse(poses.canRun(null));
    }

    @Test
    void aPoseThatStopsBeingNeededWindsDownAndLosingTheBodyWindsDownTheRest() {
        List<String> log = new ArrayList<>();
        Pose look = new Pose("看人", true, log);
        Pose swim = new Pose("漂浮", true, log);
        Poses poses = new Poses(List.of(look, swim));
        poses.tick(null);
        look.ready = false;
        log.clear();
        poses.tick(null);
        assertEquals(List.of("tick 漂浮", "stop 看人"), log, "不再成立的那一面当刻收尾");
        log.clear();
        poses.stop(null, Task.StopReason.PREEMPTED);
        assertEquals(List.of("stop 漂浮"), log, "丢掉身体时只让还在做的收尾");
    }
}
