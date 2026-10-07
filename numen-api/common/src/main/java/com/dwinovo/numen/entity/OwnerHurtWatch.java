package com.dwinovo.numen.entity;

import java.util.UUID;

/**
 * 主人血量的看护逻辑——纯 JVM,照 {@code UnstuckDetector} 的路子无头单测,
 * MC 侧的读取(血量/伤害源/时刻)由 {@code NumenPlayer} 喂进来。
 *
 * <h2>判定</h2>
 * 掉血且有实体攻击者才报(摔落/岩浆这类环境伤不吵)。两档:
 * <ul>
 *   <li>血量 &gt; {@link #DANGER_HP}:{@link Verdict#HURT}——消息而已,
 *       {@link #QUIET_TICKS} 冷却防刷屏;</li>
 *   <li>血量 ≤ {@link #DANGER_HP}:{@link Verdict#DANGER}——急件,<b>无视冷却</b>
 *       (血线崩了不能被冷却吃掉),但跌入只报一次,回到 {@link #RECOVER_HP} 以上
 *       才重新武装(与饥饿的 FED_LEVEL 同款迟滞)。</li>
 * </ul>
 *
 * <p>换主人或首次见到主人先校准不报——"从未知到已知"不是掉血。
 */
public final class OwnerHurtWatch {

    /** 危险线:原版低于 6(3 颗心)属于真危险区,与饥饿的 HUNGRY_LEVEL 同一条线。 */
    public static final float DANGER_HP = 6.0f;
    /** 回到这个程度才重新武装危险档——迟滞,免得在阈值上一条接一条。 */
    public static final float RECOVER_HP = 14.0f;
    /** 消息档的冷却(游戏刻):一场混战每 5 秒至多一条。 */
    public static final long QUIET_TICKS = 100;

    public enum Verdict { NONE, HURT, DANGER }

    private UUID watched;
    private float lastHp = -1;
    private long quietUntil;
    private boolean dangerReported;

    /**
     * 每服务端 tick 喂一次主人状态。
     *
     * @param ownerId          在线主人的 UUID
     * @param hp               主人当前血量
     * @param attackedByEntity 最近一次伤害有没有实体攻击者
     * @param now              当前游戏刻
     */
    public Verdict poll(UUID ownerId, float hp, boolean attackedByEntity, long now) {
        if (!ownerId.equals(watched) || lastHp < 0) {
            watched = ownerId;
            lastHp = hp;
            return Verdict.NONE;
        }
        float before = lastHp;
        lastHp = hp;
        if (hp >= RECOVER_HP) {
            dangerReported = false;
        }
        if (hp >= before || !attackedByEntity) {
            return Verdict.NONE;
        }
        if (hp <= DANGER_HP) {
            if (dangerReported) {
                return Verdict.NONE;
            }
            dangerReported = true;
            quietUntil = now + QUIET_TICKS;
            return Verdict.DANGER;
        }
        if (now < quietUntil) {
            return Verdict.NONE;
        }
        quietUntil = now + QUIET_TICKS;
        return Verdict.HURT;
    }

    /** 主人离线:忘掉基线,回来重新校准。 */
    public void reset() {
        watched = null;
        lastHp = -1;
    }
}
