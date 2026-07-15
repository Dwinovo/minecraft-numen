package com.dwinovo.numen.client.screen;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A screen-driven provider picker: a collapsed box showing the current provider, expanding to the live
 * provider list on click (+ an optional "add site" row). Not a self-contained widget — the host renders
 * it LAST (open list on top) and routes clicks FIRST. Shared by {@link SettingsScreen} and the
 * {@link NumenScreen} Settings tab.
 */
public final class ProviderDropdown {

    /** Sentinel selection id for the "+ add a site" row (only present when {@code allowAddSite}). */
    public static final String ADD_SITE = "__add_site__";

    private static final int ROW = 16;
    private static final net.minecraft.resources.ResourceLocation FRAME =
            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(com.dwinovo.numen.Constants.MOD_ID, "button");

    private final List<LlmProviders.Option> options;   // live snapshot at construction (rebuilt each settings build)
    private final boolean allowAddSite;
    private int x, y, w, h = 18;
    /** 展开列表不得越过的下边界(面板底);默认不限。越界时列表向上翻。 */
    private int dropBottom = Integer.MAX_VALUE;
    private boolean open;
    private String selectedId;

    public ProviderDropdown(String selectedId, boolean allowAddSite) {
        this.options = LlmProviders.all();
        this.allowAddSite = allowAddSite;
        this.selectedId = ADD_SITE.equals(selectedId) ? selectedId : LlmProviders.normalize(selectedId);
    }

    public void setBounds(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    /** 限定展开列表的下边界(通常 = 面板底):放不下就向上翻,列表不再戳出面板。 */
    public void setDropBottom(int bottom) { this.dropBottom = bottom; }

    /** 展开列表的外框顶 y:默认贴着收起框下沿;越过 {@link #dropBottom} 则翻到上方。 */
    private int listTop() {
        int below = y + h - 2;
        int lh = rowCount() * ROW + 4;
        if (below + lh <= dropBottom || y - lh + 2 < 0) return below;
        return y - lh + 2;
    }

    public String selectedId() { return selectedId; }
    public boolean isOpen() { return open; }
    public void close() { open = false; }

    private int rowCount() { return options.size() + (allowAddSite ? 1 : 0); }

    private String label(int i) {
        if (i < options.size()) return options.get(i).displayName();
        return I18n.get("numen.settings.add_site");
    }
    private String idAt(int i) { return i < options.size() ? options.get(i).id() : ADD_SITE; }

    private String selectedLabel() {
        if (ADD_SITE.equals(selectedId)) return I18n.get("numen.settings.add_site");
        for (LlmProviders.Option o : options) if (o.id().equals(selectedId)) return o.displayName();
        return options.isEmpty() ? selectedId : options.get(0).displayName();
    }

    public void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        UiTheme th = UiTheme.current();
        g.blitSprite(FRAME, x, y, w, h);
        int ty = y + (h - 8) / 2;
        Nb.text(g, font, selectedLabel(), x + 6, ty, th.text());
        Nb.text(g, font, open ? "▴" : "▾", x + w - 12, ty, th.textDim());

        if (open) {
            int oy = listTop();
            int n = rowCount();
            g.blitSprite(FRAME, x, oy, w, n * ROW + 4);
            for (int i = 0; i < n; i++) {
                int ry = oy + 2 + i * ROW;
                if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW) {
                    g.fill(x + 2, ry, x + w - 2, ry + ROW, 0x33000000);
                }
                boolean add = i >= options.size();
                int color = idAt(i).equals(selectedId) ? th.cta() : (add ? th.run() : th.text());
                Nb.text(g, font, label(i), x + 6, ry + (ROW - 8) / 2, color);
            }
        }
    }

    /** Returns true if consumed. The host checks {@link #selectedId()} (== {@link #ADD_SITE} → add flow). */
    public boolean mouseClicked(double mx, double my) {
        if (open) {
            // 行命中区与 render 用同一个 listTop()+2 起点(旧实现相差 2px,也不会向上翻)。
            int oy = listTop() + 2;
            for (int i = 0; i < rowCount(); i++) {
                int ry = oy + i * ROW;
                if (mx >= x && mx < x + w && my >= ry && my < ry + ROW) {
                    selectedId = idAt(i);
                    open = false;
                    return true;
                }
            }
            open = false;
            return true;
        }
        if (mx >= x && mx < x + w && my >= y && my < y + h) { open = true; return true; }
        return false;
    }
}
