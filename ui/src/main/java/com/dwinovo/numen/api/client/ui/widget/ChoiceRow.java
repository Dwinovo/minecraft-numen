package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;

/**
 * 选择行里点了去别处选的那一种:整行是一个钮,左边标签、右边当前值(强调色),悬停整行浮出浅底——和 {@link Dropdown#row}
 * 一个样子,只是点下去不展开弹层,而是 {@code onClick}(开另一张卡去选)。
 */
public final class ChoiceRow extends Widget {

    private final String label;
    private final String value;
    private final int inset;
    private final Runnable onClick;
    private float hoverT;
    private long lastFrameMs = -1;

    public ChoiceRow(String label, String value, int inset, Runnable onClick) {
        this.label = label;
        this.value = value;
        this.inset = inset;
        this.onClick = onClick;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hovered = enabled && contains(mouseX, mouseY);
        long dt = lastFrameMs < 0 ? 1000 : nowMs - lastFrameMs;
        lastFrameMs = nowMs;
        hoverT = NumenStyle.hoverStep(hoverT, hovered, dt);
        if (enabled && hoverT > 0.01f) {
            int overlay = ((int) (((c.hover() >>> 24) & 0xFF) * hoverT) << 24) | (c.hover() & 0xFFFFFF);
            s.fillRect(x, y, w, h, overlay);
        }
        int ty = y + (h - s.lineHeight()) / 2 + 1;
        s.drawText(label, x + inset, ty, c.textPrimary(), false);
        String shown = TextClip.fit(s, value, Math.max(0, w - inset * 2 - s.textWidth(label) - 8));
        s.drawText(shown, x + w - inset - s.textWidth(shown), ty, enabled ? c.accent() : c.textMuted(), false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my) || !enabled) {
            return false;
        }
        onClick.run();
        return true;
    }
}
