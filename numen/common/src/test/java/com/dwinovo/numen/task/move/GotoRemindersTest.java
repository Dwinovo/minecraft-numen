package com.dwinovo.numen.task.move;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 去处写错时的提醒怎么组成:说事实,再给能照抄的写法;每一句里的 {@code numen.move.walk} 都是完整的一次调用,坐标加距离。
 */
class GotoRemindersTest {

    private static final BlockPos FURNACE = new BlockPos(120, 64, -35);

    /** 点名的那一格站到哪儿都挖不成(基岩挖不动):说它是什么、在哪、为什么。 */
    @Test
    void aBlockNothingCanBreakSaysItCantBeDugAndWhy() {
        String said = GotoReminders.cantDig(FURNACE, "bedrock",
                com.dwinovo.numen.nav.NavText.refused(com.dwinovo.numen.pathing.plan.Reason.UNBREAKABLE, null));
        assertTrue(said.startsWith("bedrock at 120,64,-35") && said.contains("can't be dug: it can't be broken"), said);
    }
}
