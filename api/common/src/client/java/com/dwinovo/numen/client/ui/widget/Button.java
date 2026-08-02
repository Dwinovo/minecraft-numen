package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

/** 按钮。Style 选语义色:普通/强调(主操作)/危险(删除类)。 */
public final class Button extends Widget {

    /** GHOST = 幽灵钮(图标类):平时无底,悬停浮现浅底——主流模态 ✕ 的标准形态。 */
    public enum Style { NORMAL, ACCENT, DANGER, GHOST }

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
        if (style == Style.GHOST) {
            if (hovered) s.fillRoundRect(x, y, w, h, NumenStyle.RADIUS_CONTROL, c.hover());
            int ghostColor = !enabled ? c.textMuted() : hovered ? c.textPrimary() : c.textSecondary();
            s.drawText(label, x + (w - s.textWidth(label)) / 2,
                    y + (h - s.lineHeight()) / 2 + 1, ghostColor, false);
            return;
        }
        int bg = switch (style) {
            case ACCENT -> c.accent();
            case DANGER -> c.danger();
            default -> hovered ? c.hover() : c.sectionBg();
        };
        if (hovered && style != Style.NORMAL) bg = NumenStyle.hoverBrighten(bg);
        if (!enabled) bg = c.sectionBg();
        s.fillRoundRect(x, y, w, h, NumenStyle.RADIUS_CONTROL, bg);
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

}
