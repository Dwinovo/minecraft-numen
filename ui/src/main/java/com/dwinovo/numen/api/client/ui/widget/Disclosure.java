package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;

/**
 * 折叠节的抬头:一个三角 + 标题 + 一道延到行尾的细线,点一下开合。
 *
 * <p>默认收着,把只有懂行的人才要动的东西藏在后面——新手看到的是一页能看完的东西,
 * 想细调的人点开就是。三角跟着开合转:收着朝右,展开朝下,中间是转到一半的样子。
 *
 * <p>控件自己不记开没开:状态是宿主手里的 {@link Reveal},点击翻它;里面有哪些行、占多高只有宿主算得出来,
 * 宿主照同一个 {@link Reveal#value()} 定那一截露出多高,三角与高度同步。
 */
public final class Disclosure extends Widget {

    /** 三角那一格的边长(朝右时宽 4 高 7,朝下时宽 7 高 4)。 */
    private static final int ARROW = 7;
    /** 三角与标题之间。 */
    private static final int ARROW_GAP = 5;
    /** 标题与那道细线之间。 */
    private static final int RULE_GAP = 6;

    private final String title;
    private final Reveal reveal;

    public Disclosure(String title, Reveal reveal) {
        this.title = title == null ? "" : title;
        this.reveal = reveal;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hot = contains(mouseX, mouseY);
        int fg = hot ? c.textPrimary() : c.textSecondary();

        arrow(s, fg, reveal.value());

        int tx = x + ARROW + ARROW_GAP;
        int ty = y + (h - s.lineHeight()) / 2;
        String text = TextClip.fit(s, title, Math.max(0, w - (tx - x)));
        s.drawText(text, tx, ty, fg, false);

        // 标题右边那道细线:把这一节和上面的内容划开,又不用再套一层框
        int ruleX = tx + s.textWidth(text) + RULE_GAP;
        if (ruleX < x + w) {
            s.fillRect(ruleX, y + h / 2, x + w - ruleX, 1, c.divider());
        }
    }

    /**
     * 三角:朝右的等腰三角绕自己的中心转 {@code turn}×90°(顺时针到朝下),逐像素判点在不在三角里。
     * 两头(0 与 1)转出来就是整齐的像素三角,中间是转到一半的样子。
     */
    private void arrow(IDrawSurface s, int color, float turn) {
        double a = Math.toRadians(90.0 * turn);
        double cos = Math.cos(a), sin = Math.sin(a);
        // 朝右三角,以包围盒中心为原点:底边在左(-1.5),尖在右(+2.5),高 7
        double[][] v = {{-1.5, -3.5}, {-1.5, 3.5}, {2.5, 0}};
        double[][] r = new double[3][2];
        for (int i = 0; i < 3; i++) {
            r[i][0] = v[i][0] * cos - v[i][1] * sin;
            r[i][1] = v[i][0] * sin + v[i][1] * cos;
        }
        int cx = x + ARROW / 2, cy = y + h / 2;
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

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        reveal.toggle();
        return true;
    }
}
