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

    /** 一只寻常近战怪的危险半径(含格量化补偿),见 {@code Menace.dangerRadius}。 */
    private static final double ORDINARY_DANGER = 2.73;

    /** 原版爆炸伤害的边界:威力 3 的爬行者,六格外一点也挨不着。 */
    private static final double BLAST_SPAN = 6.0;

    private static Foe mob(int id, double distance) {
        return new Foe(id, distance, false, false, distance < ORDINARY_DANGER,
                true, true, true);
    }

    /** 引信在走:危险半径是爆炸波及范围,六格。 */
    private static Foe creeper(int id, double distance) {
        return new Foe(id, distance, true, true, distance < BLAST_SPAN, true, true, true);
    }

    /** 引信还没点着的爬行者 —— 判据眼里就是一只普通怪,只是危险半径是点火线。 */
    private static Foe idleCreeper(int id, double distance) {
        return new Foe(id, distance, true, false, distance < 3.0, true, true, true);
    }

    private static Battlefield field(boolean melee, boolean ranged, Foe... foes) {
        return new Battlefield(HEALTHY, REACH, melee, ranged, false, List.of(foes));
    }

    // ==================== 够不够得着 ====================

    @Test
    void withinReachSheSwings() {
        Move m = AttackPlan.decide(field(true, true, mob(1, 3.0)), null);
        assertEquals(Action.SKIRMISH, m.action());
        assertEquals(1, m.foeId());
    }

    /** 有弓也照样走过去砍:走得到就不该花箭。 */
    @Test
    void reachableButFarSheWalksThereRatherThanSpendArrows() {
        assertEquals(Action.SKIRMISH,
                AttackPlan.decide(field(true, true, mob(1, 20.0)), null).action());
    }

    /** 走不到才动用远程——恶魂、悬崖对面、柱子上的东西都归这一支。 */
    @Test
    void unreachableIsWhatBowsAreFor() {
        Foe far = new Foe(1, 20.0, false, false, false, true, false, true);
        assertEquals(Action.RANGED, AttackPlan.decide(field(true, true, far), null).action());
    }

    @Test
    void unreachableWithNoBowIsGivenUp() {
        Foe far = new Foe(1, 20.0, false, false, false, true, false, true);
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
        Move m = AttackPlan.decide(b, new Move(Action.SKIRMISH, 1));
        assertEquals(Action.AVOID, m.action());
        assertEquals(AttackPlan.NO_FOE, m.foeId(), "退是对全场的,不指向某一只");
    }

    /** 它退到安全线外了,就该回去接着打原来那只。 */
    @Test
    void onceTheBlastIsClearSheGoesBackToFighting() {
        Battlefield b = field(true, true, mob(1, 3.0), creeper(2, 9.0));
        assertEquals(Action.SKIRMISH, AttackPlan.decide(b, new Move(Action.SKIRMISH, 1)).action());
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
     * 没弓,而它正追着她:<b>脱离</b>,不是"放弃"也不是"打完了"。
     *
     * <p>这一条曾经判成 DONE —— 苦力怕还跟着,候选空了就宣布胜利收工,回执还写着"没有东西再
     * 追你了"。反射链冷却完又开一场一模一样的,每轮放一次血。"没有能打的"与"没有危险了"在
     * 这种局面下正好相反。
     */
    @Test
    void anExplosiveChasingHerWithNoBowMeansBreakingOffNotFinishing() {
        Move m = AttackPlan.decide(field(true, false, creeper(1, 9.0)), null);
        assertEquals(Action.DISENGAGE, m.action());
        assertEquals(AttackPlan.NO_FOE, m.foeId(), "脱离是对全场的");
    }

    /**
     * 但它<b>没在追她</b>(模型点名让她打一个远处的苦力怕),那就只是这一只打不了:
     * 说清楚"打不了"而不是"打完了"——模型对前者能做点什么(去拿把弓),对后者无从下手。
     */
    @Test
    void anIdleExplosiveWithNoBowIsReportedAsUnfightable() {
        Foe idle = new Foe(1, 9.0, true, true, false, false, true, true);   // 九格外,挨不着
        Move m = AttackPlan.decide(field(true, false, idle), null);
        assertEquals(Action.ABANDON, m.action());
        assertEquals(1, m.foeId());
    }

    /** 没弓时它不该抢走本来能打的那只。 */
    @Test
    void anUnfightableExplosiveDoesNotStealTheTurnFromAFightableMob() {
        Battlefield b = field(true, false, creeper(1, 8.0), mob(2, 9.0));
        Move m = AttackPlan.decide(b, null);
        assertEquals(Action.SKIRMISH, m.action());
        assertEquals(2, m.foeId(), "该去打那只僵尸,不是围着苦力怕打转");
    }

    /**
     * <b>引信没点着的爬行者就是一只普通怪。</b>她够得着 4 格、它 3 格才点火,中间那条一格宽
     * 的带能打到它而不触发。曾经"会炸的一律不当目标",于是她只会绕着走、永远解决不掉。
     */
    @Test
    void aCreeperThatHasNotLitYetIsJustAMob() {
        Move m = AttackPlan.decide(field(true, false, idleCreeper(1, 3.5)), null);
        assertEquals(Action.SKIRMISH, m.action());
        assertEquals(1, m.foeId());
    }

    /** 远一点就走过去 —— 和别的怪没有区别。 */
    @Test
    void anUnlitCreeperIsWalkedUpToLikeAnythingElse() {
        assertEquals(Action.SKIRMISH,
                AttackPlan.decide(field(true, false, idleCreeper(1, 9.0)), null).action());
    }

    /** 引信一点着,同一只立刻改判 —— 它已经在倒计时了。 */
    @Test
    void theMomentItLightsSheStopsFighting() {
        Battlefield lit = field(true, false, creeper(1, 3.5));
        assertEquals(Action.AVOID, AttackPlan.decide(lit, new Move(Action.SKIRMISH, 1)).action());
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
        assertEquals(1, AttackPlan.decide(b, new Move(Action.SKIRMISH, 1)).foeId(),
                "上一刻在打 1,就算 2 更近也别转向");
    }

    /** 但它死了/不见了就该换人。 */
    @Test
    void whenTheChosenOneIsGoneShePicksAgain() {
        Battlefield b = field(true, true, mob(2, 4.5));
        assertEquals(2, AttackPlan.decide(b, new Move(Action.SKIRMISH, 1)).foeId());
    }

    // ==================== 近战迟滞 ====================

    /** 已经在挥击时,目标退开一点点不该让她立刻重新起步寻路。 */
    @Test
    void onceSwingingSheKeepsSwingingThroughSmallDrift() {
        Battlefield b = field(true, false, mob(1, 4.6));
        assertEquals(Action.SKIRMISH, AttackPlan.decide(b, new Move(Action.SKIRMISH, 1)).action());
    }

    /** 还没开打时不吃迟滞:同样 4.6 格,该走过去。 */
    @Test
    void beforeTheFirstSwingTheBandIsTheReachItself() {
        Battlefield b = field(true, false, mob(1, 4.6));
        assertEquals(Action.SKIRMISH, AttackPlan.decide(b, null).action());
    }

    /** 退得够远还是要追——迟滞是一条带,不是无限期豁免。 */
    @Test
    void driftingWellOutOfBandStillMeansWalking() {
        Battlefield b = field(true, false, mob(1, 7.0));
        assertEquals(Action.SKIRMISH, AttackPlan.decide(b, new Move(Action.SKIRMISH, 1)).action());
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
        Foe chicken = new Foe(1, 3.0, false, false, false, false, true, true);
        assertEquals(Action.SKIRMISH,
                AttackPlan.decide(field(false, false, chicken), null).action());
    }

    // ==================== 扛不扛得住 ====================

    /** 扛不住就脱离,哪怕近在眼前、哪怕手里有武器。 */
    @Test
    void tooHurtMeansBreakOffEvenWithTheTargetInReach() {
        Battlefield b = new Battlefield(4.0, REACH, true, true, false, List.of(mob(1, 3.0)));
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
        Battlefield b = new Battlefield(4.0, REACH, true, true, false, List.of(creeper(1, 3.0)));
        assertEquals(Action.DISENGAGE, AttackPlan.decide(b, null).action());
    }

    /**
     * <b>退不掉就打。</b>站着挨打是确定的死,背水一战至少有机会。这一维与 {@code reachable}
     * 对称:一个说"走得到吗"(该不该走过去打),一个说"退得掉吗"(该不该退)。
     *
     * <p>它替掉的是链子那个 5 秒冷却 —— 那个冷却只是把死循环放慢,期间新来的危险她一动不动,
     * 实测四次重伤都发生在那个窗口里。
     */
    @Test
    void corneredMeansFightingInsteadOfStandingStill() {
        Battlefield trapped = new Battlefield(4.0, REACH, true, true, true, List.of(mob(1, 3.0)));
        assertEquals(Action.SKIRMISH, AttackPlan.decide(trapped, null).action());
    }

    /** 退得掉就还是退 —— cornered 只在真被围住时才改判。 */
    @Test
    void withAWayOutSheStillBreaksOff() {
        Battlefield b = new Battlefield(4.0, REACH, true, true, false, List.of(mob(1, 3.0)));
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
        Foe stranger = new Foe(1, 3.0, false, false, false, false, true, false);
        assertEquals(Action.DONE, AttackPlan.decide(field(true, true, stranger), null).action());
    }

    // ==================== 有人贴到危险半径里了 ====================

    /**
     * 有人进了它的危险半径就该挪窝,<b>不管她在打谁</b>。
     *
     * <p>判据曾经只看血量和爆炸:寻路已经在按危险半径挑落脚点了,判据照样说"过去打",
     * 吸引项一路把她往人堆里拉。而寻路的目标是<b>开路那一刻的快照</b>,别的怪走近了它不会
     * 自己失效——每刻用实时距离重问一遍的,只有这里。
     */
    @Test
    void someoneInsideHisRadiusMeansMovingFirst() {
        Battlefield b = field(true, true, mob(1, 5.0), mob(2, 2.0));
        assertEquals(Action.AVOID, AttackPlan.decide(b, null).action());
    }

    /**
     * 出了半径就接着打,<b>不需要迟滞</b>——她的够到距离比对方的危险半径大出半格到一格
     * (僵尸 3.30 对 2.73),退到边缘就够。那条缝是原版碰撞箱给的,不是调出来的。
     */
    @Test
    void justOutsideTheRadiusSheFightsAgain() {
        Battlefield b = field(true, true, mob(1, 3.0));
        assertEquals(Action.SKIRMISH, AttackPlan.decide(b, null).action());
        assertEquals(Action.SKIRMISH,
                AttackPlan.decide(b, new Move(Action.AVOID, AttackPlan.NO_FOE)).action());
    }

    /** 退无可退时不许再退 —— 站着挨打是确定的死,打至少有机会。 */
    @Test
    void corneredSheFightsAnyway() {
        var boxedIn = new Battlefield(HEALTHY, REACH, true, true, true,
                List.of(mob(1, 2.0), mob(2, 2.0)));
        assertEquals(Action.SKIRMISH, AttackPlan.decide(boxedIn, null).action());
    }

    /** 远处那些不该让她掉头就走 —— 只有进了半径的才算。 */
    @Test
    void distantMobsDoNotScareHer() {
        Battlefield b = field(true, true, mob(1, 3.0), mob(2, 8.0), mob(3, 11.0));
        assertEquals(Action.SKIRMISH, AttackPlan.decide(b, null).action());
    }

    // ==================== 空手 ====================

    /**
     * 空手时进了危险半径要判 <b>DISENGAGE 而不是 AVOID</b>。
     *
     * <p>AVOID 的意思是"还想打,只是不能在这儿打" —— 她根本打不了,这句是假的。两条判据
     * 先后触发的结果是:怪进半径出 AVOID、退半步出 DISENGAGE,在那条线上来回换,而两个
     * 动作在执行层走不同分支、各自重建导航。实测空手时 35 次对 30 次,几乎 1:1。
     */
    @Test
    void barehandedInsideTheRadiusIsAnEscapeNotAReposition() {
        assertEquals(Action.DISENGAGE,
                AttackPlan.decide(field(false, false, mob(1, 2.0)), null).action());
    }

    /** 出了半径也一样 —— 空手就该一路走脱离这一条支,不该跟着半径线换动作。 */
    @Test
    void barehandedOutsideTheRadiusIsStillAnEscape() {
        assertEquals(Action.DISENGAGE,
                AttackPlan.decide(field(false, false, mob(1, 4.0)), null).action());
    }

    /** 有武器才谈得上"换个地方打"。 */
    @Test
    void armedInsideTheRadiusIsAReposition() {
        assertEquals(Action.AVOID,
                AttackPlan.decide(field(true, false, mob(1, 2.0)), null).action());
    }
}
