package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
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
    private static final int MAX_POPUP_ROWS = 8;

    public Dropdown(List<String> items, int selected, IntConsumer onSelect) {
        this.items = items;
        this.selected = Math.max(0, Math.min(selected, items.size() - 1));
        this.onSelect = onSelect;
    }

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

    private int rowH(IDrawSurface s) { return s.lineHeight() + 4; }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hovered = enabled && contains(mouseX, mouseY);
        s.fillRoundRect(x, y, w, h, 3, hovered || open ? c.hover() : c.inputBg());
        String text = selectedItem();
        s.drawText(text, x + 5, y + (h - s.lineHeight()) / 2 + 1,
                enabled ? c.textPrimary() : c.textMuted(), false);
        s.drawText(open ? "▲" : "▼", x + w - 11, y + (h - s.lineHeight()) / 2 + 1,
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

    private int popupRows() { return Math.min(items.size(), MAX_POPUP_ROWS); }

    private boolean inPopup(double mx, double my) {
        return mx >= x && mx < x + w && my >= y + h && my < y + h + popupRows() * rowHCached;
    }

    /** 行高在渲染时缓存,事件处理无画布也能判命中。 */
    private int rowHCached = 13;

    @Override
    public void renderOverlay(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        rowHCached = rowH(s);
        int rows = popupRows();
        int py = y + h;
        s.fillRoundRect(x, py, w, rows * rowHCached, 3, c.panelBg());
        for (int r = 0; r < rows; r++) {
            int idx = popupScroll + r;
            if (idx >= items.size()) break;
            int ry = py + r * rowHCached;
            boolean hov = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + rowHCached;
            if (idx == selected) s.fillRect(x, ry, w, rowHCached, c.selected());
            else if (hov) s.fillRect(x, ry, w, rowHCached, c.hover());
            s.drawText(items.get(idx), x + 5, ry + 2, c.textPrimary(), false);
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
        popupScroll = Math.max(0, Math.min(popupScroll - (int) Math.signum(delta),
                Math.max(0, items.size() - MAX_POPUP_ROWS)));
        return true;
    }

    @Override
    public void closeOverlay() {
        open = false;
    }
}
