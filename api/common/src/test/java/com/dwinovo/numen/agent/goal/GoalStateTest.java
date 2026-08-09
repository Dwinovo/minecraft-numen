package com.dwinovo.numen.agent.goal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标的状态机与计时。
 *
 * <p>这层决定"她会不会一直跑下去":只有 {@code ACTIVE} 驱动续跑,所以每条转换都得说清
 * 从哪儿能到哪儿,不能到的要明确拒绝——含糊会变成"停不下来"或者"接不回来"。
 */
class GoalStateTest {

    private static final long T0 = 1_000_000L;
    private static final long MIN = 60_000L;

    private static GoalState active() {
        return GoalState.of("把家门口那片林子清干净", T0);
    }

    @Test
    void aFreshGoalIsActiveAndHasRunNoTurns() {
        GoalState g = active();
        assertEquals(GoalStatus.ACTIVE, g.status());
        assertTrue(g.isActive());
        assertEquals(0, g.turnsExecuted());
        assertTrue(g.hasTurnsLeft());
    }

    @Test
    void objectiveIsTrimmedAndCapped() {
        assertEquals("挖矿", GoalState.of("  挖矿  ", T0).objective());
        String huge = "x".repeat(GoalState.MAX_OBJECTIVE_CHARS + 500);
        assertEquals(GoalState.MAX_OBJECTIVE_CHARS, GoalState.of(huge, T0).objective().length());
    }

    // ---- 计时:算干活的时长,不算挂机 ----

    @Test
    void pausedTimeDoesNotCount() {
        GoalState g = active();
        assertEquals(5 * MIN, g.activeElapsedMs(T0 + 5 * MIN));

        g.pause(T0 + 5 * MIN);
        assertEquals(5 * MIN, g.activeElapsedMs(T0 + 99 * MIN), "暂停期间不涨");

        g.resume(T0 + 100 * MIN);
        assertEquals(5 * MIN, g.activeElapsedMs(T0 + 100 * MIN), "刚恢复还是那么多");
        assertEquals(7 * MIN, g.activeElapsedMs(T0 + 102 * MIN), "接着涨");
    }

    @Test
    void completingFreezesTheClock() {
        GoalState g = active();
        g.complete(T0 + 3 * MIN);
        assertEquals(3 * MIN, g.activeElapsedMs(T0 + 999 * MIN));
    }

    // ---- 转换 ----

    @Test
    void onlyActiveGoalsPause() {
        GoalState g = active();
        assertTrue(g.pause(T0));
        assertFalse(g.pause(T0), "已经暂停了");
    }

    @Test
    void resumeAcceptsPausedAndBlockedButNotComplete() {
        GoalState paused = active();
        paused.pause(T0);
        assertTrue(paused.resume(T0 + MIN));

        GoalState blocked = active();
        for (int i = 0; i < GoalState.BLOCKED_CONSECUTIVE_THRESHOLD; i++) {
            blocked.reportBlocked("没有梯子", T0);
        }
        assertEquals(GoalStatus.BLOCKED, blocked.status());
        assertTrue(blocked.resume(T0 + MIN));
        assertEquals(0, blocked.blockedAttempts(), "恢复了就重新数");

        GoalState done = active();
        done.complete(T0);
        assertFalse(done.resume(T0 + MIN), "做完了就是终点,接不回来");
    }

    @Test
    void oneBlockReportIsNotBlockedYet() {
        // 一次挡住可能只是这一步没走通,换个法子还能继续;每次都停下来问主人才是烦人
        GoalState g = active();
        assertFalse(g.reportBlocked("够不着", T0));
        assertFalse(g.reportBlocked("够不着", T0));
        assertTrue(g.reportBlocked("够不着", T0), "同一堵墙撞够次数才算");
        assertEquals(GoalStatus.BLOCKED, g.status());
    }

    @Test
    void aDifferentReasonRestartsTheCount() {
        GoalState g = active();
        g.reportBlocked("够不着", T0);
        g.reportBlocked("够不着", T0);
        assertFalse(g.reportBlocked("没材料了", T0), "换了堵墙,重新数");
        assertEquals(1, g.blockedAttempts());
        assertEquals(GoalStatus.ACTIVE, g.status());
    }

    @Test
    void turnsRunOutAndContinueGivesAFreshBudget() {
        GoalState g = active();
        for (int i = 0; i < GoalState.MAX_GOAL_TURNS; i++) {
            g.countTurn(T0);
        }
        assertFalse(g.hasTurnsLeft());
        assertTrue(g.markMaxTurns(T0));
        assertEquals(GoalStatus.MAX_TURNS, g.status());
        assertFalse(g.resume(T0), "跑够轮次不是暂停,得用 continue");

        assertTrue(g.continueFromMaxTurns(T0 + MIN));
        assertEquals(GoalStatus.ACTIVE, g.status());
        assertEquals(0, g.turnsExecuted());
        assertTrue(g.hasTurnsLeft());
    }

    @Test
    void onlyActiveGoalsHitTheTurnCeiling() {
        GoalState g = active();
        g.pause(T0);
        assertFalse(g.markMaxTurns(T0));
    }

    // ---- 落盘 ----

    @Test
    void aGoalSurvivesARoundTripThroughDisk() {
        GoalState g = active();
        g.countTurn(T0);
        g.addTokens(1234, T0);
        g.reportBlocked("没有梯子", T0);
        g.pause(T0 + 2 * MIN);

        GoalState back = GoalState.fromJson(g.toJson());
        assertNotNull(back);
        assertEquals(g.objective(), back.objective());
        assertEquals(GoalStatus.PAUSED, back.status());
        assertEquals(1, back.turnsExecuted());
        assertEquals(1234, back.tokensUsed());
        assertEquals("没有梯子", back.lastBlockReason());
        assertEquals(g.activeElapsedMs(T0 + 99 * MIN), back.activeElapsedMs(T0 + 99 * MIN),
                "暂停着的时长过了磁盘一圈还得一样");
    }

    @Test
    void anEmptyObjectiveIsNoGoalAtAll() {
        assertNull(GoalState.fromJson(null));
        assertNull(GoalState.fromJson(GoalState.of("", T0).toJson()));
    }

    @Test
    void anUnknownStatusOnDiskLandsOnPausedRatherThanRunningAway() {
        // 存档来自别的版本/被人手改过:宁可停着等主人,也不能自己跑起来
        var json = active().toJson();
        json.addProperty("status", "some_future_state");
        assertEquals(GoalStatus.PAUSED, GoalState.fromJson(json).status());
    }

    @Test
    void elapsedReadsLikeSomethingAHumanWouldSay() {
        assertEquals("45s", GoalPrompts.elapsed(45_000L));
        assertEquals("5min", GoalPrompts.elapsed(5 * MIN));
        assertEquals("1h22min", GoalPrompts.elapsed(82 * MIN));
    }

    @Test
    void theContinuationBlockCarriesTheObjectiveAndProgress() {
        GoalState g = active();
        g.countTurn(T0);
        g.addTokens(500, T0);
        String p = GoalPrompts.continuation(g, T0 + 3 * MIN);

        assertTrue(p.startsWith("<goal-steering type=\"continuation\">"), p);
        assertTrue(p.endsWith("</goal-steering>"), p);
        assertTrue(p.contains(g.objective()));
        assertTrue(p.contains("Continuation turns executed: 1"), p);
        assertTrue(p.contains("Tokens used: 500"), p);
        assertTrue(p.contains("3min"), p);
    }
}
