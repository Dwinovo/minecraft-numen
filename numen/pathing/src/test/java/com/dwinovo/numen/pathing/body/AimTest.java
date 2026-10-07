package com.dwinovo.numen.pathing.body;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 照鼠标转头:每个方向都转整数个像素、落在离要的角度半个像素以内;水平朝向走近的那一边;俯仰夹在 ±90 之间。 */
class AimTest {

    /** 浮点误差的容差:角度是 float,几百度时末位约 1e-5。 */
    private static final double SLACK = 1e-3;

    /** {@code delta} 是整数个像素。 */
    private static void wholePixels(double delta, String what) {
        double pixels = delta / Aim.PIXEL;
        assertEquals(Math.rint(pixels), pixels, SLACK, what + " 转了 " + pixels + " 个像素");
    }

    /** 转到的角度是整数个像素,离要的角度不到半个像素。 */
    @Test
    void aTurnMovesWholeMousePixelsAndLandsWithinHalfAPixel() {
        float fromYaw = 10.3F;
        float fromPitch = 5.1F;
        float[][] wanted = {{47.77F, -33.2F}, {-61.04F, 71.9F}, {10.3F, 5.1F}, {10.31F, 5.2F}};
        for (float[] w : wanted) {
            Aim.Rotation to = Aim.turned(fromYaw, fromPitch, w[0], w[1]);
            wholePixels(to.yaw() - fromYaw, "水平");
            wholePixels(to.pitch() - fromPitch, "俯仰");
            assertTrue(Math.abs(to.yaw() - w[0]) <= Aim.PIXEL / 2 + SLACK, "水平转到 " + to.yaw() + ",要的是 " + w[0]);
            assertTrue(Math.abs(to.pitch() - w[1]) <= Aim.PIXEL / 2 + SLACK, "俯仰转到 " + to.pitch() + ",要的是 " + w[1]);
        }
    }

    /** 从 170 度转到 -170 度走近的那一边:往正方向转 20 度,不绕 340 度回去;反过来也一样。 */
    @Test
    void yawTurnsTheShortWayRound() {
        Aim.Rotation right = Aim.turned(170, 0, -170, 0);
        assertEquals(20, right.yaw() - 170, Aim.PIXEL / 2 + SLACK);
        Aim.Rotation left = Aim.turned(-170, 0, 170, 0);
        assertEquals(-20, left.yaw() + 170, Aim.PIXEL / 2 + SLACK);
    }

    /** 俯仰到头就是 ±90:要的角度越过去了,停在头上。 */
    @Test
    void pitchStopsAtStraightUpAndStraightDown() {
        assertEquals(90, Aim.turned(0, 80, 0, 120).pitch());
        assertEquals(-90, Aim.turned(0, -80, 0, -135).pitch());
        Aim.Rotation down = Aim.turned(0, 0, 0, 90);
        assertTrue(down.pitch() <= 90 && down.pitch() > 90 - Aim.PIXEL, "正好低头到底:" + down.pitch());
    }
}
