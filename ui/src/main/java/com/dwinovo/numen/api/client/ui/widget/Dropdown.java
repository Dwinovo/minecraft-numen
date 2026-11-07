package com.dwinovo.numen.api.client.ui.widget;

import com.dwinovo.numen.api.client.ui.Animation;
import com.dwinovo.numen.api.client.ui.IDrawSurface;
import com.dwinovo.numen.api.client.ui.NumenStyle;
import com.dwinovo.numen.api.client.ui.NumenTheme;
import com.dwinovo.numen.api.client.ui.TextClip;

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
    /** 折叠框悬停进度(0~1,HOVER_MS 走满)。 */
    private float hoverT;
    private long lastFrameMs = -1;
    private int popupScroll;   // 弹层滚动(行数)
    /**
     * 弹层出入的时长:从贴着字段的那一边长开、收回去。始终是实底,半透明会让底下字段的字透出来;
     * 长开从这个比例起,第一帧就看得见,不是空的一帧。
     */
    private static final long REVEAL_MS = 150;
    private static final float REVEAL_FROM = 0.2f;
    /** 弹层开了 / 收了的时刻;-1 = 还没画到那一帧(时间由渲染给,控件不读钟)。 */
    private long openedAtMs = -1, closedAtMs = -1;


    /** 底线(字段外观只有这一种,见 {@link FieldLine});下拉开着当聚焦,展开从点击处起。 */
    private final FieldLine line = new FieldLine();

    public Dropdown(List<String> items, int selected, IntConsumer onSelect) {
        this.items = items;
        this.selected = Math.max(0, Math.min(selected, items.size() - 1));
        this.onSelect = onSelect;
    }

    /** 弹层宽度覆盖(默认与收起态同宽);弹层右缘对齐控件右缘,窄选择器不至于挤爆版面。 */
    private int popupW;

    public Dropdown popupWidth(int width) {
        this.popupW = width;
        return this;
    }

    /** 选择行的标签;null = 框形态。见 {@link #row}。 */
    private String rowLabel;
    /** 选择行两端文字到行边的距离。 */
    private int rowInset;
    /** 选择行的弹层宽:按最长的一项量,画弹层时定。 */
    private int rowPopupW;

    /**
     * 选择行形态(Telegram 对话框里的那种):整行是一个钮,左边标签、右边当前值(强调色),悬停整行浮出浅底;
     * 点开的弹层挂在行下,右缘对着当前值。{@code inset} 是两端文字到行边的距离。置灰时值是淡色、点不开。
     */
    public Dropdown row(String label, int inset) {
        this.rowLabel = label;
        this.rowInset = inset;
        return this;
    }

    /** 没有可选的项时行上写这句警告色的话,点它做 {@link #onEmpty};null = 空着就是空着。 */
    private String emptyText;
    private Runnable onEmpty;

    /** 空列表的出路:行上直接说"没有——去哪儿新建",点一下就去(选择行形态)。 */
    public Dropdown whenEmpty(String text, Runnable go) {
        this.emptyText = text;
        this.onEmpty = go;
        return this;
    }

    private int popupWidth() {
        if (popupW > 0) return popupW;
        return rowLabel != null && rowPopupW > 0 ? rowPopupW : w;
    }

    private int popupX() {
        if (popupW > 0) return x + w - popupW;
        // 选择行:弹层里的字(内缩 5)右缘与行上的当前值对齐
        return rowLabel != null && rowPopupW > 0 ? Math.max(x, x + w - rowInset + 5 - rowPopupW) : x;
    }

    /** 每一项右边的灰色说明(与条目一一对应);弹层里右对齐,放不下就省略。收起态不显示。 */
    private List<String> details = List.of();

    public Dropdown details(List<String> details) {
        this.details = details;
        return this;
    }

    public void setItems(List<String> items, int selected) {
        this.items = items;
        this.selected = Math.max(0, Math.min(selected, items.size() - 1));
        this.popupScroll = 0;
    }

    public int selectedIndex() { return selected; }

    /** 程序性选中(不触发 onSelect)——用于控件间联动同步。 */
    public void select(int index) {
        this.selected = Math.max(0, Math.min(index, items.size() - 1));
    }

    public String selectedItem() {
        return items.isEmpty() ? "" : items.get(selected);
    }

    public boolean isOpen() { return open; }

    private int rowH(IDrawSurface s) { return s.lineHeight() + NumenStyle.ROW_TEXT_PAD; }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        boolean hovered = enabled && contains(mouseX, mouseY);
        long dt = lastFrameMs < 0 ? 1000 : nowMs - lastFrameMs;
        lastFrameMs = nowMs;
        hoverT = NumenStyle.hoverStep(hoverT, hovered || open, dt);
        if (rowLabel != null) {
            renderRow(s, c);
            return;
        }
        // 一行字 + 右侧箭头 + 底线(和输入框同一种外观,不画框);下拉开着底线亮 accent(=聚焦态)。
        // 悬停是"在底上叠一层"而非"换底色"——hover 是亮度感知的半透明叠加色(暗主题偏白/亮主题偏黑),
        // 拿它当实底填会整块发黑(真机教训),按进度收放透明度才对。
        if (hoverT > 0.01f) {
            int overlay = ((int) (((c.hover() >>> 24) & 0xFF) * hoverT) << 24) | (c.hover() & 0xFFFFFF);
            s.fillRect(x, y, w, h - 1, overlay);
        }
        line.draw(s, c, x, y, w, h, open, hovered, false, nowMs);
        s.drawText(TextClip.fit(s, selectedItem(), Math.max(0, w - NumenStyle.FIELD_PAD - 16)), x + NumenStyle.FIELD_PAD,
                y + (h - s.lineHeight()) / 2 + 1, enabled ? c.textPrimary() : c.textMuted(), false);
        s.drawText(open ? "▲" : "▼", x + w - 11, y + (h - s.lineHeight()) / 2 + 1, c.textSecondary(), false);
    }

    /** 选择行:整行浅底随悬停收放,左标签、右当前值。 */
    private void renderRow(IDrawSurface s, NumenTheme.Colors c) {
        if (enabled && hoverT > 0.01f) {
            int overlay = ((int) (((c.hover() >>> 24) & 0xFF) * hoverT) << 24) | (c.hover() & 0xFFFFFF);
            s.fillRect(x, y, w, h, overlay);
        }
        int ty = y + (h - s.lineHeight()) / 2 + 1;
        s.drawText(rowLabel, x + rowInset, ty, c.textPrimary(), false);
        int room = w - rowInset * 2 - s.textWidth(rowLabel) - 8;
        boolean none = items.isEmpty() && emptyText != null;
        String value = com.dwinovo.numen.api.client.ui.TextClip.fit(s, none ? emptyText : selectedItem(), Math.max(0, room));
        s.drawText(value, x + w - rowInset - s.textWidth(value), ty,
                none ? c.warning() : enabled ? c.accent() : c.textMuted(), false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my) || !enabled) return false;
        line.press(mx);
        if (items.isEmpty()) {
            if (onEmpty == null) return false;
            onEmpty.run();
            return true;
        }
        open = true;
        openedAtMs = -1;
        if (root != null) root.openOverlay(this);
        return true;
    }

    // ---- 浮层通道 ----

    /** 弹层摆在哪(往下还是往上、多高、多宽):字段位置、行高、条目数、可见区域一变就跟着变,所以每次现算。 */
    private PopupPlacement placement() {
        int want = Math.min(items.size(), NumenStyle.POPUP_MAX_ROWS) * rowHCached;
        return PopupPlacement.place(y, h, popupX(), popupWidth(), want, rowHCached,
                root == null ? UiRoot.Viewport.UNBOUNDED : root.viewport());
    }

    private int popupRows() {
        return placement().h() / rowHCached;
    }

    private int maxPopupScroll() { return Math.max(0, items.size() - popupRows()); }

    private boolean inPopup(double mx, double my) {
        PopupPlacement p = placement();
        return mx >= p.x() && mx < p.x() + p.w() && my >= p.y() && my < p.y() + p.h();
    }

    /** 行高在渲染时缓存,事件处理无画布也能判命中。 */
    private int rowHCached = 13;

    @Override
    public void renderOverlay(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (openedAtMs < 0) openedAtMs = nowMs;
        float t = Animation.easeOutCubic(Animation.progress(nowMs - openedAtMs, REVEAL_MS));
        drawPopup(s, c, mouseX, mouseY, REVEAL_FROM + (1f - REVEAL_FROM) * t);
    }

    /** 收起后浮层通道已经放手,收回去那几帧由通道的退场画;收回中不接指针,也就不画悬停。 */
    @Override
    public boolean renderLeaving(IDrawSurface s, NumenTheme.Colors c, long nowMs) {
        if (open) return false;
        if (closedAtMs < 0) closedAtMs = nowMs;
        float t = Animation.progress(nowMs - closedAtMs, REVEAL_MS);
        if (t >= 1f) return false;
        drawPopup(s, c, Integer.MIN_VALUE, Integer.MIN_VALUE, 1f - Animation.easeInCubic(t));
        return true;
    }

    /** @param reveal 露出多少(0~1):从贴着字段的那一边起算 */
    private void drawPopup(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, float reveal) {
        rowHCached = rowH(s);
        if (rowLabel != null) {
            int widest = 0;
            for (String it : items) widest = Math.max(widest, s.textWidth(it));
            rowPopupW = Math.min(w, widest + 10 + NumenStyle.SCROLLBAR_W);
        }
        popupScroll = Math.min(popupScroll, maxPopupScroll());
        int rows = popupRows();
        PopupPlacement p = placement();
        int py = p.y();
        int px = p.x();
        int pw = p.w();
        int shownH = Math.max(1, Math.round(p.h() * reveal));
        s.pushScissor(px, p.up() ? p.y() + p.h() - shownH : p.y(), pw, shownH);
        s.fillRect(px, py, pw, rows * rowHCached, c.panelBg());
        for (int r = 0; r < rows; r++) {
            int idx = popupScroll + r;
            if (idx >= items.size()) break;
            int ry = py + r * rowHCached;
            boolean hov = mouseX >= px && mouseX < px + pw && mouseY >= ry && mouseY < ry + rowHCached;
            if (idx == selected) s.fillRect(px, ry, pw, rowHCached, c.selected());
            else if (hov) s.fillRect(px, ry, pw, rowHCached, c.hover());
            s.drawText(items.get(idx), px + 5, ry + 2, c.textPrimary(), false);
            if (idx < details.size()) {
                int room = pw - NumenStyle.SCROLLBAR_W - 5 - (5 + s.textWidth(items.get(idx)) + 8);
                String d = com.dwinovo.numen.api.client.ui.TextClip.fit(s, details.get(idx), Math.max(0, room));
                s.drawText(d, px + pw - NumenStyle.SCROLLBAR_W - 5 - s.textWidth(d), ry + 2, c.textSecondary(), false);
            }
        }
        // 装不下全部条目时画滚动拇指——告诉玩家"下面还有,滚轮翻"。
        if (items.size() > rows) {
            int trackH = rows * rowHCached;
            int thumbH = Math.max(8, trackH * rows / items.size());
            int thumbY = py + (int) ((trackH - thumbH)
                    * (double) popupScroll / Math.max(1, maxPopupScroll()));
            s.fillRect(px + pw - NumenStyle.SCROLLBAR_W, thumbY, NumenStyle.SCROLLBAR_W, thumbH, c.divider());
        }
        s.popScissor();
    }

    @Override
    public boolean overlayClicked(double mx, double my, int button) {
        if (!inPopup(mx, my)) return false;
        int row = (int) ((my - placement().y()) / rowHCached);
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
        closedAtMs = -1;
    }
}
