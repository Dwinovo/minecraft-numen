package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;

/**
 * 静态文本。Role 决定取哪个语义色槽:正文,或者次级(表单标签、说明)。比次级更淡的只属于占位字和时间戳,不用 Label 画。
 * 超出自身宽度按码点截断缀省略号。
 */
public final class Label extends Widget {

    public enum Role { PRIMARY, SECONDARY }

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
        };
        s.drawText(TextClip.fit(s, text, w), x, y, color, false);
    }
}
