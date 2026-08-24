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

        // 关闭态在浅色面板上必须自证存在:次级色描边环 + 半透明灰轨道
        // (纯 inputBg 轨道在奶油底上白上白,等于隐形——真机教训)。
        if (on && enabled) {
            s.fillRoundRect(x, y, w, h, h / 2, c.accent());
        } else {
            // 描边环 → 不透明浅底 → 半透明灰纹(直接叠环上会整块变深)。
            s.fillRoundRect(x, y, w, h, h / 2, enabled ? c.textMuted() : c.divider());
            s.fillRoundRect(x + 1, y + 1, w - 2, h - 2, (h - 2) / 2, c.inputBg());
            s.fillRoundRect(x + 1, y + 1, w - 2, h - 2, (h - 2) / 2,
                    (c.textMuted() & 0x00FFFFFF) | 0x40000000);
        }
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
