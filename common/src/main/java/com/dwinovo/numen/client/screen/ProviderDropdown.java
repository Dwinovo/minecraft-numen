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

    private final List<LlmProviders.Option> options;   // live snapshot at construction (rebuilt each settings build)
    private final boolean allowAddSite;
    private int x, y, w, h = 18;
    /** 展开列表不得越过的下边界(面板底);默认不限。越界时列表向上翻。 */
    private int dropBottom = Integer.MAX_VALUE;
    private boolean open;
    private int scrollOff;   // 首个可见行的下标(列表被截断时滚轮驱动)
    private String selectedId;

    public ProviderDropdown(String selectedId, boolean allowAddSite) {
        this.options = LlmProviders.all();
        this.allowAddSite = allowAddSite;
        this.selectedId = ADD_SITE.equals(selectedId) ? selectedId : LlmProviders.normalize(selectedId);
    }

    public void setBounds(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    /** 限定展开列表的下边界(通常 = 面板底):放不下就向上翻,列表不再戳出面板。 */
    public void setDropBottom(int bottom) { this.dropBottom = bottom; }

    // ---- 展开列表几何:优先向下;放不下且上方更宽敞则向上翻;两边都装不完时
    //      截断到装得下的行数,余下的靠滚轮。列表因此永不越出 [0, dropBottom]。 ----

    private boolean flipsUp() {
        int below = (dropBottom - (y + h - 2) - 4) / ROW;
        return below < rowCount() && (y - 6) / ROW > below;
    }

    /** 实际画出的行数(被截断时小于条目数)。 */
    private int rowsShown() {
        int cap = flipsUp() ? (y - 6) / ROW : (dropBottom - (y + h - 2) - 4) / ROW;
        return Math.min(rowCount(), Math.max(1, cap));
    }

    private int listTop() {
        return flipsUp() ? y - (rowsShown() * ROW + 4) + 2 : y + h - 2;
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
        // 收起框与字段同款圆角卡;展开时边框亮 CTA 提示"正处于选择中"。
        com.dwinovo.numen.client.ui.RoundRect.card(g, x, y, x + w, y + h, 5,
                th.aiFill(), open ? th.cta() : th.aiBorder());
        int ty = y + (h - 8) / 2;
        Nb.text(g, font, selectedLabel(), x + 6, ty, th.text());
        Nb.text(g, font, open ? "▴" : "▾", x + w - 12, ty, th.textDim());

        if (open) {
            int rows = rowsShown();
            scrollOff = Math.clamp(scrollOff, 0, rowCount() - rows);
            int oy = listTop();
            com.dwinovo.numen.client.ui.RoundRect.card(g, x, oy, x + w, oy + rows * ROW + 4, 5,
                    th.aiFill(), th.aiBorder());
            for (int i = 0; i < rows; i++) {
                int idx = scrollOff + i;
                int ry = oy + 2 + i * ROW;
                if (mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + ROW) {
                    com.dwinovo.numen.client.ui.RoundRect.fill(g, x + 2, ry, x + w - 2, ry + ROW,
                            4, th.chipFill());
                }
                boolean add = idx >= options.size();
                // 选中行最深、其余退为次级;"＋新增站点"行保持 run 蓝区分动作行。
                int color = idAt(idx).equals(selectedId) ? th.text() : (add ? th.run() : th.textDim());
                Nb.text(g, font, label(idx), x + 6, ry + (ROW - 8) / 2, color);
            }
            if (rows < rowCount()) {   // 截断态:右缘一根细滚动指示条
                int track = rows * ROW;
                int thumbH = Math.max(8, track * rows / rowCount());
                int thumbY = oy + 2 + (track - thumbH) * scrollOff / (rowCount() - rows);
                g.fill(x + w - 4, thumbY, x + w - 2, thumbY + thumbH, 0x66000000);
            }
        }
    }

    /** Returns true if consumed. The host checks {@link #selectedId()} (== {@link #ADD_SITE} → add flow). */
    public boolean mouseClicked(double mx, double my) {
        if (open) {
            // 行命中区与 render 用同一个 listTop()+2 起点(旧实现相差 2px,也不会向上翻)。
            int oy = listTop() + 2;
            int rows = rowsShown();
            for (int i = 0; i < rows; i++) {
                int ry = oy + i * ROW;
                if (mx >= x && mx < x + w && my >= ry && my < ry + ROW) {
                    selectedId = idAt(Math.clamp(scrollOff, 0, rowCount() - rows) + i);
                    open = false;
                    return true;
                }
            }
            open = false;
            return true;
        }
        if (mx >= x && mx < x + w && my >= y && my < y + h) { open = true; scrollOff = 0; return true; }
        return false;
    }

    /** 滚轮落在展开列表上时滚动被截断的行;未消费返回 false 让宿主继续路由。 */
    public boolean mouseScrolled(double mx, double my, double sy) {
        if (!open || sy == 0) return false;
        int rows = rowsShown();
        if (rows >= rowCount()) return false;
        int oy = listTop();
        if (mx < x || mx >= x + w || my < oy || my >= oy + rows * ROW + 4) return false;
        scrollOff = Math.clamp(scrollOff - (int) Math.signum(sy), 0, rowCount() - rows);
        return true;
    }
}
