package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.List;

/**
 * 取代输入行的选择面板——键盘驱动,一次只开一个。
 *
 * <h2>为什么是"取代"而不是"叠在上面"</h2>
 * 叠着的浮层要回答"输入框还在,回车归谁"这种问题;取代之后没有这个问题:面板在场时
 * 输入框不在,回车只有一个含义。退出即恢复,输入框清空。
 *
 * <p>比输入行高:它要一次显示好几行。宽度跟输入框一样,底边对齐——视线不用挪。
 *
 * <h2>它不知道行里是什么</h2>
 * 行是 {@link Row},{@code on} 只决定那个圆点画绿还是画红;回车干什么由
 * {@link Page#activate} 说了算。面板只管几何、选中、滚动窗和画。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class SelectPanel extends Widget {

    /** 一行。{@code on} 为 {@code null} = 这行没有开关态,不画圆点。 */
    public record Row(String label, String note, Boolean on) {

        public Row {
            label = label == null ? "" : label;
            note = note == null ? "" : note;
        }
    }

    /** 面板里装的东西。行怎么来、回车干什么,都归它。 */
    public interface Page {

        /** 标题行;{@code null} = 不画。 */
        String title();

        List<Row> rows();

        /** 回车落在第 {@code index} 行。返回 {@code true} = 内容变了,下一帧重取行。 */
        boolean activate(int index);

        /**
         * 行上面那条横幅占多高;{@code 0}(缺省)= 不画。
         *
         * <p>有些页要先给一眼看得出的东西——构成条、水位条——再列明细。数字要一个个比,
         * 一条条一眼就够。缺省不画,现有的页什么都不用改。
         */
        default int bannerHeight() {
            return 0;
        }

        /** 画那条横幅。{@link #bannerHeight} 为 0 时不会被调到。 */
        default void drawBanner(IDrawSurface s, NumenTheme.Colors c, int x, int y, int w) {
        }
    }

    private static final int ROW_H = 12;
    private static final int TITLE_H = 11;
    private static final int PAD = 4;
    /** 开关圆点。绿 = 开着,红 = 关掉了。 */
    private static final String DOT = "●";

    private final Page page;
    private List<Row> rows;
    private int selected;
    /** 滚动窗的第一行下标。选中项走出窗口时才动,不跟着每次移动重算。 */
    private int windowStart;

    public SelectPanel(Page page) {
        this.page = page;
        this.rows = page.rows();
    }

    /** 装得下这么多行时的高度——宿主拿它决定面板顶边在哪。 */
    public static int heightFor(int rowCount, boolean titled) {
        int shown = Math.min(Math.max(rowCount, 1), NumenStyle.POPUP_MAX_ROWS);
        return PAD * 2 + (titled ? TITLE_H : 0) + shown * ROW_H;
    }

    public int preferredHeight() {
        return heightFor(rows.size(), page.title() != null) + page.bannerHeight();
    }

    public int selectedIndex() {
        return selected;
    }

    /** 重新向 page 取行(内容变了之后)。选中项尽量留在原位。 */
    public void refresh() {
        rows = page.rows();
        selected = Math.max(0, Math.min(selected, rows.size() - 1));
    }

    @Override
    public boolean focusable() {
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        switch (keyCode) {
            case com.dwinovo.numen.client.ui.KeyCodes.UP -> {
                move(-1);
                return true;
            }
            case com.dwinovo.numen.client.ui.KeyCodes.DOWN -> {
                move(1);
                return true;
            }
            case com.dwinovo.numen.client.ui.KeyCodes.ENTER -> {
                if (!rows.isEmpty() && page.activate(selected)) {
                    refresh();
                }
                return true;
            }
            default -> {
                return false;   // Esc 归宿主:关面板不是面板自己的事
            }
        }
    }

    /** 上下移动并夹紧。不绕圈——到头就停,列表里绕圈会让人以为自己看漏了。 */
    private void move(int dir) {
        if (rows.isEmpty()) {
            return;
        }
        selected = Math.max(0, Math.min(selected + dir, rows.size() - 1));
        int shown = visibleRows();
        if (selected < windowStart) {
            windowStart = selected;
        } else if (selected >= windowStart + shown) {
            windowStart = selected - shown + 1;
        }
    }

    private int visibleRows() {
        return Math.min(rows.size(), NumenStyle.POPUP_MAX_ROWS);
    }

    @Override
    public void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        NumenStyle.fieldCard(s, x, y, w, h, c.panelBg(), c.accent());

        int iy = y + PAD;
        String title = page.title();
        if (title != null) {
            s.drawText(title, x + PAD, iy, c.textSecondary(), false);
            iy += TITLE_H;
        }

        int banner = page.bannerHeight();
        if (banner > 0) {
            page.drawBanner(s, c, x + PAD, iy, w - PAD * 2);
            iy += banner;
        }

        int shown = visibleRows();
        windowStart = Math.max(0, Math.min(windowStart, Math.max(0, rows.size() - shown)));
        for (int i = 0; i < shown; i++) {
            drawRow(s, c, rows.get(windowStart + i), windowStart + i == selected,
                    x + PAD, iy + i * ROW_H, w - PAD * 2);
        }
    }

    private void drawRow(IDrawSurface s, NumenTheme.Colors c, Row row, boolean picked,
                         int rx, int ry, int rw) {
        if (picked) {
            s.fillRoundRect(rx - 2, ry - 1, rw + 4, ROW_H, NumenStyle.RADIUS_SMALL, c.selected());
        }
        int textY = ry + (ROW_H - s.lineHeight()) / 2;
        int left = rx;
        if (row.on() != null) {
            s.drawText(DOT, left, textY, row.on() ? c.success() : c.danger(), false);
            left += s.textWidth(DOT) + 4;
        }
        // 关掉的技能整行压暗:一眼看出它现在不参与,而不是只有那个点变了色。
        boolean off = Boolean.FALSE.equals(row.on());
        s.drawText(row.label(), left, textY,
                off ? c.textMuted() : picked ? c.textPrimary() : c.textSecondary(), false);

        if (row.note().isEmpty()) {
            return;
        }
        int used = left + s.textWidth(row.label()) + 6;
        int room = rx + rw - used;
        if (room <= 0) {
            return;
        }
        String note = clip(s, row.note(), room);
        s.drawText(note, rx + rw - s.textWidth(note), textY, c.textMuted(), false);
    }

    private static String clip(IDrawSurface s, String text, int maxW) {
        if (s.textWidth(text) <= maxW) {
            return text;
        }
        String out = text;
        while (out.length() > 1 && s.textWidth(out + "…") > maxW) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "…";
    }
}
