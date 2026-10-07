package com.dwinovo.numen.client.screen.settings;

import com.dwinovo.numen.client.screen.UiTheme;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.widget.Label;
import com.dwinovo.numen.client.ui.widget.UiRoot;
import com.dwinovo.numen.client.ui.widget.Widget;

/**
 * 设置分区页的版式,照 Telegram 设置行:一行一项,左边标签,右边是值、开关或输入控件;
 * 一组与一组之间一道宽缝。外接大脑、语音输入两页共用这一处几何,行高、缝宽、标签位置不各算各的。
 */
final class SettingsRows {

    /** 一行的高:控件上下各留两格。 */
    static final int ROW_H = NumenStyle.CONTROL_H + 4;
    /** 组间宽缝的高(Telegram 的 boxDividerHeight)。 */
    static final int GAP_H = 8;
    /** 右侧控件占行宽的比例上限:左边的标签至少留得下这么宽。 */
    private static final int LABEL_MIN_W = 70;

    private SettingsRows() {}

    /** 高 {@code h} 的控件放进从 {@code rowY} 起的一行,垂直居中时的顶边。 */
    static int controlY(int rowY, int h) {
        return NumenStyle.centerIn(rowY, ROW_H, h);
    }

    /** 右侧输入控件(下拉、输入框)的宽:行宽的六成,标签那一侧至少留 {@link #LABEL_MIN_W}。 */
    static int controlW(int w) {
        return Math.min(w * 3 / 5, w - LABEL_MIN_W);
    }

    /** 行左端的标签(Telegram 设置行的正文色),宽到 {@code labelW} 为止,行内垂直居中。 */
    static Label label(UiRoot ui, String text, int x, int rowY, int labelW) {
        Label l = ui.add(new Label(text, Label.Role.PRIMARY));
        l.setBounds(x, controlY(rowY, 9), labelW, 9);
        return l;
    }

    /**
     * 组间宽缝,从 {@code y} 起高 {@link #GAP_H}。铺满整页宽:分区内容两侧各内缩 {@link NumenStyle#PAD},
     * 缝往两侧各伸出这么多,和设置首页的行一样贴着页边。做成控件是为了跟着滚动层一起走。
     */
    static void gap(UiRoot ui, int x, int y, int w) {
        ui.add(new Gap()).setBounds(x - NumenStyle.PAD, y, w + NumenStyle.PAD * 2, GAP_H);
    }

    /**
     * 缝本身:Telegram 的分隔条(boxDividerBg = windowBgOver),上下各一道分隔色的边,
     * 看上去是两组之间凹下去的一条。不接点击。
     */
    private static final class Gap extends Widget {
        @Override
        public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
            UiTheme t = UiTheme.current();
            s.fillRect(x, y, w, h, t.over());
            s.fillRect(x, y, w, 1, t.border());
            s.fillRect(x, y + h - 1, w, 1, t.border());
        }
    }
}
