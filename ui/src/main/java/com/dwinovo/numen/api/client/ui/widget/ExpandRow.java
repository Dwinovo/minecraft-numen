package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;

/**
 * 开合一截({@link SlideWrap})的那一行,样子和选择行({@link Dropdown#row})一样:整行是一个钮,左边标签,悬停整行浮出浅底。
 * 右端一个三角跟着开合转(收着朝右、展开朝下)。
 *
 * <p>控件不记开没开:点击翻宿主手里的 {@link Reveal},三角和那一截露出多高都照同一个 {@link Reveal#value()}。
 */
public final class ExpandRow extends Widget {

    private final String label;
    private final int inset;
    private final Reveal reveal;
    private float hoverT;
    private long lastFrameMs = -1;

    /** @param inset 两端文字到行边的距离(与同一张卡上的选择行相同) */
    public ExpandRow(String label, int inset, Reveal reveal) {
        this.label = label == null ? "" : label;
        this.inset = inset;
        this.reveal = reveal;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hovered = contains(mouseX, mouseY);
        long dt = lastFrameMs < 0 ? 1000 : nowMs - lastFrameMs;
        lastFrameMs = nowMs;
        hoverT = NumenStyle.hoverStep(hoverT, hovered, dt);
        if (hoverT > 0.01f) {
            int overlay = ((int) (((c.hover() >>> 24) & 0xFF) * hoverT) << 24) | (c.hover() & 0xFFFFFF);
            s.fillRect(x, y, w, h, overlay);
        }
        int ty = y + (h - s.lineHeight()) / 2 + 1;
        s.drawText(label, x + inset, ty, c.textPrimary(), false);

        int arrowCx = x + w - inset - TurnArrow.SIZE / 2 - 1;
        TurnArrow.draw(s, arrowCx, y + h / 2, c.textSecondary(), reveal.value());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        reveal.toggle();
        return true;
    }
}
