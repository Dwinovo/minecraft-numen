package com.dwinovo.numen.core.combat;

import java.util.HashMap;
import java.util.Map;

/**
 * 「逃掉了没有」——看的是<b>还有没有东西正在缩短与她的距离</b>。
 *
 * <h2>为什么不看距离、也不看仇恨</h2>
 * 距离是代理量:十五格外一只正在冲刺的蜘蛛,比五格外一只卡在坑里的僵尸危险得多。
 * 仇恨方向更是反的:一只僵尸能在二十格外锁定她一整晚却永远打不到她——<b>仇恨不是伤害</b>。
 *
 * <p>退避的目的是别再挨打,所以判据直接问那件事:谁还在逼近。
 *
 * <h2>它为什么不需要一张怪物能力表</h2>
 * 每只追击者记一个「离她最近曾到过多少」。卡在柱子下的僵尸再也刷新不了这个值;会爬墙的
 * 蜘蛛、会传送的末影人则一直在刷新。<b>看的是结果不是能力</b>,所以模组怪、以后加的怪
 * 全部自动适用,不用维护"谁会爬墙谁会游泳"。
 *
 * <h2>用法</h2>
 * 每 tick {@link #observe} 一次当前追击者,然后问 {@link #escaped}。
 * 实例跟着一次交战走(任务对象里),交战结束一起消失——不进静态表,也就没有清理问题。
 */
public final class Pursuit {

    /** 追击停滞这么久才算真的甩掉。太短会在怪绕路的空档里误判"安全了"。 */
    private static final long STALL_TICKS = 40;

    /**
     * 距离要缩短超过这么多才算"它在逼近"。
     *
     * <p>没有这道门槛,一只原地站着的怪也会因为浮点抖动与碰撞箱微动不停刷新最近距离,
     * 于是"停滞"永远不成立,她就永远逃不掉。
     */
    private static final double CLOSER_EPSILON = 0.25;

    private static final long NEVER = Long.MIN_VALUE;

    /** 每只追击者离她最近曾到过多少(平方距离)。 */
    private final Map<Integer, Double> closestSeen = new HashMap<>();
    /** 从哪一刻起没有人再逼近;{@link #NEVER} = 这一刻还有人在逼近。 */
    private long stalledSince = NEVER;

    /**
     * 喂一遍这一刻的追击者。
     *
     * @param distancesSquared 追击者的实体 id → 它到她的平方距离;空表示没人追
     * @param now              当前游戏时间(刻)
     */
    public void observe(Map<Integer, Double> distancesSquared, long now) {
        closestSeen.keySet().retainAll(distancesSquared.keySet());   // 走掉的不再记账
        boolean someoneClosing = false;
        for (Map.Entry<Integer, Double> e : distancesSquared.entrySet()) {
            Double best = closestSeen.get(e.getKey());
            if (best == null) {
                closestSeen.put(e.getKey(), e.getValue());
                someoneClosing = true;   // 新出现的追击者:先当它在逼近
            } else if (e.getValue() < best - CLOSER_EPSILON) {
                closestSeen.put(e.getKey(), e.getValue());
                someoneClosing = true;
            }
        }
        if (someoneClosing) {
            stalledSince = NEVER;
        } else if (stalledSince == NEVER && !distancesSquared.isEmpty()) {
            stalledSince = now;
        }
    }

    /**
     * 甩掉了没有。
     *
     * <p>没人追是立刻算数;有人追但谁都不再逼近,得稳住 {@link #STALL_TICKS} 才算。
     */
    public boolean escaped(long now) {
        if (closestSeen.isEmpty()) {
            return true;
        }
        return stalledSince != NEVER && now - stalledSince >= STALL_TICKS;
    }

    /** 换了一场交战就从头记。 */
    public void reset() {
        closestSeen.clear();
        stalledSince = NEVER;
    }
}
