package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.entity.CompanionHurtWatch.Report;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 她自己挨打的分档与去抖。判据:实体攻击一律报急件(远处的弓箭手看不见,不叫醒她就一直挨着),
 * 环境伤不报、也不占用冷却;连击被冷却窗口压住。
 */
class CompanionHurtWatchTest {

    private final CompanionHurtWatch watch = new CompanionHurtWatch();

    /** 摔落、岩浆这类环境伤不吵。 */
    @Test
    void environmentDamageIsNotReported() {
        assertNull(watch.poll(null, 4, 16, 20, 0));
    }

    /** 实体伤害报:攻击者、伤害量与她的血量都带出来。 */
    @Test
    void anEntityHitIsReported() {
        Report report = watch.poll("wither_skeleton", 4, 16, 20, 0);

        assertNotNull(report);
        assertEquals("wither_skeleton", report.attacker());
        assertEquals(4, report.amount());
        assertEquals(16, report.hp());
        assertEquals(20, report.maxHp());
    }

    /** 连击里后续的几箭被冷却窗口压住,窗口一过再叫。 */
    @Test
    void followUpHitsInsideTheWindowAreSwallowed() {
        assertNotNull(watch.poll("zombie", 3, 17, 20, 0));
        assertNull(watch.poll("zombie", 3, 14, 20, 1));
        assertNotNull(watch.poll("zombie", 3, 11, 20, CompanionHurtWatch.QUIET_TICKS));
    }

    /** 环境伤不占用冷却:它报不出来,也不该把紧随其后的真伤害一起吃掉。 */
    @Test
    void environmentDamageDoesNotStartTheWindow() {
        assertNull(watch.poll(null, 4, 16, 20, 0));
        assertNotNull(watch.poll("zombie", 3, 13, 20, 1));
    }
}
