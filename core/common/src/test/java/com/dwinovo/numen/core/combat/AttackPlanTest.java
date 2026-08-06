package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.combat.AttackPlan.Stance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这一刻该怎么打。
 *
 * <p>钉的是两条:<b>够得着就用近战</b>(那同时也是省箭的那一支),以及<b>会炸的东西一律不贴身</b>
 * ——后者是今天真会把她炸死的那条路。
 */
class AttackPlanTest {

    /** 血量充裕 —— 这些用例检的是别的维度。 */
    private static final double HEALTHY = 40.0;

    private static AttackPlan.Situation at(double distance, boolean reachable, boolean hasRanged) {
        return new AttackPlan.Situation(distance, 4.0, reachable, hasRanged, false, 0.0, HEALTHY, false);
    }

    private static AttackPlan.Situation explosiveAt(double distance, boolean hasRanged) {
        return new AttackPlan.Situation(distance, 4.0, true, hasRanged, true, 7.5, HEALTHY, false);
    }

    private static AttackPlan.Situation hurtTo(double effectiveHealth) {
        return new AttackPlan.Situation(3.0, 4.0, true, true, false, 0.0, effectiveHealth, false);
    }

    // ==================== 够不够得着 ====================

    @Test
    void withinReachSheSwings() {
        assertEquals(Stance.MELEE, AttackPlan.decide(at(3.0, true, true)));
    }

    /** 有弓也照样走过去砍:走得到就不该花箭。 */
    @Test
    void reachableButFarSheWalksThereRatherThanSpendArrows() {
        assertEquals(Stance.CLOSE_IN, AttackPlan.decide(at(20.0, true, true)));
    }

    /** 走不到才动用远程——恶魂、悬崖对面、柱子上的东西都归这一支。 */
    @Test
    void unreachableIsWhatBowsAreFor() {
        assertEquals(Stance.RANGED, AttackPlan.decide(at(20.0, false, true)));
    }

    /** 走不到又没有远程手段:说打不了,而不是原地耗着。 */
    @Test
    void unreachableWithNoBowIsGivenUpRatherThanChasedForever() {
        assertEquals(Stance.ABANDON, AttackPlan.decide(at(20.0, false, false)));
    }

    // ==================== 该不该靠近 ====================

    /**
     * 会炸的东西<b>近在眼前也不打</b>。这一条与"够不够得着"无关——爬行者恰恰是完全够得着的,
     * 只按距离判就会直接判近战,那正是会被炸的那条路。
     */
    @Test
    void anExplosiveTargetIsNeverMeleedEvenPointBlank() {
        assertEquals(Stance.AVOID, AttackPlan.decide(explosiveAt(1.0, true)));
        assertEquals(Stance.AVOID, AttackPlan.decide(explosiveAt(1.0, false)));
    }

    /** 退到安全线以外,才轮到出手。 */
    @Test
    void pastTheSafeLineSheShoots() {
        assertEquals(Stance.RANGED, AttackPlan.decide(explosiveAt(8.0, true)));
    }

    /** 没有弓就是打不了它——不是"那就上去砍吧"。 */
    @Test
    void anExplosiveTargetWithoutABowIsAbandonedNotCharged() {
        assertEquals(Stance.ABANDON, AttackPlan.decide(explosiveAt(8.0, false)));
    }

    /** 安全线本身算安全:边界上不该再退一步,否则她会在这一格上来回改主意。 */
    @Test
    void theSafeLineItselfCounts() {
        assertEquals(Stance.RANGED, AttackPlan.decide(explosiveAt(7.5, true)));
    }

    // ==================== 近战迟滞 ====================

    /**
     * 已经在挥击时,目标退开一点点不该让她立刻重新起步寻路。没有这道迟滞,目标在够到线上
     * 微动就是"打一下 → 拆掉路径重新规划 → 打一下",实测刷了七十多次"新路径立刻到达"。
     */
    @Test
    void onceSwingingSheKeepsSwingingThroughSmallDrift() {
        AttackPlan.Situation drifted =
                new AttackPlan.Situation(4.6, 4.0, true, false, false, 0.0, HEALTHY, true);
        assertEquals(Stance.MELEE, AttackPlan.decide(drifted));
    }

    /** 但退得够远还是要追 —— 迟滞是一条带,不是无限期豁免。 */
    @Test
    void driftingWellOutOfBandStillMeansWalking() {
        AttackPlan.Situation gone =
                new AttackPlan.Situation(6.0, 4.0, true, false, false, 0.0, HEALTHY, true);
        assertEquals(Stance.CLOSE_IN, AttackPlan.decide(gone));
    }

    /** 还没开打时不吃迟滞:同样 4.6 格,该走过去。 */
    @Test
    void beforeTheFirstSwingTheBandIsTheReachItself() {
        AttackPlan.Situation approaching =
                new AttackPlan.Situation(4.6, 4.0, true, false, false, 0.0, HEALTHY, false);
        assertEquals(Stance.CLOSE_IN, AttackPlan.decide(approaching));
    }

    // ==================== 扛不扛得住 ====================

    /**
     * 扛不住就脱离,<b>哪怕近在眼前、哪怕手里有武器</b>。她一直打到死那次,病根就是"打不过"
     * 根本不在战斗判据里 —— 任务只管挥剑,而看得见血量的那条链没被叫醒。
     */
    @Test
    void tooHurtMeansBreakOffEvenWithTheTargetInReach() {
        assertEquals(Stance.DISENGAGE, AttackPlan.decide(hurtTo(4.0)));
    }

    /** 血够就照常打 —— 这一维不该把正常交战也拦下来。 */
    @Test
    void enoughLeftInTheTankMeansFightOn() {
        assertEquals(Stance.MELEE, AttackPlan.decide(hurtTo(40.0)));
    }

    /**
     * 比的是<b>按护甲折算后</b>的血。同样 8 点血,裸奔该退、下界合金该打 ——
     * 这里只钉判据吃的是折算值,折算本身由 {@code Menace.effectiveHealth} 用原版公式做。
     */
    @Test
    void theThresholdIsAboutEffectiveHealthNotRawHealth() {
        assertTrue(AttackPlan.outmatched(8.0), "裸血 8 点:退");
        assertFalse(AttackPlan.outmatched(8.0 * 5), "同样 8 点血,重甲折算后能扛五倍:打");
    }

    /** 扛不住排在"会不会炸"前面:两者都真时,脱离退得比保持输出距离更彻底。 */
    @Test
    void breakingOffOutranksKeepingShootingDistance() {
        assertEquals(Stance.DISENGAGE, AttackPlan.decide(
                new AttackPlan.Situation(3.0, 4.0, true, true, true, 7.5, 4.0, false)));
    }

    // ==================== 边界 ====================

    /** 正好在够到距离上就该挥,不该再走一步——差一点点就会变成贴着目标反复起步。 */
    @Test
    void exactlyAtReachIsCloseEnough() {
        assertEquals(Stance.MELEE, AttackPlan.decide(at(4.0, true, false)));
    }
}
