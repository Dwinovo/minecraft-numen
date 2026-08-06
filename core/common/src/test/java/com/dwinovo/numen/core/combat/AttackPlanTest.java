package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.combat.Battlefield.Foe;

import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dwinovo.numen.core.combat.AttackPlan.Action;
import static com.dwinovo.numen.core.combat.AttackPlan.Move;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 这一刻该做什么、对谁做。
 *
 * <p>判据吃的是<b>整个局面</b>而不是一个目标——那正是"她一边打史莱姆一边被苦力怕炸死"的
 * 病根:全局的危险在单目标的描述里无处安放。
 */
class AttackPlanTest {

    private static final double HEALTHY = 40.0;
    private static final double REACH = 4.0;

    private static Foe mob(int id, double distance) {
        return new Foe(id, distance, false, 0.0, true, true, true);
    }

    private static Foe creeper(int id, double distance) {
        return new Foe(id, distance, true, 7.5, true, true, true);
    }

    private static Battlefield field(boolean melee, boolean ranged, Foe... foes) {
        return new Battlefield(HEALTHY, REACH, melee, ranged, List.of(foes));
    }

    // ==================== 够不够得着 ====================

    @Test
    void withinReachSheSwings() {
        Move m = AttackPlan.decide(field(true, true, mob(1, 3.0)), null);
        assertEquals(Action.MELEE, m.action());
        assertEquals(1, m.foeId());
    }

    /** 有弓也照样走过去砍:走得到就不该花箭。 */
    @Test
    void reachableButFarSheWalksThereRatherThanSpendArrows() {
        assertEquals(Action.CLOSE_IN,
                AttackPlan.decide(field(true, true, mob(1, 20.0)), null).action());
    }

    /** 走不到才动用远程——恶魂、悬崖对面、柱子上的东西都归这一支。 */
    @Test
    void unreachableIsWhatBowsAreFor() {
        Foe far = new Foe(1, 20.0, false, 0.0, true, false, true);
        assertEquals(Action.RANGED, AttackPlan.decide(field(true, true, far), null).action());
    }

    @Test
    void unreachableWithNoBowIsGivenUp() {
        Foe far = new Foe(1, 20.0, false, 0.0, true, false, true);
        assertEquals(Action.ABANDON, AttackPlan.decide(field(true, false, far), null).action());
    }

    // ==================== 全局的危险 ====================

    /**
     * <b>这条是这次重构的正主。</b>她在打三格外的史莱姆,苦力怕就在三点三格外——单目标的
     * 判据一无所知,照旧判 MELEE。局面判据看得见,先退。
     */
    @Test
    void aCreeperInBlastRangeOutranksWhateverSheIsFighting() {
        Battlefield b = field(true, true, mob(1, 3.0), creeper(2, 3.3));
        Move m = AttackPlan.decide(b, new Move(Action.MELEE, 1));
        assertEquals(Action.AVOID, m.action());
        assertEquals(AttackPlan.NO_FOE, m.foeId(), "退是对全场的,不指向某一只");
    }

    /** 它退到安全线外了,就该回去接着打原来那只。 */
    @Test
    void onceTheBlastIsClearSheGoesBackToFighting() {
        Battlefield b = field(true, true, mob(1, 3.0), creeper(2, 9.0));
        assertEquals(Action.MELEE, AttackPlan.decide(b, new Move(Action.MELEE, 1)).action());
    }

    // ==================== 会炸的东西 ====================

    /** 有弓:退到安全线外射它。 */
    @Test
    void withABowAnExplosiveTargetIsShotFromOutside() {
        Move m = AttackPlan.decide(field(true, true, creeper(1, 9.0)), null);
        assertEquals(Action.RANGED, m.action());
        assertEquals(1, m.foeId());
    }

    /**
     * 没弓:它<b>根本不该当目标</b>。让它进候选的话,判据会在安全线上一格 AVOID、一格
     * ABANDON 地来回跳——放弃后下一刻又被选回来,实测每秒三轮。
     */
    @Test
    void withoutABowAnExplosiveIsNeverPickedAsATarget() {
        Move m = AttackPlan.decide(field(true, false, creeper(1, 9.0)), null);
        assertEquals(Action.ABANDON, m.action(), "说清楚是打不了,不是打完了");
        assertEquals(1, m.foeId());
    }

    /** 没弓时它不该抢走本来能打的那只。 */
    @Test
    void anUnfightableExplosiveDoesNotStealTheTurnFromAFightableMob() {
        Battlefield b = field(true, false, creeper(1, 8.0), mob(2, 9.0));
        Move m = AttackPlan.decide(b, null);
        assertEquals(Action.CLOSE_IN, m.action());
        assertEquals(2, m.foeId(), "该去打那只僵尸,不是围着苦力怕打转");
    }

    // ==================== 打谁:先近的,但要有承诺 ====================

    @Test
    void theNearestOneGoesFirst() {
        Battlefield b = field(true, true, mob(1, 9.0), mob(2, 4.5));
        assertEquals(2, AttackPlan.decide(b, null).foeId());
    }

    /**
     * 选定之后打完再换。一群会分裂的史莱姆里"最近那只"每刻都在变,每次转向都会拆掉刚算好的
     * 路径——实测目标 id 在九只之间来回跳,同时刷了七十多次"新路径立刻判定到达"。
     */
    @Test
    void sheStaysOnTheOneSheChose() {
        Battlefield b = field(true, true, mob(1, 9.0), mob(2, 4.5));
        assertEquals(1, AttackPlan.decide(b, new Move(Action.CLOSE_IN, 1)).foeId(),
                "上一刻在打 1,就算 2 更近也别转向");
    }

    /** 但它死了/不见了就该换人。 */
    @Test
    void whenTheChosenOneIsGoneShePicksAgain() {
        Battlefield b = field(true, true, mob(2, 4.5));
        assertEquals(2, AttackPlan.decide(b, new Move(Action.MELEE, 1)).foeId());
    }

    // ==================== 近战迟滞 ====================

    /** 已经在挥击时,目标退开一点点不该让她立刻重新起步寻路。 */
    @Test
    void onceSwingingSheKeepsSwingingThroughSmallDrift() {
        Battlefield b = field(true, false, mob(1, 4.6));
        assertEquals(Action.MELEE, AttackPlan.decide(b, new Move(Action.MELEE, 1)).action());
    }

    /** 还没开打时不吃迟滞:同样 4.6 格,该走过去。 */
    @Test
    void beforeTheFirstSwingTheBandIsTheReachItself() {
        Battlefield b = field(true, false, mob(1, 4.6));
        assertEquals(Action.CLOSE_IN, AttackPlan.decide(b, null).action());
    }

    /** 退得够远还是要追——迟滞是一条带,不是无限期豁免。 */
    @Test
    void driftingWellOutOfBandStillMeansWalking() {
        Battlefield b = field(true, false, mob(1, 6.0));
        assertEquals(Action.CLOSE_IN, AttackPlan.decide(b, new Move(Action.MELEE, 1)).action());
    }

    // ==================== 手上有什么 ====================

    /**
     * 只有弓、没有近战武器时,目标贴到脸上<b>不该用拳头凑合</b>——该拉开到能射的距离。
     */
    @Test
    void withOnlyABowSheBacksOffInsteadOfPunching() {
        assertEquals(Action.AVOID,
                AttackPlan.decide(field(false, true, mob(1, 3.0)), null).action());
    }

    @Test
    void withOnlyABowAtRangeSheShoots() {
        assertEquals(Action.RANGED,
                AttackPlan.decide(field(false, true, mob(1, 12.0)), null).action());
    }

    /** 两条路都没有,而对方会还手:退开。赤手对上僵尸不是一条出路。 */
    @Test
    void barehandedAgainstSomethingThatFightsBackMeansBreakingOff() {
        assertEquals(Action.DISENGAGE,
                AttackPlan.decide(field(false, false, mob(1, 3.0)), null).action());
    }

    /** 但赤手打一只不还手的东西是正当的(模型点名让她去打一只鸡)。 */
    @Test
    void barehandedAgainstSomethingHarmlessIsFine() {
        Foe chicken = new Foe(1, 3.0, false, 0.0, false, true, true);
        assertEquals(Action.MELEE,
                AttackPlan.decide(field(false, false, chicken), null).action());
    }

    // ==================== 扛不扛得住 ====================

    /** 扛不住就脱离,哪怕近在眼前、哪怕手里有武器。 */
    @Test
    void tooHurtMeansBreakOffEvenWithTheTargetInReach() {
        Battlefield b = new Battlefield(4.0, REACH, true, true, List.of(mob(1, 3.0)));
        assertEquals(Action.DISENGAGE, AttackPlan.decide(b, null).action());
    }

    /**
     * 比的是<b>按护甲折算后</b>的血。同样 8 点血,裸奔该退、下界合金该打——
     * 折算本身由 {@code Menace.effectiveHealth} 用原版公式做,这里只钉判据吃的是折算值。
     */
    @Test
    void theThresholdIsAboutEffectiveHealthNotRawHealth() {
        assertTrue(AttackPlan.outmatched(8.0), "裸血 8 点:退");
        assertFalse(AttackPlan.outmatched(8.0 * 5), "同样 8 点血,重甲折算后能扛五倍:打");
    }

    /** 扛不住排在一切前面,包括躲爆炸——两者都是退,但脱离退得更彻底。 */
    @Test
    void breakingOffOutranksEverything() {
        Battlefield b = new Battlefield(4.0, REACH, true, true, List.of(creeper(1, 3.0)));
        assertEquals(Action.DISENGAGE, AttackPlan.decide(b, null).action());
    }

    // ==================== 收场 ====================

    @Test
    void anEmptyFieldIsDone() {
        assertEquals(Action.DONE, AttackPlan.decide(field(true, true), null).action());
    }

    /** 没被授权的不算数——场上还有怪,但都不归这一场仗管。 */
    @Test
    void unauthorizedFoesDoNotKeepTheFightAlive() {
        Foe stranger = new Foe(1, 3.0, false, 0.0, false, true, false);
        assertEquals(Action.DONE, AttackPlan.decide(field(true, true, stranger), null).action());
    }
}
