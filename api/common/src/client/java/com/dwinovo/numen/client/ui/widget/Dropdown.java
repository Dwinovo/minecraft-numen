package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * 下拉选择。收起时是一个显示当前项的按钮;点开后弹层经 {@link UiRoot.Overlay}
 * 走浮层通道——绘制在所有控件之后、事件在所有控件之前,层级正确性由 root
 * 统一保证而不是每个下拉自己比 z。
 */
public final class Dropdown extends Widget implements UiRoot.Overlay {

    private List<String> items;
    private int selected;
    private final IntConsumer onSelect;
    private boolean open;
    private int popupScroll;   // 弹层滚动(行数)
    

    /** 紧凑模式:收起态只画箭头不画值(窄"选择器"用,值另有输入框承载)。 */
    private boolean compact;

    public Dropdown(List<String> items, int selected, IntConsumer onSelect) {
        this.items = items;
        this.selected = Math.max(0, Math.min(selected, items.size() - 1));
        this.onSelect = onSelect;
    }

    public Dropdown compact() {
        this.compact = true;
        return this;
    }

    /** 弹层宽度覆盖(默认与收起态同宽);弹层右缘对齐控件右缘,窄选择器不至于挤爆版面。 */
    private int popupW;

    public Dropdown popupWidth(int width) {
        this.popupW = width;
        return this;
    }

    private int popupWidth() { return popupW > 0 ? popupW : w; }

    private int popupX() { return popupW > 0 ? x + w - popupW : x; }

    public void setItems(List<String> items, int selected) {
        this.items = items;
        this.selected = Math.max(0, Math.min(selected, items.size() - 1));
        this.popupScroll = 0;
    }

    public int selectedIndex() { return selected; }

    public String selectedItem() {
        return items.isEmpty() ? "" : items.get(selected);
    }

    public boolean isOpen() { return open; }

    private int rowH(IDrawSurface s) { return s.lineHeight() + NumenStyle.ROW_TEXT_PAD; }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hovered = enabled && contains(mouseX, mouseY);
        s.fillRoundRect(x, y, w, h, NumenStyle.RADIUS_CONTROL, hovered || open ? c.hover() : c.inputBg());
        if (!compact) {
            s.drawText(selectedItem(), x + 5, y + (h - s.lineHeight()) / 2 + 1,
                    enabled ? c.textPrimary() : c.textMuted(), false);
        }
        int arrowX = compact ? x + (w - s.textWidth("▼")) / 2 : x + w - 11;
        s.drawText(open ? "▲" : "▼", arrowX, y + (h - s.lineHeight()) / 2 + 1,
                c.textMuted(), false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my) || !enabled || items.isEmpty()) return false;
        open = true;
        if (root != null) root.openOverlay(this);
        return true;
    }

    // ---- 浮层通道 ----

    /** 弹层行数:条目数、行数上限、屏幕底缘剩余空间三者取最小(至少 3 行保可用)。 */
    private int popupRows() {
        int byViewport = NumenStyle.POPUP_MAX_ROWS;
        if (root != null && root.viewportHeight() != Integer.MAX_VALUE) {
            byViewport = Math.max(3, (root.viewportHeight() - (y + h) - 4) / Math.max(1, rowHCached));
        }
        return Math.min(items.size(), Math.min(NumenStyle.POPUP_MAX_ROWS, byViewport));
    }

    private int maxPopupScroll() { return Math.max(0, items.size() - popupRows()); }

    private boolean inPopup(double mx, double my) {
        return mx >= popupX() && mx < popupX() + popupWidth()
                && my >= y + h && my < y + h + popupRows() * rowHCached;
    }

    /** 行高在渲染时缓存,事件处理无画布也能判命中。 */
    private int rowHCached = 13;

    @Override
    public void renderOverlay(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        rowHCached = rowH(s);
        popupScroll = Math.min(popupScroll, maxPopupScroll());
        int rows = popupRows();
        int py = y + h;
        int px = popupX();
        int pw = popupWidth();
        s.fillRoundRect(px, py, pw, rows * rowHCached, NumenStyle.RADIUS_CONTROL, c.panelBg());
        for (int r = 0; r < rows; r++) {
            int idx = popupScroll + r;
            if (idx >= items.size()) break;
            int ry = py + r * rowHCached;
            boolean hov = mouseX >= px && mouseX < px + pw && mouseY >= ry && mouseY < ry + rowHCached;
            if (idx == selected) s.fillRect(px, ry, pw, rowHCached, c.selected());
            else if (hov) s.fillRect(px, ry, pw, rowHCached, c.hover());
            s.drawText(items.get(idx), px + 5, ry + 2, c.textPrimary(), false);
        }
        // 装不下全部条目时画滚动拇指——告诉玩家"下面还有,滚轮翻"。
        if (items.size() > rows) {
            int trackH = rows * rowHCached;
            int thumbH = Math.max(8, trackH * rows / items.size());
            int thumbY = py + (int) ((trackH - thumbH)
                    * (double) popupScroll / Math.max(1, maxPopupScroll()));
            s.fillRoundRect(px + pw - NumenStyle.SCROLLBAR_W, thumbY, NumenStyle.SCROLLBAR_W, thumbH, NumenStyle.RADIUS_SMALL, c.divider());
        }
    }

    @Override
    public boolean overlayClicked(double mx, double my, int button) {
        if (!inPopup(mx, my)) return false;
        int row = (int) ((my - y - h) / rowHCached);
        int idx = popupScroll + row;
        if (idx >= 0 && idx < items.size()) {
            selected = idx;
            onSelect.accept(idx);
        }
        closeOverlay();
        if (root != null) root.closeOverlay(this);
        return true;
    }

    @Override
    public boolean overlayScrolled(double mx, double my, double delta) {
        if (!inPopup(mx, my)) return false;
        popupScroll = Math.max(0, Math.min(popupScroll - (int) Math.signum(delta), maxPopupScroll()));
        return true;
    }

    @Override
    public void closeOverlay() {
        open = false;
    }
}
