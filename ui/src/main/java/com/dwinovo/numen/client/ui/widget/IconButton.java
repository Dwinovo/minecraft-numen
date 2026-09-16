package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenIcons;
import com.dwinovo.numen.client.ui.NumenTheme;

/**
 * 只有图标的按钮:悬停时底色亮起、图标转亮,并把那句话交给 {@link UiRoot} 当 tooltip。
 *
 * <p>什么时候用它、什么时候写字:<b>常做又没有后果的动作</b>(复制)用图标,一眼认得、
 * 不占地方;<b>少做或者有后果的动作</b>(重新生成令牌、保存并重启)写字——图标藏得住形状,
 * 藏不住代价。
 */
public final class IconButton extends Widget {

    private final boolean[][] icon;
    private final String tooltip;
    private final Runnable onClick;

    public IconButton(boolean[][] icon, String tooltip, Runnable onClick) {
        this.icon = icon;
        this.tooltip = tooltip;
        this.onClick = onClick;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hot = enabled && contains(mouseX, mouseY);
        if (hot) {
            s.fillRect(x, y, w, h, c.hover());
            if (root != null) {
                root.requestTooltip(tooltip);
            }
        }
        int n = NumenIcons.size(icon);
        NumenIcons.draw(s, icon, x + (w - n) / 2, y + (h - n) / 2,
                !enabled ? c.textMuted() : hot ? c.textPrimary() : c.textSecondary());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        onClick.run();
        return true;
    }
}
