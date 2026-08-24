package com.dwinovo.numen.core.tools;

import net.minecraft.world.entity.player.Player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 拒绝理由怎么交给模型。
 *
 * <p>原版那几句本来就具体("there are monsters nearby"、"the bed is too far away"),所以
 * 原样转发,不自己再翻一遍——翻一遍就得跟着原版措辞走,那是要过期的硬编码。这里钉的是
 * 「原话原样送到」和「没配文案的那种不能炸」。
 */
@Tag("mc")
class SleepOpsTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    /** BedSleepingProblem 是 record,没有 values()——具名常量逐个点名。 */
    private static List<Player.BedSleepingProblem> constants() {
        return List.of(Player.BedSleepingProblem.TOO_FAR_AWAY,
                Player.BedSleepingProblem.OBSTRUCTED,
                Player.BedSleepingProblem.OTHER_PROBLEM,
                Player.BedSleepingProblem.NOT_SAFE);
    }

    /** 配了文案的,一个字不改地送出去。 */
    @Test
    void vanillaWordingIsPassedThroughUntouched() {
        assumeTrue(booted);
        for (Player.BedSleepingProblem problem : constants()) {
            if (problem.message() == null) {
                continue;
            }
            assertEquals(problem.message().getString(), SleepOps.explain(problem), problem.toString());
        }
    }

    /**
     * {@code OTHER_PROBLEM} 原版没配文案。不挡就是 NPE——那会把"她睡不着"变成
     * "工具报错",而模型对后者无从下手。
     */
    @Test
    void aRejectionWithNoVanillaWordingStillSaysSomethingUsable() {
        assumeTrue(booted);
        int checked = 0;
        for (Player.BedSleepingProblem problem : constants()) {
            if (problem.message() != null) {
                continue;
            }
            checked++;
            String text = SleepOps.explain(problem);
            assertFalse(text.isBlank(), problem.toString());
        }
        assertTrue(checked > 0, "原版不再有无文案的拒绝了?那这条守卫可以退役");
    }

    /** 每一种都得有话说,不能有漏到空串的。 */
    @Test
    void everyRejectionHasWords() {
        assumeTrue(booted);
        for (Player.BedSleepingProblem problem : constants()) {
            assertFalse(SleepOps.explain(problem).isBlank(), problem.toString());
        }
    }
}
