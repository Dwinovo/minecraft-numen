package com.dwinovo.numen.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 界面里搬一步之前:点名的格在不在眼前这张界面里,不在就跳过这一步、说可点的格是哪些,什么都不动。 */
class ContainerOpsTest {

    /** 什么都没开时眼前是她自己的背包界面(46 格):点第 90 格越界,说 0..45、去看 numen.gui.view()。 */
    @Test
    void aSlotBeyondTheOpenMenuIsSkippedAndSaysTheRange() {
        assertEquals("from slot 90 OUT OF RANGE (0..45) — skipped; numen.gui.view() shows the indices.",
                ContainerOps.outOfRange(new ContainerOps.Move(90, null, null), 46));
    }

    @Test
    void aTargetSlotBeyondTheMenuIsSkippedToo() {
        assertEquals("to slot -1 OUT OF RANGE (0..45) — skipped; numen.gui.view() shows the indices.",
                ContainerOps.outOfRange(new ContainerOps.Move(3, -1, null), 46));
    }

    @Test
    void slotsInsideTheMenuGoAhead() {
        assertNull(ContainerOps.outOfRange(new ContainerOps.Move(0, 45, 1), 46));
    }
}
