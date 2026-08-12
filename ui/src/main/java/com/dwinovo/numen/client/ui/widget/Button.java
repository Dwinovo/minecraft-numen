package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextClip;

/** 按钮。Style 选语义色:普通/强调(主操作)/危险(删除类)。 */
public final class Button extends Widget {

    /** GHOST = 幽灵钮(图标类):平时无底,悬停浮现浅底——主流模态 ✕ 的标准形态。 */
    public enum Style { NORMAL, ACCENT, DANGER, GHOST }

    /**
     * 图标绘制回调:组件库不认识贴图(那是每版本适配层的事),宿主注入一段
     * "在这个方框里画图标"的闭包即可。{@code argb} 是按状态算好的图标色调。
     */
    public interface IconDrawer {
        void draw(IDrawSurface s, int x, int y, int size, int argb);
    }

    private String label;
    private final Style style;
    private final Runnable onClick;
    private IconDrawer icon;
    private int iconSize = 10;
    private String tooltip;

    public Button(String label, Style style, Runnable onClick) {
        this.label = label == null ? "" : label;
        this.style = style;
        this.onClick = onClick;
    }

    public void setLabel(String label) { this.label = label == null ? "" : label; }

    /** 图标钮:图标居中替代文字(label 退为无障碍/tooltip 文案)。 */
    public Button icon(int size, IconDrawer drawer) {
        this.iconSize = size;
        this.icon = drawer;
        return this;
    }

    /** 悬停提示(宿主取走自行绘制——tooltip 的定位与样式是宿主的事)。 */
    public Button tooltip(String text) {
        this.tooltip = text;
        return this;
    }

    public String tooltip() { return tooltip; }

    /** 悬停进度(0~1,HOVER_MS 走满)——指针反馈短过渡,不瞬时硬切。 */
    private float hoverT;
    private long lastMs = -1;

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hovered = enabled && contains(mouseX, mouseY);
        long dt = lastMs < 0 ? 1000 : nowMs - lastMs;
        lastMs = nowMs;
        hoverT = NumenStyle.hoverStep(hoverT, hovered, dt);
        float t = enabled ? hoverT : 0f;

        if (style == Style.GHOST) {
            if (t > 0.01f) {   // 浮现的浅底:半透明 hover 色按进度收放透明度
                int bg = ((int) (((c.hover() >>> 24) & 0xFF) * t) << 24) | (c.hover() & 0xFFFFFF);
                s.fillRoundRect(x, y, w, h, NumenStyle.RADIUS_CONTROL, bg);
            }
            int ghostColor = !enabled ? c.textMuted()
                    : NumenStyle.mixColor(c.textSecondary(), c.textPrimary(), t);
            String shown = TextClip.ellipsize(s, label, Math.max(0, w - 4));
            s.drawText(shown, x + (w - s.textWidth(shown)) / 2,
                    y + (h - s.lineHeight()) / 2 + 1, ghostColor, false);
            return;
        }
        if (style == Style.NORMAL) {
            s.fillRoundRect(x, y, w, h, NumenStyle.RADIUS_CONTROL, c.sectionBg());
            if (t > 0.01f) {   // hover 是叠加色:铺在底上,透明度随进度
                int overlay = ((int) (((c.hover() >>> 24) & 0xFF) * t) << 24) | (c.hover() & 0xFFFFFF);
                s.fillRoundRect(x, y, w, h, NumenStyle.RADIUS_CONTROL, overlay);
            }
        } else {
            int base = style == Style.ACCENT ? c.accent() : c.danger();
            int bg = !enabled ? c.sectionBg()
                    : NumenStyle.mixColor(base, NumenStyle.hoverBrighten(base), t);
            s.fillRoundRect(x, y, w, h, NumenStyle.RADIUS_CONTROL, bg);
        }
        int textColor = !enabled ? c.textMuted()
                : style == Style.NORMAL ? c.textPrimary() : 0xFFFFFFFF;
        if (icon != null) {
            icon.draw(s, x + (w - iconSize) / 2, y + (h - iconSize) / 2, iconSize, textColor);
            return;
        }
        String shown = TextClip.ellipsize(s, label, Math.max(0, w - 4));
        int tx = x + (w - s.textWidth(shown)) / 2;
        int ty = y + (h - s.lineHeight()) / 2 + 1;
        s.drawText(shown, tx, ty, textColor, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my) || !enabled) return false;
        onClick.run();
        return true;
    }

}
