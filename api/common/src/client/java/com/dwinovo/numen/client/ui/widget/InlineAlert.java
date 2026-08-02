package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.Animation;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextWrap;

import java.util.List;

/**
 * 页内驻留结果条——提示四层里的第②层:与当前操作相关的结果/状态,
 * 嵌在表单/面板内容流里,**不自动消失**(被下一次操作替换或显式清除)。
 * 检测结果住这里:用户正对着表单修 key,结果必须一直看得见。
 * 三色语义与 toast 同源(成功绿/警告黄/失败红,强色边+同系浅底)。
 */
public final class InlineAlert extends Widget {

    public enum Severity { SUCCESS, WARNING, ERROR }

    private Severity severity;
    private String message;
    private List<String> lines;      // 懒排版缓存(依赖画布度量)
    private long shownAtMs = -1;     // 首帧渐显起点

    public void show(Severity severity, String message) {
        this.severity = severity;
        this.message = message == null ? "" : message;
        this.lines = null;
        this.shownAtMs = -1;
    }

    public void clear() {
        this.message = null;
        this.lines = null;
    }

    public boolean isShowing() { return message != null; }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (message == null) return;
        if (lines == null) {
            lines = TextWrap.wrap(message, w - NumenStyle.PAD * 2, s::textWidth, 2);
            shownAtMs = nowMs;
        }
        // 入场 150ms 渐显小上浮——出现要被注意到,但不抢打断。
        float p = Animation.progress(nowMs - shownAtMs, 150);
        float alphaF = Animation.easeOutCubic(p);
        int rise = Math.round((1 - Animation.easeOutCubic(p)) * 4);

        int border = switch (severity) {
            case SUCCESS -> c.success();
            case WARNING -> c.warning();
            case ERROR -> c.danger();
        };
        int bg = switch (severity) {
            case SUCCESS -> c.toastInfoBg();
            case WARNING -> c.toastWarnBg();
            case ERROR -> c.toastErrorBg();
        };
        int contentH = Math.max(1, lines.size()) * s.lineHeight() + NumenStyle.PAD;
        int ay = y + (h - contentH) + rise;   // 底对齐给定区域(靠近按钮行)
        s.fillRoundRect(x, ay, w, contentH, NumenStyle.RADIUS_CONTROL, alpha(border, alphaF));
        s.fillRoundRect(x + 1, ay + 1, w - 2, contentH - 2,
                NumenStyle.RADIUS_CONTROL - 1, alpha(bg, alphaF));
        if (alphaF > 0.05f) {
            int ty = ay + NumenStyle.PAD / 2 + 1;
            for (String line : lines) {
                s.drawText(line, x + NumenStyle.PAD, ty, alpha(c.toastText(), alphaF), false);
                ty += s.lineHeight();
            }
        }
    }

    private static int alpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }
}
