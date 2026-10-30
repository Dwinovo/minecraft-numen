package com.dwinovo.numen.api.combat;

/**
 * 射出去的东西怎么飞,攻击任务据此算瞄准:抛物线({@link Arc},箭、弹射物)或命中扫描({@link Hitscan},枪:一条线,没有飞行时间)。
 */
public sealed interface Flight {

    /**
     * 受重力和阻力的弹射物,每刻的运动同原版的箭:{@code v = v * drag; v.y -= gravity}。
     *
     * @param velocity              出膛初速(格/刻)
     * @param hitboxRadius          弹射物的碰撞半径
     * @param copiesShooterVelocity 出膛速度叠上射手的速度(原版的弓是,弩不是)
     */
    record Arc(double velocity, double gravity, double drag, double hitboxRadius, boolean copiesShooterVelocity)
            implements Flight {}

    /** 一条线打过去:视线所及、没有方块挡着就打得到。 */
    record Hitscan() implements Flight {}
}
