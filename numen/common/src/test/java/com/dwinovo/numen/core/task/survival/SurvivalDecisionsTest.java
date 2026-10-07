package com.dwinovo.numen.core.task.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生存反射的纯判据——不碰 Minecraft。
 *
 * <p>每条只回答一个问题:<b>现在该不该抢身体</b>。不返回浮点"我多想要"再挑最大的:
 * 反射之间的先后是<b>固定的</b>(摔落永远比脱困急),不随世界状态变,用连续量表达一个
 * 固定序只会得到一堆没人看得懂的魔法数。先后写在注册号上(见 {@code ReflexOrderTest}),
 * 这里只剩触发。
 */
class SurvivalDecisionsTest {

    // ---- 进食 ----






    // ---- 有没有威胁 ----
    // 「打还是跑」不在这一层了:它按护甲折算的有效血量判,和"够不够得着""该不该贴近"
    // 一起归 AttackPlan —— 战斗只有一份判据。这里只剩"要不要醒过来"。

    @Test
    void noThreatDoesNotWake() {
        assertFalse(SurvivalDecisions.mobDefenseTriggered(false));
    }

    @Test
    void aThreatWakesTheChain() {
        assertTrue(SurvivalDecisions.mobDefenseTriggered(true));
    }

    // ---- 摔落缓冲 ----

    @Test
    void groundedNeverSaves() {
        // 落地、踩水、抓着梯子 —— 都不是"在摔"
        assertFalse(SurvivalDecisions.mlgTriggered(true, -3.0, true));
    }

    @Test
    void slowDescentNeverSaves() {
        // 走下台阶、慢慢沉:掉得不够快就不该抢身体
        assertFalse(SurvivalDecisions.mlgTriggered(false, -0.3, true));
        assertFalse(SurvivalDecisions.mlgTriggered(false, 0.0, true));
    }

    @Test
    void fastFallWithAMeansSaves() {
        assertTrue(SurvivalDecisions.mlgTriggered(false, -3.0, true));
        assertTrue(SurvivalDecisions.mlgTriggered(false, SurvivalDecisions.MLG_FALL_SPEED, true));
    }

    @Test
    void nothingToSaveWithMeansNoSave() {
        // 手上没水桶也没软方块:抢了身体也救不了自己,身体该留给别的反射
        assertFalse(SurvivalDecisions.mlgTriggered(false, -3.0, false));
    }

    @Test
    void settledSpeedIsSlowerThanTheFallTrigger() {
        // 两条线必须分得开:还在自由落体时不能被当成"落进水里了,可以收桶"
        assertTrue(SurvivalDecisions.MLG_SETTLED_SPEED > SurvivalDecisions.MLG_FALL_SPEED);
    }

    // ---- 换气 ----

    @Test
    void lowAirUnderwaterSurfaces() {
        assertTrue(SurvivalDecisions.breathTriggered(true, SurvivalDecisions.LOW_AIR_TICKS));
        assertTrue(SurvivalDecisions.breathTriggered(true, 0));
    }

    @Test
    void plentyOfAirDoesNotSurface() {
        assertFalse(SurvivalDecisions.breathTriggered(true, 300));
    }

    @Test
    void headAboveWaterDoesNotSurface() {
        // 头一出水面立刻不触发:氧气自己会回,再占着身体就成了在水面发呆
        assertFalse(SurvivalDecisions.breathTriggered(false, 0));
    }
}
