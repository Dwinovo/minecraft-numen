package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.Animation;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;

/**
 * 字段的底线——字段外观的唯一一种(Telegram 的 InputField):平时一道细线,聚焦时强调色的粗线从点击的那一点
 * 向两边展开,失焦时往那一点收回去。输入框、多行输入框、选择类字段({@link PickField}、{@link Dropdown} 的字段态)
 * 都只画它,不画框。出错换危险色的粗线。
 *
 * <p>时间由调用方按帧给,本类不读钟。
 */
final class FieldLine {

    private static final long MS = 200;

    /** 展开的进度 0~1(线性,画的时候再缓动)。 */
    private float t;
    private long lastMs = -1;
    /** 展开从哪一横坐标起;没点过(Tab 聚焦)是 {@link #NONE},从中间起。 */
    private static final int NONE = Integer.MIN_VALUE;
    private int originX = NONE;

    /** 记下点在哪(聚焦的那一下展开从这里起)。 */
    void press(double mx) {
        originX = (int) mx;
    }

    /** 失焦:下次聚焦若不是点的(Tab),从中间展开。 */
    void release() {
        originX = NONE;
    }

    /**
     * @param y,h    字段的顶边与高;线贴着底边
     * @param active 聚焦着(或下拉开着)
     * @param hot    指针停在上面
     */
    void draw(IDrawSurface s, NumenTheme.Colors c, int x, int y, int w, int h,
              boolean active, boolean hot, boolean error, long nowMs) {
        float step = lastMs < 0 ? 0f : Math.max(0, nowMs - lastMs) / (float) MS;
        lastMs = nowMs;
        t = active ? Math.min(1f, t + step) : Math.max(0f, t - step);

        int rest = NumenStyle.mixColor(c.inputBorder(), c.textMuted(), 0.5f);
        int base = hot && !active ? c.textSecondary() : rest;
        s.fillRect(x, y + h - 1, w, 1, error ? c.danger() : base);
        if (error) {
            s.fillRect(x, y + h - 2, w, 2, c.danger());
            return;
        }
        if (t <= 0f) {
            return;
        }
        int from = originX == NONE || originX < x || originX > x + w ? x + w / 2 : originX;
        int spread = Math.round(Animation.easeOutCubic(t) * Math.max(from - x, x + w - from));
        int l = Math.max(x, from - spread);
        int r = Math.min(x + w, from + spread);
        s.fillRect(l, y + h - 2, r - l, 2, c.accent());
    }
}
