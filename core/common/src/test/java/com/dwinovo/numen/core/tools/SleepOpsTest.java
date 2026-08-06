package com.dwinovo.numen.core.tools;

import net.minecraft.world.entity.player.Player;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 原版拒绝睡觉时,回执得说清"下一步该干什么"。
 *
 * <p>光把 {@code getMessage()} 那句话转发出去不够——那是给玩家看的("你现在不能休息"),
 * 模型据此不知道该等天黑、该清怪、还是该走近点。枚举名才是判据,翻译发生在这里。
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

    /** 每一种拒绝都得有自己的下一步,不能有漏到默认分支去的。 */
    @Test
    void everyRejectionTellsHerWhatToDoNext() {
        assumeTrue(booted);
        for (Player.BedSleepingProblem problem : Player.BedSleepingProblem.values()) {
            String text = SleepOps.explain(problem);
            assertTrue(text.length() > problem.name().length() + 20,
                    problem + " 只有原话没有下一步: " + text);
        }
    }

    @Test
    void daytimePointsAtWaitingRatherThanRetrying() {
        assumeTrue(booted);
        String text = SleepOps.explain(Player.BedSleepingProblem.NOT_POSSIBLE_NOW);
        assertTrue(text.contains("daytime"), text);
        assertTrue(text.contains("set_timer"), text);   // 重试无用,该等
    }

    @Test
    void monstersPointAtClearingThemNotAtAnotherBed() {
        assumeTrue(booted);
        String text = SleepOps.explain(Player.BedSleepingProblem.NOT_SAFE);
        assertTrue(text.contains("monsters"), text);
    }

    @Test
    void tooFarPointsAtGotoBecauseSleepDoesNotTravel() {
        assumeTrue(booted);
        String text = SleepOps.explain(Player.BedSleepingProblem.TOO_FAR_AWAY);
        assertTrue(text.contains("goto"), text);
    }

    /** 原版原话有就带上(跟着语言文件走,主人看得懂),枚举名任何时候都在。 */
    @Test
    void theVanillaWordingRidesAlongForTheOwner() {
        assumeTrue(booted);
        String text = SleepOps.explain(Player.BedSleepingProblem.NOT_SAFE);
        assertTrue(text.contains(Player.BedSleepingProblem.NOT_SAFE.name().toLowerCase(Locale.ROOT)),
                text);
        assertTrue(text.contains(Player.BedSleepingProblem.NOT_SAFE.getMessage().getString()), text);
    }

    /**
     * 有几种拒绝原版没配文案({@code getMessage()} 返回 null)。翻译不能因此炸——
     * 那会把"她睡不着"变成"工具报错",而模型对后者无从下手。
     */
    @Test
    void aRejectionWithNoVanillaWordingStillExplainsItself() {
        assumeTrue(booted);
        for (Player.BedSleepingProblem problem : Player.BedSleepingProblem.values()) {
            if (problem.getMessage() != null) {
                continue;
            }
            String text = SleepOps.explain(problem);
            assertFalse(text.isBlank(), problem.name());
            assertTrue(text.contains(problem.name().toLowerCase(Locale.ROOT)), text);
        }
    }
}
