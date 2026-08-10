package com.dwinovo.numen.agent.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 长期目标的记账。
 *
 * <p>目标没有状态机——只有"在"和"不在"。所以这层要钉的只有三件事:计数对不对、什么时候
 * 该放弃、过一圈磁盘还是不是原来那个。
 */
class GoalStateTest {

    private static final long T0 = 1_000_000L;
    private static final long MIN = 60_000L;

    private static GoalState goal() {
        return GoalState.of("把家门口那片林子清干净", T0);
    }

    @Test
    void aFreshGoalHasRunNoTurns() {
        GoalState g = goal();
        assertEquals(0, g.turnsExecuted());
        assertTrue(g.hasTurnsLeft());
        assertEquals(0, g.tokensUsed());
    }

    @Test
    void objectiveIsTrimmedAndCapped() {
        assertEquals("挖矿", GoalState.of("  挖矿  ", T0).objective());
        String huge = "x".repeat(GoalState.MAX_OBJECTIVE_CHARS + 500);
        assertEquals(GoalState.MAX_OBJECTIVE_CHARS, GoalState.of(huge, T0).objective().length());
    }

    @Test
    void elapsedIsJustSubtractionBecauseThereIsNoPausing() {
        assertEquals(5 * MIN, goal().elapsedMs(T0 + 5 * MIN));
        assertEquals(0, goal().elapsedMs(T0 - 999), "时钟倒退也不给负数");
    }

    @Test
    void turnsRunOut() {
        GoalState g = goal();
        for (int i = 0; i < GoalState.MAX_GOAL_TURNS - 1; i++) {
            g.countTurn();
        }
        assertTrue(g.hasTurnsLeft(), "还差一轮");
        g.countTurn();
        assertFalse(g.hasTurnsLeft(), "到顶了 —— 上层据此收工,不是闷头继续");
    }

    @Test
    void oneBlockReportIsNotEnoughToGiveUp() {
        // 一次挡住可能只是这一步没走通,换个法子还能继续;每次都放弃才是烦人
        GoalState g = goal();
        assertFalse(g.reportBlocked("够不着"));
        assertFalse(g.reportBlocked("够不着"));
        assertTrue(g.reportBlocked("够不着"), "同一堵墙撞够 3 次才放弃");
    }

    @Test
    void aDifferentReasonRestartsTheCount() {
        GoalState g = goal();
        g.reportBlocked("够不着");
        g.reportBlocked("够不着");
        assertFalse(g.reportBlocked("没材料了"), "换了堵墙,重新数");
        assertEquals(1, g.blockedAttempts());
        assertEquals("没材料了", g.lastBlockReason());
    }

    @Test
    void tokensOnlyEverGoUp() {
        GoalState g = goal();
        g.addTokens(500);
        g.addTokens(-9999);
        g.addTokens(300);
        assertEquals(800, g.tokensUsed());
    }

    @Test
    void aGoalSurvivesARoundTripThroughDisk() {
        GoalState g = goal();
        g.countTurn();
        g.addTokens(1234);
        g.reportBlocked("没有梯子");

        GoalState back = GoalState.fromJson(g.toJson());
        assertNotNull(back);
        assertEquals(g.objective(), back.objective());
        assertEquals(1, back.turnsExecuted());
        assertEquals(1234, back.tokensUsed());
        assertEquals(1, back.blockedAttempts());
        assertEquals("没有梯子", back.lastBlockReason());
        assertEquals(g.elapsedMs(T0 + 9 * MIN), back.elapsedMs(T0 + 9 * MIN),
                "起点过了磁盘一圈还得对得上");
    }

    @Test
    void anEmptyObjectiveIsNoGoalAtAll() {
        assertNull(GoalState.fromJson(null));
        assertNull(GoalState.fromJson(GoalState.of("", T0).toJson()));
        assertNull(GoalState.fromJson(GoalState.of("   ", T0).toJson()));
    }

    // ---- 续跑块 ----

    @Test
    void elapsedReadsLikeSomethingAHumanWouldSay() {
        assertEquals("45s", GoalPrompts.elapsed(45_000L));
        assertEquals("5min", GoalPrompts.elapsed(5 * MIN));
        assertEquals("1h22min", GoalPrompts.elapsed(82 * MIN));
    }

    @Test
    void theContinuationBlockCarriesTheObjectiveAndProgress() {
        GoalState g = goal();
        g.countTurn();
        g.addTokens(500);
        String p = GoalPrompts.continuation(g, T0 + 3 * MIN);

        assertTrue(p.startsWith("<goal-steering type=\"continuation\">"), p);
        assertTrue(p.endsWith("</goal-steering>"), p);
        assertTrue(p.contains(g.objective()));
        assertTrue(p.contains("Continuation turns executed: 1"), p);
        assertTrue(p.contains("Tokens used: 500"), p);
        assertTrue(p.contains("3min"), p);
    }

    @Test
    void everyContinuationIsBuiltFreshSoProgressIsNeverStale() {
        // 每轮重拼的意义就在这儿:拼一次常驻的话,她永远以为自己还在第 1 轮
        GoalState g = goal();
        g.countTurn();
        String first = GoalPrompts.continuation(g, T0);
        g.countTurn();
        String second = GoalPrompts.continuation(g, T0 + MIN);

        assertTrue(first.contains("turns executed: 1"), first);
        assertTrue(second.contains("turns executed: 2"), second);
    }
}
