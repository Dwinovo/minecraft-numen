package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;

/** 静态文本。Role 决定取哪个语义色槽。 */
public final class Label extends Widget {

    public enum Role { PRIMARY, SECONDARY, MUTED }

    private String text;
    private Role role;

    public Label(String text, Role role) {
        this.text = text == null ? "" : text;
        this.role = role;
    }

    public void setText(String text) { this.text = text == null ? "" : text; }

    public String text() { return text; }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        int color = switch (role) {
            case PRIMARY -> c.textPrimary();
            case SECONDARY -> c.textSecondary();
            case MUTED -> c.textMuted();
        };
        s.drawText(text, x, y, color, false);
    }
}
