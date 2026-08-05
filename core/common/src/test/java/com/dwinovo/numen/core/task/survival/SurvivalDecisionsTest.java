package com.dwinovo.numen.core.task.survival;

import com.dwinovo.numen.core.task.survival.SurvivalDecisions.ThreatResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生存反射的纯判据——不碰 Minecraft。
 *
 * <p>每条只回答一个问题:<b>现在该不该抢身体</b>。从前它们返回一个浮点"我多想要",
 * 调度器挑最大的;但反射之间的先后是<b>固定的</b>(摔落永远比脱困急),不随世界状态变。
 * 用连续量表达一个固定序,数值就成了必须小心维护却没人看得懂的魔法数——先后已经
 * 搬到注册号上(见 {@code ReflexOrderTest}),这里只剩触发。
 */
class SurvivalDecisionsTest {

    // ---- 进食 ----

    @Test
    void noEdibleNeverEats() {
        // 身上没吃的:再饿也没有可做的事,抢了身体只是白站着
        assertFalse(SurvivalDecisions.foodTriggered(0, 20.0f, false));
        assertFalse(SurvivalDecisions.foodTriggered(0, 1.0f, false));
    }

    @Test
    void fullAndHealthyDoesNotEat() {
        assertFalse(SurvivalDecisions.foodTriggered(20, 20.0f, true));
        assertFalse(SurvivalDecisions.foodTriggered(18, 20.0f, true));
    }

    @Test
    void hungryEats() {
        assertTrue(SurvivalDecisions.foodTriggered(SurvivalDecisions.HUNGRY_LEVEL, 20.0f, true));
        assertTrue(SurvivalDecisions.foodTriggered(0, 20.0f, true));
    }

    @Test
    void hurtEatsBackUpToTheRegenLine() {
        // 受伤但还没到"饿"的线:吃回自然回血线(食物 ≥18)能把血换回来
        assertTrue(SurvivalDecisions.foodTriggered(16, 6.0f, true));
        assertFalse(SurvivalDecisions.foodTriggered(16, 20.0f, true), "满血不必为回血而吃");
    }

    @Test
    void hurtButAlreadyAtTheRegenLineDoesNotEat() {
        assertFalse(SurvivalDecisions.foodTriggered(20, 4.0f, true));
    }

    // ---- 打还是跑 ----

    @Test
    void noThreatIsNone() {
        assertEquals(ThreatResponse.NONE, SurvivalDecisions.decideThreatResponse(false, 20.0f, true));
        assertFalse(SurvivalDecisions.mobDefenseTriggered(false));
    }

    @Test
    void healthyArmedFightsBack() {
        assertEquals(ThreatResponse.FIGHT, SurvivalDecisions.decideThreatResponse(true, 20.0f, true));
        assertTrue(SurvivalDecisions.mobDefenseTriggered(true));
    }

    @Test
    void lowHealthFleesEvenWhenArmed() {
        assertEquals(ThreatResponse.FLEE, SurvivalDecisions.decideThreatResponse(true, 4.0f, true));
    }

    @Test
    void unarmedFleesEvenWhenHealthy() {
        // 生存层从不主动去拿武器,所以空手就是跑
        assertEquals(ThreatResponse.FLEE, SurvivalDecisions.decideThreatResponse(true, 20.0f, false));
    }

    // ---- 摔落缓冲 ----

    @Test
    void onGroundNeverSaves() {
        assertFalse(SurvivalDecisions.mlgTriggered(true, 100.0, true));
    }

    @Test
    void shortFallNeverSaves() {
        assertFalse(SurvivalDecisions.mlgTriggered(false, 2.0, true));
    }

    @Test
    void lethalFallWithAMeansSaves() {
        assertTrue(SurvivalDecisions.mlgTriggered(false, 10.0, true));
        assertTrue(SurvivalDecisions.mlgTriggered(false, SurvivalDecisions.MLG_FALL_TRIGGER, true));
    }

    @Test
    void nothingToSaveWithMeansNoSave() {
        // 手上没水桶也没软方块:抢了身体也救不了自己,身体该留给别的反射
        assertFalse(SurvivalDecisions.mlgTriggered(false, 50.0, false));
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
