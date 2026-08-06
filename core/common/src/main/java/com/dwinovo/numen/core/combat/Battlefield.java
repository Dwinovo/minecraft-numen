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
 * @param foes            场上的敌对生物,顺序不重要
 */
public record Battlefield(double effectiveHealth,
                          double meleeReach,
                          boolean hasMelee,
                          boolean hasRanged,
                          List<Foe> foes) {

    /**
     * 场上的一个。
     *
     * @param id         运行时实体 id
     * @param distance   离她多远
     * @param explosive  会炸——贴上去就是进爆炸半径,见 {@link Menace#keepAwayFrom}
     * @param clearance  会炸的话该离它多远;不会炸则 0
     * @param engaging   正在针对她(锁定了她,或刚打了她)
     * @param reachable  寻路还没判定"到不了"
     * @param authorized 允许打它。点名模式下是模型给的那份清单,无差别模式下就是"在追我的"
     */
    public record Foe(int id,
                      double distance,
                      boolean explosive,
                      double clearance,
                      boolean engaging,
                      boolean reachable,
                      boolean authorized) {

        /** 它已经近到进了爆炸伤害范围。 */
        public boolean blastTooClose() {
            return explosive && distance < clearance;
        }
    }

    /** 场上有没有已经贴到爆炸范围里的东西——不管她正在打谁,这都是最先要处理的。 */
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
