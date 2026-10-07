package com.dwinovo.numen.entity;

import com.dwinovo.numen.entity.OwnerHurtWatch.Verdict;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 主人看护的分档与去抖。判据:掉血且有攻击者才报;安全区是带冷却的消息,
 * 危险区(≤6,原版跑不动线)是急件且无视冷却,但跌入只报一次、回 14 再武装。
 */
class OwnerHurtWatchTest {

    private final OwnerHurtWatch watch = new OwnerHurtWatch();
    private final UUID owner = UUID.randomUUID();

    /** 首见校准:从未知到已知不是掉血。 */
    @Test
    void firstSightCalibratesSilently() {
        assertEquals(Verdict.NONE, watch.poll(owner, 20, true, 0));
        assertEquals(Verdict.NONE, watch.poll(owner, 20, false, 1));
    }

    @Test
    void entityHitReportsButEnvironmentDoesNot() {
        watch.poll(owner, 20, false, 0);
        assertEquals(Verdict.NONE, watch.poll(owner, 16, false, 1), "摔落这类环境伤不吵");
        assertEquals(Verdict.HURT, watch.poll(owner, 12, true, 2));
    }

    @Test
    void quietWindowSwallowsFollowUpScratches() {
        watch.poll(owner, 20, true, 0);
        assertEquals(Verdict.HURT, watch.poll(owner, 18, true, 1));
        assertEquals(Verdict.NONE, watch.poll(owner, 16, true, 50), "冷却内的后续消息压住");
        assertEquals(Verdict.HURT, watch.poll(owner, 15, true, 1 + OwnerHurtWatch.QUIET_TICKS));
    }

    /** 血线崩了不能被冷却吃掉。 */
    @Test
    void droppingIntoDangerBypassesTheQuietWindow() {
        watch.poll(owner, 20, true, 0);
        assertEquals(Verdict.HURT, watch.poll(owner, 18, true, 1));
        assertEquals(Verdict.DANGER, watch.poll(owner, 5, true, 10));
    }

    /** 跌入只报一次;回过血再跌才再报。 */
    @Test
    void dangerReportsOncePerDip() {
        watch.poll(owner, 8, true, 0);
        assertEquals(Verdict.DANGER, watch.poll(owner, 5, true, 1));
        assertEquals(Verdict.NONE, watch.poll(owner, 3, true, 2), "还在坑里,不重复报");
        // 回血到武装线之上,再跌回坑里 → 新的一次危险
        watch.poll(owner, OwnerHurtWatch.RECOVER_HP, true, 500);
        assertEquals(Verdict.DANGER, watch.poll(owner, 4, true, 501));
    }

    /** 换主人重新校准,不把两个人的血量差当掉血。 */
    @Test
    void ownerSwapRecalibrates() {
        watch.poll(owner, 20, true, 0);
        assertEquals(Verdict.NONE, watch.poll(UUID.randomUUID(), 6, true, 1));
    }

    /** 离线重置基线:回来那一刻的血量是新起点。 */
    @Test
    void resetForgetsTheBaseline() {
        watch.poll(owner, 20, true, 0);
        watch.reset();
        assertEquals(Verdict.NONE, watch.poll(owner, 10, true, 1));
    }
}
