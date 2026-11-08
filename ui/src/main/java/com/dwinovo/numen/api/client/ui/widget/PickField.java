package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;

/**
 * 按钮式字段:外观和输入框一样只有底线({@link FieldLine}),一行字——左边是当前值的名字、右边一小段次要文字
 * (id、主机名),最右一个 ›;点一下交给宿主去开{@link PickerBox}。它自己不存值,宿主换了值就 {@link #setText}。
 */
public final class PickField extends Widget {

    private String main;
    private String sub;
    private final Runnable onClick;
    /** 悬停进度(0~1,HOVER_MS 走满),和下拉同一种收放。 */
    private float hoverT;
    private long lastFrameMs = -1;
    private final FieldLine line = new FieldLine();

    public PickField(String main, String sub, Runnable onClick) {
        this.onClick = onClick;
        setText(main, sub);
    }

    /** 值是空的时候(还没选)显示的提示字,淡色。 */
    private String placeholder = "";

    public PickField placeholder(String text) {
        this.placeholder = text == null ? "" : text;
        return this;
    }

    public void setText(String main, String sub) {
        this.main = main == null ? "" : main;
        this.sub = sub == null ? "" : sub;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        long dt = lastFrameMs < 0 ? 1000 : nowMs - lastFrameMs;
        lastFrameMs = nowMs;
        hoverT = NumenStyle.hoverStep(hoverT, enabled && contains(mouseX, mouseY), dt);
        if (hoverT > 0.01f) {
            int overlay = ((int) (((c.hover() >>> 24) & 0xFF) * hoverT) << 24) | (c.hover() & 0xFFFFFF);
            s.fillRect(x, y, w, h - 1, overlay);
        }
        line.draw(s, c, x, y, w, h, false, enabled && contains(mouseX, mouseY), false, nowMs);
        int ty = y + (h - s.lineHeight()) / 2 + 1;
        int caretX = x + w - 4 - s.textWidth("›");
        s.drawText("›", caretX, ty, c.textSecondary(), false);
        int right = caretX - 6;
        int left = x + NumenStyle.FIELD_PAD + 1;
        String subText = TextClip.fit(s, sub, Math.max(0, (right - left) * 2 / 5));
        int subW = s.textWidth(subText);
        if (subW > 0) s.drawText(subText, right - subW, ty, c.textSecondary(), false);
        int mainRoom = right - left - (subW > 0 ? subW + 8 : 0);
        boolean empty = main.isEmpty();
        s.drawText(TextClip.fit(s, empty ? placeholder : main, Math.max(0, mainRoom)), left, ty,
                enabled && !empty ? c.textPrimary() : c.textMuted(), false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my) || !enabled) return false;
        onClick.run();
        return true;
    }
}
