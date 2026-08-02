package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.List;
import java.util.function.IntConsumer;

/**
 * 数据驱动的可滚动列表(站点列表/同伴列表的底盘)。只画可视行(scissor),
 * 滚动为像素级并夹紧;行内容经 {@link RowRenderer} 回调外置——列表管几何
 * 与选择,不知道行里画什么。
 */
public final class ListView<T> extends Widget {

    /** 行渲染回调:坐标已换算好,只管往里画。 */
    public interface RowRenderer<T> {
        void render(IDrawSurface s, NumenTheme.Colors c, T item, int index,
                    int rowX, int rowY, int rowW, int rowH,
                    boolean selected, boolean hovered);
    }

    private List<T> items;
    private final int rowHeight;
    private final RowRenderer<T> renderer;
    private final IntConsumer onSelect;
    private int selectedIndex = -1;
    private double scrollY;

    public ListView(List<T> items, int rowHeight, RowRenderer<T> renderer, IntConsumer onSelect) {
        this.items = items;
        this.rowHeight = rowHeight;
        this.renderer = renderer;
        this.onSelect = onSelect;
    }

    public void setItems(List<T> items) {
        this.items = items;
        scrollY = Math.min(scrollY, maxScroll());
        if (selectedIndex >= items.size()) selectedIndex = -1;
    }

    public int selectedIndex() { return selectedIndex; }

    public void select(int index) {
        selectedIndex = index >= 0 && index < items.size() ? index : -1;
    }

    public double scrollY() { return scrollY; }

    public double maxScroll() {
        return Math.max(0, (double) items.size() * rowHeight - h);
    }

    /** 可视行区间 [first, last](含端点);空列表返回 {0,-1}。 */
    public int[] visibleRange() {
        if (items.isEmpty() || h <= 0) return new int[]{0, -1};
        int first = (int) (scrollY / rowHeight);
        int last = Math.min(items.size() - 1, (int) ((scrollY + h - 1) / rowHeight));
        return new int[]{first, last};
    }

    /** 屏幕纵坐标 → 行下标,不在任何行上返回 -1。 */
    public int rowAt(double my) {
        if (my < y || my >= y + h) return -1;
        int idx = (int) ((my - y + scrollY) / rowHeight);
        return idx >= 0 && idx < items.size() ? idx : -1;
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        s.pushScissor(x, y, w, h);
        int[] range = visibleRange();
        int hoveredRow = contains(mouseX, mouseY) ? rowAt(mouseY) : -1;
        for (int i = range[0]; i <= range[1]; i++) {
            int rowY = y + i * rowHeight - (int) scrollY;
            renderer.render(s, c, items.get(i), i, x, rowY, w, rowHeight,
                    i == selectedIndex, i == hoveredRow);
        }
        s.popScissor();

        double max = maxScroll();
        if (max > 0) {   // 滚动条:轨道隐形,只画拇指
            int barH = Math.max(10, (int) ((double) h * h / (items.size() * rowHeight)));
            int barY = y + (int) ((h - barH) * (scrollY / max));
            s.fillRoundRect(x + w - NumenStyle.SCROLLBAR_W, barY, NumenStyle.SCROLLBAR_W, barH, NumenStyle.RADIUS_SMALL, c.divider());
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) return false;
        int row = rowAt(my);
        if (row >= 0) {
            selectedIndex = row;
            onSelect.accept(row);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (maxScroll() <= 0) return false;
        scrollY = Math.max(0, Math.min(maxScroll(), scrollY - delta * rowHeight));
        return true;
    }
}
