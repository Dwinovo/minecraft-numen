package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 危险半径那把尺子。这里只钉不需要实体的那几条。 */
class MenaceTest {

    /** 原版 {@code Mob.DEFAULT_ATTACK_REACH}。 */
    private static final double ATTACK_REACH = Math.sqrt(2.04) - 0.6;

    /** {@code Menace.strikeRangeOf} 的算式,拿宽度直接算,免得测试要造实体。 */
    private static double strikeRange(double attackerWidth, double victimWidth) {
        return (attackerWidth / 2.0 + ATTACK_REACH + victimWidth / 2.0) * Math.sqrt(2.0);
    }

    /**
     * 安全圆必须是方框判定的<b>外接圆</b>。用内切圆(不乘 √2)是错的:中心距 1.43 时若在
     * 对角方向,两轴间隙各 1.01,照样打得到。
     */
    @Test
    void theSafeCircleCircumscribesTheAttackBox() {
        double perAxis = 0.3 + ATTACK_REACH + 0.3;      // 僵尸对玩家,每轴
        double radius = strikeRange(0.6, 0.6);
        assertEquals(perAxis * Math.sqrt(2.0), radius, 1e-9);
        // 对角上最远的那个可达点,恰好落在圆上
        assertEquals(Math.hypot(perAxis, perAxis), radius, 1e-9);
    }

    /** 越宽的怪够得越远——这就是它不能是个常数的原因。 */
    @Test
    void widerMobsReachFurther() {
        double zombie = strikeRange(0.6, 0.6);
        double spider = strikeRange(1.4, 0.6);
        double bigSlime = strikeRange(2.04, 0.6);
        assertTrue(zombie < spider);
        assertTrue(spider < bigSlime);
    }

    /**
     * <b>够得着 > 危险半径</b>,退到边缘就能打——这条不等式是"不需要迟滞"的全部依据。
     *
     * <p>够到距离要算上目标半宽:原版那 3.0 是从眼睛射到<b>碰撞箱</b>,不是到中心。
     * 大史莱姆宽 2.04,不加半宽会被判成"够不着",而原版玩家打得到。
     */
    @Test
    void herReachBeatsTheirDangerRadius() {
        double slack = Math.sqrt(2.0) / 2.0;            // Menace.CELL_SLACK
        for (double width : new double[] {0.6, 1.4, 2.04}) {
            double reach = Swing.reachTo(3.0, width);
            double danger = strikeRange(width, 0.6) + slack;
            assertTrue(reach > danger,
                    "宽 " + width + " 的怪没留出窗口:够到 " + reach + ",危险 " + danger);
        }
    }
}
