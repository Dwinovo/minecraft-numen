package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.combat.Battlefield.Foe;

/**
 * 这一刻该做什么、对谁做。<b>本能派的仗与模型派的 {@code attack} 问的是同一个函数</b>
 * ——爬行者该退多远,不该在反射里写一遍、在工具里再写一遍。
 *
 * <h2>输入是整个局面,不是一个目标</h2>
 * 见 {@link Battlefield}。"该不该躲"是全场的事,"打谁"也要看全场(挑最近的、跳过打不了的、
 * 记得上一刻打的那只)。把这些挂在单目标的描述上,每加一个考量就多一个字段,而且顺序一乱
 * 就出现"她在打史莱姆,苦力怕在旁边点火"这种局面。
 *
 * <h2>它有记忆</h2>
 * {@link #decide} 收上一刻的决定。两处需要:<b>迟滞</b>(已经在挥击时,目标退开一点点不该
 * 让她立刻重新起步寻路)与<b>承诺</b>(选中一只就打完再换,否则一群会分裂的怪里"最近那只"
 * 每刻都在变,她永远在转向)。没有记忆的判据只能靠调用方在外面打补丁。
 */
public final class AttackPlan {

    /** 这一刻做什么。 */
    public enum Action {
        /**
         * 走位。<b>这是一个移动动作,不是"打不打"</b> —— 打由攻击系统每刻独立判(冷却好了
         * 且够得着就打),与她这一刻在靠近还是在拉开无关。
         *
         * <p>曾经这里分 MELEE 与 CLOSE_IN 两个动作,于是"靠近"那一支不挥刀、"拉开"那一支
         * 也不挥刀 —— 她躲的时候不还手,骷髅一边后退一边射她也追不上。
         */
        SKIRMISH,
        /** 拉开弓弩射它。 */
        RANGED,
        /** 主动拉开距离——会炸的东西已经贴到爆炸范围里了。仍然想打,只是不能贴身。 */
        AVOID,
        /** 脱离接触——扛不住了,或这一架根本打不了。 */
        DISENGAGE,
        /** 这一只打不了(走不到又没远程),换下一只。 */
        ABANDON,
        /** 没什么可打的了。 */
        DONE
    }

    /**
     * 决定。
     *
     * @param action 做什么
     * @param foeId  对谁做;{@link Action#AVOID}、{@link Action#DISENGAGE}、{@link Action#DONE}
     *               是对全场的,此时为 {@link #NO_FOE}
     */
    public record Move(Action action, int foeId) {}

    public static final int NO_FOE = Integer.MIN_VALUE;

    /**
     * 低于这个有效血量就别打了。
     *
     * <p>比的是<b>折算后</b>的血:满血裸奔与满血下界合金曾经判得一模一样,而后者能多扛
     * 四五倍。数值沿用一直以来的那条裸血线(八点,四颗心),只是喂进来的输入变实在了。
     */
    private static final double MIN_EFFECTIVE_HEALTH = 8.0;

    /**
     * 只有远程手段时,离目标近于这个距离就先拉开。
     *
     * <p>太近弹道压得平、还白白挨打;而手上没有近战武器时"贴上去用拳头"从来不是答案。
     */
    private static final double RANGED_MIN_DISTANCE = 5.0;

    private AttackPlan() {}

    /** 这点有效血量还够不够站着打。阈值只有这一处。 */
    public static boolean outmatched(double effectiveHealth) {
        return effectiveHealth <= MIN_EFFECTIVE_HEALTH;
    }

    /**
     * @param last 上一刻的决定;第一次传 {@code null}
     */
    public static Move decide(Battlefield b, Move last) {
        // ① 扛不住 —— 一切"怎么打"的讨论都以她还站得住为前提。
        if (outmatched(b.effectiveHealth()) && !b.cornered()) {
            return new Move(Action.DISENGAGE, NO_FOE);
        }
        // ② 会炸的已经贴到爆炸范围里 —— <b>全场的事</b>,与她正在打谁无关。
        //    单目标的判据看不见这一条,于是她一边打史莱姆一边被炸。
        if (b.underBlastThreat()) {
            return new Move(Action.AVOID, NO_FOE);
        }
        // ③ 两条路都没有:赤手对上会还手的东西不是一条出路,退开。
        //
        //    <b>必须排在 ④ 前面。</b>空手时"进了危险半径"该判 DISENGAGE 而不是 AVOID ——
        //    AVOID 的意思是"还想打,只是不能在这儿打",她根本打不了,这句是假的。两条判据
        //    先后触发的结果是:怪进半径出 AVOID、退半步出 DISENGAGE,在那条线上来回换,
        //    而两个动作在执行层走不同分支、各自重建导航。实测空手时 35 次对 30 次,几乎 1:1。
        if (!b.hasMelee() && !b.hasRanged() && anyEngaging(b) && !b.cornered()) {
            return new Move(Action.DISENGAGE, NO_FOE);
        }
        // ④ 有人已经进了它的危险半径 —— <b>全场的事</b>,与她正在打谁无关。
        //    寻路的目标是开路那一刻的快照,别的怪走近了它不会自己失效;这一条用的是<b>实时
        //    距离</b>,每刻重问一遍,防偷袭真正靠得住的是它。
        //
        //    不需要迟滞:她的够到距离比对方的危险半径大出半格到一格(僵尸 3.30 对 2.73),
        //    退到边缘就能打。这条缝是原版碰撞箱给的,不是调出来的。
        if (b.anyTooClose() && !b.cornered()) {
            return new Move(Action.AVOID, NO_FOE);
        }

        Foe foe = pick(b, last);
        if (foe != null) {
            return new Move(actionAgainst(b, foe), foe.id());
        }
        // 打不了了。<b>还有东西在追她,那就不叫打完了。</b>
        //
        // 这一条曾经判成 DONE:苦力怕还跟着,她没弓打不了,于是候选空 → 宣布胜利收工,
        // 回执还写着"没有东西再追你了"。反射链冷却完又开一场一模一样的,每轮放一次血。
        // "没有能打的"与"没有危险了"在这种局面下正好相反。
        if (anyEngaging(b)) {
            return new Move(Action.DISENGAGE, NO_FOE);
        }
        // 它不追她,只是这一只碰不得(模型点名让她打一个够不着或会炸的东西)。说清楚是
        // "打不了"而不是"打完了"——模型对前者能做点什么(去拿把弓),对后者无从下手。
        Foe blocked = nearestAuthorized(b);
        return blocked != null
                ? new Move(Action.ABANDON, blocked.id())
                : new Move(Action.DONE, NO_FOE);
    }

    /**
     * 打谁。<b>先打近的,但选定之后打完再换</b>——每刻按距离重选的话,一群会分裂的史莱姆里
     * "最近那只"每刻都在变,她永远在转向,而每次转向都会拆掉刚算好的路径。
     */
    private static Foe pick(Battlefield b, Move last) {
        Foe kept = last == null ? null : b.byId(last.foeId());
        if (kept != null && fightable(b, kept)) {
            return kept;
        }
        Foe best = null;
        for (Foe f : b.foes()) {
            if (!fightable(b, f)) {
                continue;
            }
            if (best == null || f.distance() < best.distance()
                    || (f.distance() == best.distance() && f.id() < best.id())) {
                best = f;
            }
        }
        return best;
    }

    /**
     * 这一只值不值得当目标。
     *
     * <p><b>会炸的东西,没有远程手段就根本不该当目标</b>:她要的是躲开它,不是打它。让它进
     * 候选的话,判据会在安全线上一格 AVOID、一格 ABANDON 地来回跳——放弃后下一刻又被选回来,
     * 实测每秒三轮。躲它归 ② 那一档,不归"打谁"。
     */
    private static boolean fightable(Battlefield b, Foe f) {
        if (!f.authorized()) {
            return false;
        }
        // 引信没点着的爬行者就是一只普通怪:她够得着 4 格、它 3 格才点火,中间那条一格宽的
        // 带能打到它而不触发。曾经"会炸的一律不当目标",于是她只会绕着走、永远解决不掉,
        // 还在安全线上一格 AVOID 一格 ABANDON 地来回跳。
        return !f.armed() || b.hasRanged();
    }

    /** 对选定的这一只<b>怎么移动</b>。打不打不在这儿决定。 */
    private static Action actionAgainst(Battlefield b, Foe foe) {
        if (foe.armed()) {
            // 已经在倒计时的只能远远射(能选中它就说明有弓弩);"已经贴太近"那一档在 ② 拦掉了。
            return Action.RANGED;
        }
        // 近战可用 = 有近战武器,或者根本没有远程手段(那拳头也得上)。
        boolean meleeAvailable = b.hasMelee() || !b.hasRanged();
        if (!meleeAvailable) {
            // 手上只有弓:太近先拉开,别拿拳头凑合。
            return foe.distance() < RANGED_MIN_DISTANCE ? Action.AVOID : Action.RANGED;
        }
        // 走得到就走位 —— 太远往前、太近往后,都是同一个动作。够不够得着挥刀是攻击系统的事。
        if (foe.reachable()) {
            return Action.SKIRMISH;
        }
        return b.hasRanged() ? Action.RANGED : Action.ABANDON;
    }

    /** 被授权、但这一刻打不了的里面最近的那只。 */
    private static Foe nearestAuthorized(Battlefield b) {
        Foe best = null;
        for (Foe f : b.foes()) {
            if (f.authorized() && (best == null || f.distance() < best.distance())) {
                best = f;
            }
        }
        return best;
    }

    private static boolean anyEngaging(Battlefield b) {
        return b.foes().stream().anyMatch(Foe::engaging);
    }
}
