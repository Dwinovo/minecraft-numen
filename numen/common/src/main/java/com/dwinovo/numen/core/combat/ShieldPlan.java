package com.dwinovo.numen.core.combat;

/**
 * 举不举盾。<b>攻击冷却没好就举,好了就放下砍</b> —— 真人玩家的节奏。
 *
 * <h2>为什么不是"挨打就举"也不是"一直举着"</h2>
 * 原版的盾有三条硬约束,它们把形状定死了:
 *
 * <ul>
 *   <li>举起来要<b>五刻</b>才开始挡({@code shieldBlockingDelay}) —— 挨打那一刻才举,来不及</li>
 *   <li>举着<b>会减速</b> —— 一直举着就走不了位,拉不开距离</li>
 *   <li>举盾和拉弓抢同一个 {@code useItem} —— 弓战斗时根本轮不到它</li>
 * </ul>
 *
 * <p>而"攻击冷却没好"这段窗口本来就什么都做不了,减速的代价正好落在这儿,五刻的延迟也
 * 由这段窗口吸收(剑的冷却是十二刻半)。
 *
 * <p>这套判据与 PR #13 的 {@code ShieldCombatPolicy} 一致 —— 那边先想到的。
 */
public final class ShieldPlan {

    /** 这一刻拿盾做什么。 */
    public enum Decision {
        /** 不关盾的事,该干嘛干嘛。 */
        PROCEED,
        /** 手上正用着别的东西(拉弓、吃东西),这一刻别碰。 */
        WAIT,
        /** 举起来。 */
        RAISE,
        /** 举着别放。 */
        HOLD,
        /** 放下 —— 冷却好了,该砍了。 */
        RELEASE
    }

    private ShieldPlan() {}

    /**
     * @param shieldUsable  副手有盾且不在冷却里(被斧子破盾会进冷却)
     * @param usingOtherItem 正在用别的东西:拉弓、吃东西
     * @param shieldRaised   这一刻盾已经举着
     * @param attackReady    攻击充能到位,见 {@link Swing#ATTACK_READY}
     */
    public static Decision decide(boolean shieldUsable, boolean usingOtherItem,
                                  boolean shieldRaised, boolean attackReady) {
        if (usingOtherItem) {
            return Decision.WAIT;
        }
        if (shieldRaised) {
            return attackReady ? Decision.RELEASE : Decision.HOLD;
        }
        if (shieldUsable && !attackReady) {
            return Decision.RAISE;
        }
        return Decision.PROCEED;
    }
}
