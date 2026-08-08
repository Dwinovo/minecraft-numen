package com.dwinovo.numen.core.combat;

/**
 * 什么时候可以挥这一下。<b>全仓只此一处</b>——挥击时机曾经在两个地方各写一份阈值
 * (任务里 0.99、身体动作里 0.95),同一个判断给出两种答案,而两处都在打同一只怪。
 */
public final class Swing {

    /**
     * 攻击充能到几成就出手。
     *
     * <p>不等满 1.0 是算过的:剑的冷却是 12.5 刻而计数器是整数,等满要多花一整刻。
     * 第 12 刻时充能 0.96,伤害系数 {@code 0.2 + f² × 0.8} = 0.937,折合 0.0781/刻;
     * 等到第 13 刻拿满系数 1.0,只有 0.0769/刻。<b>早半刻出手反而更划算</b>。
     * 0.95 同时高过原版判暴击的 0.9,所以不影响暴击。
     */
    public static final float ATTACK_READY = 0.95f;

    /**
     * 原版玩家的近战够到距离:{@code Attributes.ENTITY_INTERACTION_RANGE} 的默认值 3.0。
     * 只作下限,属性被模组调高就跟着高;调低时不至于变成够不着任何东西。
     */
    private static final double MIN_REACH = 3.0;

    private Swing() {}

    /** 她这一刻能够到多远,<b>眼睛到碰撞箱</b>——原版那条射线的长度。 */
    public static double reachOf(double nativeInteractionRange) {
        return Math.max(MIN_REACH, nativeInteractionRange);
    }

    /**
     * 她能够到 {@code target} 的<b>中心距离</b>。
     *
     * <p>原版那 3.0 格是从眼睛沿视线射到目标<b>碰撞箱</b>为止,而目标的箱子往她这边探出
     * 半个宽度,所以中心距要加回来({@code Player} 里那句
     * {@code entityInteractionRange() + distance} 就是这个意思)。
     *
     * <p>差别不小:大史莱姆宽 2.04,光半宽就一格出头。按 3.0 硬比会把它判成"够不着",
     * 而原版玩家是打得到的——判据与站位都要这个数,不是那个 3.0。
     */
    public static double reachTo(double nativeInteractionRange, double targetWidth) {
        return reachOf(nativeInteractionRange) + targetWidth / 2.0;
    }

    /**
     * 这一下能不能挥出去。
     *
     * @param weaponChanged   这一刻刚换过手上的东西——换手当刻不出手,属性还没结算完
     * @param targetRecovering 目标还在受击无敌帧里,现在打上去伤害会被吞掉
     * @param attackStrengthScale 原版的攻击充能({@code getAttackStrengthScale})
     */
    public static boolean mayStrike(boolean weaponChanged, boolean targetRecovering,
                                    float attackStrengthScale) {
        return !weaponChanged && !targetRecovering && attackStrengthScale >= ATTACK_READY;
    }
}
