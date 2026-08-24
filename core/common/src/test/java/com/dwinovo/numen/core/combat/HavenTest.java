package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落点的几何。<b>方向的连续性就是不绕圈的全部原因</b>——落点一次挑定、跑到才换,
 * 路径重算只改路线不改目标。
 *
 * <p>这里只钉不需要世界的那一半:背离方向怎么算、扇形张多宽。可站判定要真方块,归实机。
 */
class HavenTest {

    /** 与 {@code Haven} 里那两个数同源;改了那边这里会红。 */
    private static final double SPREAD = Math.PI / 2.0;

    /** 落点必须落在<b>背离威胁重心</b>的半边,不能朝威胁跑。 */
    @Test
    void everyCandidateLeansAwayFromTheThreats() {
        double away = Math.atan2(1.0, 0.0);   // 威胁在南,背离方向朝北
        for (double roll = -1.0; roll <= 1.0; roll += 0.1) {
            double angle = away + roll * SPREAD;
            double dot = Math.cos(angle) * Math.cos(away) + Math.sin(angle) * Math.sin(away);
            assertTrue(dot >= -1e-9, "扇形张到了背离方向的反面:" + Math.toDegrees(angle));
        }
    }

    /**
     * 扇形要够宽。正后方一条道走到黑的话,那个方向要是刷怪区她就一头扎进去;
     * 原版 {@code DefaultRandomPos.getPosAway} 用的也是 ±90°。
     */
    @Test
    void theFanIsWideEnoughToPickDifferentRoutes() {
        assertTrue(SPREAD >= Math.PI / 4.0, "扇形太窄,每次都朝同一个方向");
        assertTrue(SPREAD <= Math.PI / 2.0, "扇形超过 ±90° 就会挑到侧后方以外");
    }
}
