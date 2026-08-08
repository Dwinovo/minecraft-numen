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
        /**
         * 站定拉弓。<b>战斗状态里唯一不走位的一支</b> —— 原版拉满弓要十五刻不能动,
         * 那和"一直走位"是冲突的。不是规矩,是拉弓机制逼出来的。
         */
        RANGED,
        /** 脱离接触:打不过,跑。 */
        DISENGAGE,
        /** 没什么可打的了。 */
        DONE
    }

    /**
     * 决定。
     *
     * @param action 做什么
     * @param foeId  对谁做;{@link Action#DISENGAGE}、{@link Action#DONE}
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
        // 「会炸的贴太近了」也不在这儿判。点着的爬行者<b>危险半径就是它的爆炸波及范围</b>
        // (6.71 格),走位环的内沿自然把她顶到那之外 —— 曾经它是一个独立动作(AVOID),
        // 于是"躲爆炸"和"走位"成了互斥的两个状态,躲的那一支还不还手。
        //
        // ③ 手上没有能打的东西:赤手对上会还手的东西不是一条出路,退开。
        //
        //    空手就该一路走脱离这一支,不该跟着距离线在两个动作之间换。
        if (!b.hasMelee() && !b.hasRanged() && anyEngaging(b) && !b.cornered()) {
            return new Move(Action.DISENGAGE, NO_FOE);
        }
        // 「太近了」不在这儿判。它是<b>寻路的事</b>:战斗的走位目标是一个环 —— 内沿是目标
        // 够不着她,外沿是别跟丢,太近自然往外走、太远自然往回走。曾经它是一个独立动作
        // (AVOID),于是"拉开"和"走位"成了互斥的两个状态,而拉开那一支还不挥刀。
        //
        // 判据只回答两件事:<b>还打不打得过</b>(打不过就跑),以及<b>用弓还是走位</b>。
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
        return new Move(Action.DONE, NO_FOE);
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
            // 手上只有弓:太近就走位(环会把她带出去),别拿拳头凑合;够远了才站定拉弓。
            return foe.distance() < RANGED_MIN_DISTANCE ? Action.SKIRMISH : Action.RANGED;
        }
        // 走得到就走位 —— 太远往前、太近往后,都是同一个动作。够不够得着挥刀是攻击系统的事。
        //
        // 走不到(恶魂、悬崖对面、柱子上)才动用弓;<b>没弓也不放弃</b>,接着走位试 ——
        // "够不着"曾经是永久判定,寻路抖三次就把两格外的普通僵尸标成够不着,于是拿着
        // 下界合金剑的人一边跑一边说"我没有能打的东西"。
        return !foe.reachable() && b.hasRanged() ? Action.RANGED : Action.SKIRMISH;
    }

    private static boolean anyEngaging(Battlefield b) {
        return b.foes().stream().anyMatch(Foe::engaging);
    }
}
