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

    /** 行内点击回调:行下标 + 点击点相对行左缘的横坐标(行尾图标热区判定用)。
     *  设了它就全权接管行点击(选中与否由回调自己决定),onSelect 不再触发。 */
    public interface RowClick {
        boolean click(int index, double xInRow);
    }

    private List<T> items;
    private final int rowHeight;
    private final RowRenderer<T> renderer;
    private final IntConsumer onSelect;
    private RowClick rowClick;
    private int selectedIndex = -1;
    private double scrollY;
    /** 悬停行与其淡入进度(行悬停底由列表统一画,渲染回调只管内容)。 */
    private int hoverRow = -1;
    private float hoverT;
    private long lastFrameMs = -1;

    public ListView(List<T> items, int rowHeight, RowRenderer<T> renderer, IntConsumer onSelect) {
        this.items = items;
        this.rowHeight = rowHeight;
        this.renderer = renderer;
        this.onSelect = onSelect;
    }

    public ListView<T> rowClick(RowClick rc) {
        this.rowClick = rc;
        return this;
    }

    /** 像素级滚动位移并夹紧(宿主重建后恢复滚动位,或视口外的滚轮转发)。 */
    public void scrollBy(double px) {
        scrollY = Math.max(0, Math.min(maxScroll(), scrollY + px));
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
        // 行悬停底统一在这画(短过渡淡入淡出),渲染回调只画内容。
        long dt = lastFrameMs < 0 ? 1000 : nowMs - lastFrameMs;
        lastFrameMs = nowMs;
        if (hoveredRow >= 0 && hoveredRow != hoverRow) {
            hoverRow = hoveredRow;
            hoverT = 0;
        }
        hoverT = NumenStyle.hoverStep(hoverT, hoveredRow == hoverRow && hoveredRow >= 0, dt);
        if (hoverT <= 0f && hoveredRow < 0) hoverRow = -1;
        for (int i = range[0]; i <= range[1]; i++) {
            int rowY = y + i * rowHeight - (int) scrollY;
            if (i == hoverRow && hoverT > 0.01f) {
                int overlay = ((int) (((c.hover() >>> 24) & 0xFF) * hoverT) << 24)
                        | (c.hover() & 0xFFFFFF);
                s.fillRoundRect(x, rowY, w, rowHeight, NumenStyle.RADIUS_SMALL, overlay);
            }
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
            if (rowClick != null) return rowClick.click(row, mx - x);
            selectedIndex = row;
            if (onSelect != null) onSelect.accept(row);
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
