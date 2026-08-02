package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;

/** 按钮。Style 选语义色:普通/强调(主操作)/危险(删除类)。 */
public final class Button extends Widget {

    public enum Style { NORMAL, ACCENT, DANGER }

    private String label;
    private final Style style;
    private final Runnable onClick;

    public Button(String label, Style style, Runnable onClick) {
        this.label = label;
        this.style = style;
        this.onClick = onClick;
    }

    public void setLabel(String label) { this.label = label; }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hovered = enabled && contains(mouseX, mouseY);
        int bg = switch (style) {
            case ACCENT -> c.accent();
            case DANGER -> c.danger();
            case NORMAL -> hovered ? c.hover() : c.sectionBg();
        };
        if (hovered && style != Style.NORMAL) bg = brighten(bg);
        if (!enabled) bg = c.sectionBg();
        s.fillRoundRect(x, y, w, h, 3, bg);
        int textColor = !enabled ? c.textMuted()
                : style == Style.NORMAL ? c.textPrimary() : 0xFFFFFFFF;
        int tx = x + (w - s.textWidth(label)) / 2;
        int ty = y + (h - s.lineHeight()) / 2 + 1;
        s.drawText(label, tx, ty, textColor, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my) || !enabled) return false;
        onClick.run();
        return true;
    }

    /** 悬停提亮:各通道向 255 走 15%。 */
    private static int brighten(int argb) {
        int a = argb & 0xFF000000;
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        r += (255 - r) * 15 / 100;
        g += (255 - g) * 15 / 100;
        b += (255 - b) * 15 / 100;
        return a | (r << 16) | (g << 8) | b;
    }
}
