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
        int w = s.textWidth(text) + PAD_X * 2;
        int h = s.lineHeight() + PAD_Y * 2 - 2;
        s.fillRoundRect(x, y, w, h, 2, c.badgeBg());
        s.drawText(text, x + PAD_X, y + PAD_Y, c.badgeText(), false);
        return w;
    }
}
