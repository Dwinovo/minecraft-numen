package com.dwinovo.numen.api.entity;

/**
 * 她自己挨打的看护逻辑——纯 JVM,照 {@link OwnerHurtWatch} 的路子无头单测,
 * MC 侧的读取(伤害源/伤害量/血量/时刻)由 {@code NumenPlayer} 喂进来。
 *
 * <h2>和主人那份的差别</h2>
 * 主人挨打是<b>要不要去救</b>的问题,可以按血线分档慢慢来;她自己挨打是<b>要不要立刻应对</b>的问题
 * ——远处的弓箭手她看不见,不叫醒她就一直挨着。所以这里不按血线分档:实体伤害一律报急件,
 * 只用一个冷却窗口压住连击的刷屏。环境伤(摔落、岩浆)不报。
 */
public final class CompanionHurtWatch {

    /** 冷却窗口(游戏刻):一场连击每 5 秒至多叫醒一次。 */
    public static final long QUIET_TICKS = 100;

    /** 一次"她挨打":攻击者标签、结算前的伤害量、她的血量。 */
    public record Report(String attacker, float amount, float hp, float maxHp) {}

    private long quietUntil;

    /**
     * 每服务端 tick 喂一次。
     *
     * @param attacker 最近一次伤害的实体攻击者标签;null = 环境伤或没有新的伤害
     * @param amount   结算前的伤害量
     * @param hp       她当前血量
     * @param maxHp    她的最大血量
     * @param now      当前游戏刻
     */
    public Report poll(String attacker, float amount, float hp, float maxHp, long now) {
        if (attacker == null || now < quietUntil) {
            return null;
        }
        quietUntil = now + QUIET_TICKS;
        return new Report(attacker, amount, hp, maxHp);
    }
}
