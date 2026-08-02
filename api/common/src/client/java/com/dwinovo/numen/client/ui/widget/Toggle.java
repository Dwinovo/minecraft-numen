package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.Animation;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.function.Consumer;

/** 开关。滑块位置做趋近动画(时间驱动,不积帧)。 */
public final class Toggle extends Widget {

    private boolean on;
    private final Consumer<Boolean> onChange;
    private float knobPos;   // 0=左(关) 1=右(开)
    private long lastFrameMs = -1;

    public Toggle(boolean initial, Consumer<Boolean> onChange) {
        this.on = initial;
        this.onChange = onChange;
        this.knobPos = initial ? 1f : 0f;
    }

    public boolean isOn() { return on; }

    public void setOn(boolean value) { this.on = value; }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        // 帧间隔归一化的趋近(约 60fps 每帧 0.35 的速率)。
        float speed = lastFrameMs < 0 ? 1f : Math.min(1f, (nowMs - lastFrameMs) / 16f * 0.35f);
        lastFrameMs = nowMs;
        knobPos = Animation.lerpTo(knobPos, on ? 1f : 0f, speed, 0.01f);

        int bg = on ? c.accent() : c.inputBg();
        if (!enabled) bg = c.sectionBg();
        s.fillRoundRect(x, y, w, h, h / 2, bg);
        int knobSize = h - 4;
        int travel = w - knobSize - 4;
        int kx = x + 2 + Math.round(travel * knobPos);
        s.fillRoundRect(kx, y + 2, knobSize, knobSize, knobSize / 2,
                enabled ? 0xFFFFFFFF : c.textMuted());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my) || !enabled) return false;
        on = !on;
        onChange.accept(on);
        return true;
    }
}
