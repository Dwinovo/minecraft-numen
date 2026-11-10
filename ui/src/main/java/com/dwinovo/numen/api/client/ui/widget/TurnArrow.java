package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.IDrawSurface;

/**
 * 开合的三角:朝右的等腰三角绕自己的中心转 {@code turn}×90°(顺时针到朝下),逐像素判点在不在三角里。两头(0 与 1)转出来
 * 就是整齐的像素三角,中间是转到一半的样子。{@link Disclosure} 与 {@link ExpandRow} 画的都是它。
 */
final class TurnArrow {

    /** 三角那一格的边长(朝右时宽 4 高 7,朝下时宽 7 高 4)。 */
    static final int SIZE = 7;

    private TurnArrow() {}

    /** 以 {@code (cx, cy)} 为中心画;{@code turn} 0 朝右,1 朝下。 */
    static void draw(IDrawSurface s, int cx, int cy, int color, float turn) {
        double a = Math.toRadians(90.0 * turn);
        double cos = Math.cos(a), sin = Math.sin(a);
        // 朝右三角,以包围盒中心为原点:底边在左(-1.5),尖在右(+2.5),高 7
        double[][] v = {{-1.5, -3.5}, {-1.5, 3.5}, {2.5, 0}};
        double[][] r = new double[3][2];
        for (int i = 0; i < 3; i++) {
            r[i][0] = v[i][0] * cos - v[i][1] * sin;
            r[i][1] = v[i][0] * sin + v[i][1] * cos;
        }
        for (int py = -4; py <= 4; py++) {
            for (int px = -4; px <= 4; px++) {
                if (inside(r, px + 0.5, py + 0.5)) {
                    s.fillRect(cx + px, cy + py, 1, 1, color);
                }
            }
        }
    }

    private static boolean inside(double[][] t, double px, double py) {
        double d1 = sign(px, py, t[0], t[1]);
        double d2 = sign(px, py, t[1], t[2]);
        double d3 = sign(px, py, t[2], t[0]);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0;
        boolean pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    private static double sign(double px, double py, double[] a, double[] b) {
        return (px - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (py - b[1]);
    }
}
