package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.Animation;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextWrap;

import java.util.List;

/**
 * 页面级 Alert:在给定区域内**左右居中、垂直偏上**悬浮的结果胶囊——
 * 操作结果(检测中/成功/失败)的家。与字段错误分工明确:校验错误内联在
 * 字段上,页面级结果浮在页面上部。宽度贴内容(胶囊,不是横幅),入场自
 * 上方落下+渐显;可选自动消失(成功类知道了就行),错误默认驻留到被
 * 下次操作替换。
 */
public final class InlineAlert extends Widget {

    public enum Severity { INFO, SUCCESS, WARNING, ERROR }

    /** 纯展示,永不参与命中——胶囊的名义边界常盖在表单行上,吞掉命中会把
     *  底下的输入框点死(UiRoot 命中第一个含点控件就停)。 */
    @Override
    public boolean contains(double mx, double my) {
        return false;
    }

    private static final long ENTER_MS = 150;
    private static final long EXIT_MS = 200;

    private Severity severity;
    private String message;
    private List<String> lines;
    private int pillW;
    private long shownAtMs = -1;
    /** ≤0 = 驻留;>0 = 显示这么久后自动淡出。 */
    private long autoDismissMs;

    /** 驻留展示(错误/检测中):被下次 show/clear 替换才消失。 */
    public void show(Severity severity, String message) {
        show(severity, message, 0);
    }

    public void show(Severity severity, String message, long autoDismissMs) {
        this.severity = severity;
        this.message = message == null ? "" : message;
        this.autoDismissMs = autoDismissMs;
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
            lines = TextWrap.wrap(message, w - NumenStyle.PAD * 4, s::textWidth, 2);
            int textW = 0;
            for (String line : lines) textW = Math.max(textW, s.textWidth(line));
            pillW = textW + NumenStyle.PAD * 2;
            shownAtMs = nowMs;
        }
        long elapsed = nowMs - shownAtMs;

        float alphaF;
        int drop;
        if (autoDismissMs > 0 && elapsed > autoDismissMs) {
            float pOut = Animation.progress(elapsed - autoDismissMs, EXIT_MS);
            if (pOut >= 1f) {
                clear();
                return;
            }
            alphaF = 1f - Animation.easeInCubic(pOut);
            drop = 0;
        } else {
            float pIn = Animation.progress(elapsed, ENTER_MS);
            alphaF = Animation.easeOutCubic(pIn);
            drop = -Math.round((1 - Animation.easeOutCubic(pIn)) * 4);   // 自上方落下
        }

        int border = switch (severity) {
            case INFO -> c.accent();
            case SUCCESS -> c.success();
            case WARNING -> c.warning();
            case ERROR -> c.danger();
        };
        int bg = switch (severity) {
            case INFO -> c.sectionBg();
            case SUCCESS -> c.toastInfoBg();
            case WARNING -> c.toastWarnBg();
            case ERROR -> c.toastErrorBg();
        };
        int contentH = Math.max(1, lines.size()) * s.lineHeight() + NumenStyle.PAD;
        int px = x + (w - pillW) / 2;      // 区域内左右居中
        int py = y + drop;                 // 垂直偏上:宿主给的 y 即上部锚点
        s.fillRoundRect(px, py, pillW, contentH, NumenStyle.RADIUS_CONTROL, alpha(border, alphaF));
        s.fillRoundRect(px + 1, py + 1, pillW - 2, contentH - 2,
                NumenStyle.RADIUS_CONTROL - 1, alpha(bg, alphaF));
        if (alphaF > 0.05f) {
            int ty = py + NumenStyle.PAD / 2 + 1;
            for (String line : lines) {
                s.drawText(line, px + NumenStyle.PAD, ty, alpha(c.toastText(), alphaF), false);
                ty += s.lineHeight();
            }
        }
    }

    private static int alpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (al << 24) | (argb & 0xFFFFFF);
    }
}
