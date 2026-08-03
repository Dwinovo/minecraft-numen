package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** 滑条。值域 [min,max],可选步进吸附;标签格式由调用方注入(温度/0.1 步进等)。 */
public final class Slider extends Widget {

    private final double min, max, step;
    private double value;
    private final DoubleConsumer onChange;
    private final DoubleFunction<String> labelFn;
    private boolean dragging;

    /** @param step ≤0 = 连续无步进 */
    public Slider(double min, double max, double step, double initial,
                  DoubleConsumer onChange, DoubleFunction<String> labelFn) {
        this.min = min;
        this.max = max;
        this.step = step;
        this.value = clampSnap(initial);
        this.onChange = onChange;
        this.labelFn = labelFn;
    }

    public double value() { return value; }

    public void setValue(double v) { value = clampSnap(v); }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        int trackY = y + h / 2 - 1;
        s.fillRoundRect(x, trackY, w, 2, 1, c.inputBorder());
        int fillW = (int) Math.round((value - min) / (max - min) * w);
        s.fillRoundRect(x, trackY, fillW, 2, 1, enabled ? c.accent() : c.textMuted());
        int knob = 6;
        s.fillRoundRect(x + fillW - knob / 2, y + h / 2 - knob / 2, knob, knob, knob / 2,
                enabled ? 0xFFFFFFFF : c.textMuted());
        String label = labelFn.apply(value);
        s.drawText(label, x + w + 6, y + (h - s.lineHeight()) / 2 + 1, c.textSecondary(), false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my) || !enabled) return false;
        dragging = true;
        updateFromMouse(mx);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, double dx, double dy) {
        if (!dragging) return false;
        updateFromMouse(mx);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!dragging) return false;
        dragging = false;
        return true;
    }

    private void updateFromMouse(double mx) {
        double ratio = (mx - x) / w;
        double next = clampSnap(min + ratio * (max - min));
        if (next != value) {
            value = next;
            onChange.accept(value);
        }
    }

    private double clampSnap(double v) {
        v = Math.max(min, Math.min(max, v));
        if (step > 0) v = min + Math.round((v - min) / step) * step;
        return Math.max(min, Math.min(max, v));
    }
}
