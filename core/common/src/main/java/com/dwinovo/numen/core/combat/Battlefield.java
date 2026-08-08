package com.dwinovo.numen.core.combat;

import java.util.List;

/**
 * 这一刻的<b>整个局面</b>:她自己,加上场上每一个敌对生物。
 *
 * <h2>为什么不是"她和当前目标"</h2>
 * 判据曾经只描述一个目标,于是"该不该躲"这种全局的事没地方放——实测她在打三格外的史莱姆,
 * 苦力怕就在三点三格外,判据一无所知。往单目标的描述上挂全局字段是兜底,不是修:每多一个
 * 全局考量就多一个字段,而"打谁"本身也需要看全场(挑最近的、跳过打不了的、上一刻打的那只)。
 *
 * <p>所以输入就是局面本身,{@link AttackPlan} 从局面里同时得出<b>做什么</b>和<b>对谁做</b>。
 *
 * @param effectiveHealth 按护甲折算后她还扛得住多少,见 {@link Menace#effectiveHealth}
 * @param meleeReach      她这一刻够得着多远
 * @param hasMelee        背包里有近战武器(拳头不算——赤手对上会还手的东西不是一条出路)
 * @param hasRanged       有能立刻用的弓弩(带箭,或已上弦的弩)
 * @param cornered        <b>退不掉</b>:退避的寻路连续失败。与 {@code Foe.reachable} 对称
 *                        ——一个说"走得到吗"(决定该不该走过去打),一个说"退得掉吗"
 *                        (决定该不该退)。退不掉时站着挨打是确定的死,背水一战至少有机会。
 * @param foes            场上的敌对生物,顺序不重要
 */
public record Battlefield(double effectiveHealth,
                          double meleeReach,
                          boolean hasMelee,
                          boolean hasRanged,
                          boolean cornered,
                          List<Foe> foes) {

    /**
     * 场上的一个。
     *
     * @param id         运行时实体 id
     * @param distance   离她多远
     * @param explosive  它会炸,不管这一刻炸没炸——走近它要留余量,别踩进点火线
     * @param armed      它<b>现在就要炸了</b>:引信在走,或者是一打就炸的末影水晶
     * @param tooClose   她已经进了它的<b>危险半径</b>:再待着就要挨打。
     *                   见 {@code Menace.dangerRadius} —— 判据与寻路问的是同一个函数
     * @param engaging   正在针对她(锁定了她,或刚打了她)
     * @param reachable  寻路还没判定"到不了"
     * @param authorized 允许打它。点名模式下是模型给的那份清单,无差别模式下就是"在追我的"
     */
    public record Foe(int id,
                      double distance,
                      boolean explosive,
                      boolean armed,
                      boolean tooClose,
                      boolean engaging,
                      boolean reachable,
                      boolean authorized) {

        /** 它已经在倒计时,<b>而且她还在爆炸波及范围里</b>。 */
        public boolean blastTooClose() {
            return armed && tooClose;
        }
    }

    /**
     * 有没有谁已经进了它的危险半径。
     *
     * <p>寻路早就在按危险半径选落脚点了;<b>判据也得每刻问一遍同一件事</b>,否则站位算得
     * 再好也没用——寻路的目标是开路那一刻的快照,而判据用的是实时距离,这一条才是防偷袭
     * 真正靠得住的地方。
     */
    public boolean anyTooClose() {
        for (Foe f : foes) {
            if (f.tooClose()) {
                return true;
            }
        }
        return false;
    }

    /** 场上有没有<b>已经在倒计时</b>而且还在爆炸范围里的——不管她在打谁,这都最先处理。 */
    public boolean underBlastThreat() {
        return foes.stream().anyMatch(Foe::blastTooClose);
    }

    public Foe byId(int id) {
        for (Foe f : foes) {
            if (f.id() == id) {
                return f;
            }
        }
        return null;
    }
}
