package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 多选卡:暗幕 + 居中小卡 + 一列可勾选的行 + [取消][确认]。与 {@link ConfirmDialog} 同族,
 * 走 {@link UiRoot.Overlay} 通道。
 *
 * <p>"勾选 + 一个确认按钮"是主流(Discord、微信)拉人的形态。这不是危险操作,所以和确认卡
 * 有两处相反:卡外点击 = 关掉(不吞),确认钮是强调色不是危险色。一个都没勾时确认钮置灰、
 * 点了不动——没东西可确认。
 *
 * <p>纯 JVM,不碰 Minecraft。
 */
public final class PickDialog implements UiRoot.Overlay {

    private UiRoot root;
    private int dimX, dimY, dimW, dimH;
    private String title;
    private List<String> items = List.of();
    private boolean[] picked = new boolean[0];
    private String cancelLabel;
    private String confirmLabel;
    private Consumer<List<Integer>> onConfirm;
    /** 滚动窗的第一行下标(行数超过 {@link #MAX_ROWS} 时滚轮翻)。 */
    private int windowStart;

    // 渲染时缓存的几何,事件无画布也能判命中
    private int cardX, cardY, cardW, cardH;
    private int rowsY, rowW;
    private int cancelX, confirmX, buttonY;
    private static final int BTN_W = 52;
    private static final int BTN_H = 15;
    private static final int CARD_W = 190;
    private static final int ROW_H = 13;
    private static final int BOX = 9;
    private static final int MAX_ROWS = 8;

    /** 打开多选卡。{@code dim*} = 暗幕覆盖区(通常是宿主面板),卡居中其内。 */
    public void open(UiRoot root, int dimX, int dimY, int dimW, int dimH,
                     String title, List<String> items,
                     String cancelLabel, String confirmLabel, Consumer<List<Integer>> onConfirm) {
        this.root = root;
        this.dimX = dimX;
        this.dimY = dimY;
        this.dimW = dimW;
        this.dimH = dimH;
        this.title = title == null ? "" : title;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.picked = new boolean[this.items.size()];
        this.cancelLabel = cancelLabel;
        this.confirmLabel = confirmLabel;
        this.onConfirm = onConfirm;
        this.windowStart = 0;
        root.openOverlay(this);
    }

    public boolean isOpen() {
        return root != null && root.hasOverlay();
    }

    /** 勾上的那些行的下标,按行序。 */
    public List<Integer> pickedIndices() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < picked.length; i++) {
            if (picked[i]) {
                out.add(i);
            }
        }
        return out;
    }

    private int shownRows() {
        return Math.min(items.size(), MAX_ROWS);
    }

    @Override
    public void renderOverlay(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        s.fillRect(dimX, dimY, dimW, dimH, 0x99000000);

        int shown = shownRows();
        cardW = CARD_W;
        cardH = NumenStyle.PAD * 2 + s.lineHeight() + 4 + shown * ROW_H + 8 + BTN_H;
        cardX = dimX + (dimW - cardW) / 2;
        cardY = dimY + (dimH - cardH) / 2;
        s.fillRect(cardX, cardY, cardW, cardH, c.panelBg());

        s.drawText(title, cardX + NumenStyle.PAD, cardY + NumenStyle.PAD, c.textPrimary(), false);
        rowsY = cardY + NumenStyle.PAD + s.lineHeight() + 4;
        rowW = cardW - NumenStyle.PAD * 2;
        windowStart = Math.max(0, Math.min(windowStart, items.size() - shown));
        for (int i = 0; i < shown; i++) {
            int idx = windowStart + i;
            int ry = rowsY + i * ROW_H;
            boolean hot = rowAt(mouseX, mouseY) == idx;
            if (hot) {
                s.fillRect(cardX + NumenStyle.PAD - 2, ry - 1, rowW + 4, ROW_H, c.hover());
            }
            int bx = cardX + NumenStyle.PAD;
            int by = ry + (ROW_H - BOX) / 2 - 1;
            NumenStyle.box(s, bx, by, BOX, BOX,
                    picked[idx] ? c.accent() : c.inputBg(),
                    picked[idx] ? c.accent() : c.inputBorder());
            s.drawText(items.get(idx), bx + BOX + 5, ry + (ROW_H - s.lineHeight()) / 2,
                    picked[idx] ? c.textPrimary() : c.textSecondary(), false);
        }

        buttonY = cardY + cardH - BTN_H - NumenStyle.PAD / 2 - 2;
        confirmX = cardX + cardW - NumenStyle.PAD - BTN_W;
        cancelX = confirmX - 6 - BTN_W;
        boolean any = !pickedIndices().isEmpty();
        drawButton(s, c, cancelX, cancelLabel, hover(mouseX, mouseY, cancelX) ? c.hover() : c.sectionBg(),
                c.textPrimary());
        int confirmBg = !any ? c.sectionBg()
                : hover(mouseX, mouseY, confirmX) ? NumenStyle.hoverBrighten(c.accent()) : c.accent();
        drawButton(s, c, confirmX, confirmLabel, confirmBg, any ? 0xFFFFFFFF : c.textMuted());
    }

    private void drawButton(IDrawSurface s, NumenTheme.Colors c, int bx, String label, int bg, int color) {
        s.fillRect(bx, buttonY, BTN_W, BTN_H, bg);
        s.drawText(label, bx + (BTN_W - s.textWidth(label)) / 2,
                buttonY + (BTN_H - s.lineHeight()) / 2 + 1, color, false);
    }

    private boolean hover(double mx, double my, int bx) {
        return mx >= bx && mx < bx + BTN_W && my >= buttonY && my < buttonY + BTN_H;
    }

    /** 鼠标下的行下标,不在行上则 -1。 */
    private int rowAt(double mx, double my) {
        if (mx < cardX + NumenStyle.PAD - 2 || mx >= cardX + NumenStyle.PAD + rowW + 2) {
            return -1;
        }
        for (int i = 0; i < shownRows(); i++) {
            int ry = rowsY + i * ROW_H;
            if (my >= ry - 1 && my < ry - 1 + ROW_H) {
                return windowStart + i;
            }
        }
        return -1;
    }

    private boolean inCard(double mx, double my) {
        return mx >= cardX && mx < cardX + cardW && my >= cardY && my < cardY + cardH;
    }

    @Override
    public boolean overlayClicked(double mx, double my, int button) {
        int row = rowAt(mx, my);
        if (row >= 0) {
            picked[row] = !picked[row];
            return true;
        }
        if (hover(mx, my, confirmX)) {
            List<Integer> chosen = pickedIndices();
            if (chosen.isEmpty()) {
                return true;   // 没东西可确认
            }
            Consumer<List<Integer>> action = onConfirm;
            closeAndDetach();
            if (action != null) action.accept(chosen);
            return true;
        }
        if (hover(mx, my, cancelX)) {
            closeAndDetach();
            return true;
        }
        // 卡内空白吞掉;卡外 = 关掉(root 收到 false 就关)——不是危险操作,不必锁死
        return inCard(mx, my);
    }

    @Override
    public boolean overlayScrolled(double mx, double my, double delta) {
        if (items.size() <= MAX_ROWS) {
            return false;
        }
        windowStart = Math.max(0, Math.min(windowStart - (int) Math.signum(delta), items.size() - MAX_ROWS));
        return true;
    }

    @Override
    public void closeOverlay() {
        // root 侧关闭(ESC / 卡外点击)= 取消。
    }

    private void closeAndDetach() {
        if (root != null) root.closeOverlay(this);
    }
}
