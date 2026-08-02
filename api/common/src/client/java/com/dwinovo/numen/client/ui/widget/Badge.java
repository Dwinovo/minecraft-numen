package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;

/** 小徽章(协议 [A]、推理 [R]、本地 [L] 这类能力标)。静态绘制助手,不是控件。 */
public final class Badge {

    private Badge() {}

    private static final int PAD_X = 3;
    private static final int PAD_Y = 1;

    /** 画一枚徽章,返回占用宽度(调用方用于横排下一枚)。 */
    public static int draw(IDrawSurface s, NumenTheme.Colors c, String text, int x, int y) {
        return draw(s, text, x, y, c.badgeBg(), c.badgeText());
    }

    /** 带色变体:能力徽章按语义分色(协议=accent、本地=success 之类)。 */
    public static int draw(IDrawSurface s, String text, int x, int y, int bg, int fg) {
        int w = s.textWidth(text) + PAD_X * 2;
        int h = s.lineHeight() + PAD_Y * 2 - 2;
        s.fillRoundRect(x, y, w, h, 2, bg);
        s.drawText(text, x + PAD_X, y + PAD_Y, fg, false);
        return w;
    }
}
