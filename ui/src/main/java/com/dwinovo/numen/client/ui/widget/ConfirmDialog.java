package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextWrap;

import java.util.List;

/**
 * 模态确认卡(危险操作的最后一道闸):暗幕 + 居中小卡 + [取消][确认]。
 * 走 {@link UiRoot.Overlay} 通道——模态语义由 root 统一保证:卡外点击
 * 一律吞掉(不误触背景),ESC = 取消。确认按钮 danger 色,取消在左
 * (安全选项在逃跑方向)。
 */
public final class ConfirmDialog implements UiRoot.Overlay {

    private UiRoot root;
    private int dimX, dimY, dimW, dimH;
    private String message;
    /** 可选副文本(次级色小字):危险操作要说清后果,而不是只问一句"确定吗"。 */
    private String detail;
    private String cancelLabel;
    private String confirmLabel;
    private Runnable onConfirm;
    private List<String> lines;
    private List<String> detailLines;

    // 渲染时缓存的几何,事件无画布也能判命中
    private int cardX, cardY, cardW, cardH;
    private int cancelX, confirmX, buttonY;
    private static final int BTN_W = 52;
    private static final int BTN_H = 15;
    private static final int CARD_W = 190;

    /** 打开确认卡。{@code dim*} = 暗幕覆盖区(通常是宿主面板),卡居中其内。 */
    public void open(UiRoot root, int dimX, int dimY, int dimW, int dimH,
                     String message, String cancelLabel, String confirmLabel, Runnable onConfirm) {
        open(root, dimX, dimY, dimW, dimH, message, null, cancelLabel, confirmLabel, onConfirm);
    }

    /** 带副文本的确认卡:{@code detail} 用次级色小字画在主文案之下。 */
    public void open(UiRoot root, int dimX, int dimY, int dimW, int dimH,
                     String message, String detail,
                     String cancelLabel, String confirmLabel, Runnable onConfirm) {
        this.detail = detail == null || detail.isBlank() ? null : detail;
        this.detailLines = null;
        this.root = root;
        this.dimX = dimX;
        this.dimY = dimY;
        this.dimW = dimW;
        this.dimH = dimH;
        this.message = message == null ? "" : message;
        this.cancelLabel = cancelLabel;
        this.confirmLabel = confirmLabel;
        this.onConfirm = onConfirm;
        this.lines = null;
        root.openOverlay(this);
    }

    public boolean isOpen() {
        return root != null && root.hasOverlay();
    }

    @Override
    public void renderOverlay(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        if (lines == null) {
            lines = TextWrap.wrap(message, CARD_W - NumenStyle.PAD * 2, s::textWidth, 3);
        }
        if (detail != null && detailLines == null) {
            detailLines = TextWrap.wrap(detail, CARD_W - NumenStyle.PAD * 2, s::textWidth, 3);
        }
        s.fillRect(dimX, dimY, dimW, dimH, 0x99000000);

        int detailH = detailLines == null ? 0 : detailLines.size() * s.lineHeight() + 3;
        cardW = CARD_W;
        cardH = NumenStyle.PAD * 2 + lines.size() * s.lineHeight() + detailH + 8 + BTN_H;
        cardX = dimX + (dimW - cardW) / 2;
        cardY = dimY + (dimH - cardH) / 2;
        s.fillRoundRect(cardX, cardY, cardW, cardH, NumenStyle.RADIUS_PANEL, c.panelBg());

        int ty = cardY + NumenStyle.PAD;
        for (String line : lines) {
            s.drawText(line, cardX + NumenStyle.PAD, ty, c.textPrimary(), false);
            ty += s.lineHeight();
        }
        if (detailLines != null) {
            ty += 3;
            for (String line : detailLines) {
                s.drawText(line, cardX + NumenStyle.PAD, ty, c.textMuted(), false);
                ty += s.lineHeight();
            }
        }

        buttonY = cardY + cardH - BTN_H - NumenStyle.PAD / 2 - 2;
        confirmX = cardX + cardW - NumenStyle.PAD - BTN_W;
        cancelX = confirmX - 6 - BTN_W;

        drawButton(s, c, cancelX, cancelLabel, false,
                hover(mouseX, mouseY, cancelX));
        drawButton(s, c, confirmX, confirmLabel, true,
                hover(mouseX, mouseY, confirmX));
    }

    private void drawButton(IDrawSurface s, NumenTheme.Colors c, int bx, String label,
                            boolean danger, boolean hovered) {
        int bg = danger ? c.danger() : hovered ? c.hover() : c.sectionBg();
        if (danger && hovered) bg = NumenStyle.hoverBrighten(bg);
        s.fillRoundRect(bx, buttonY, BTN_W, BTN_H, NumenStyle.RADIUS_CONTROL, bg);
        int color = danger ? 0xFFFFFFFF : c.textPrimary();
        s.drawText(label, bx + (BTN_W - s.textWidth(label)) / 2,
                buttonY + (BTN_H - s.lineHeight()) / 2 + 1, color, false);
    }

    private boolean hover(double mx, double my, int bx) {
        return mx >= bx && mx < bx + BTN_W && my >= buttonY && my < buttonY + BTN_H;
    }

    @Override
    public boolean overlayClicked(double mx, double my, int button) {
        if (hover(mx, my, confirmX)) {
            Runnable action = onConfirm;
            closeAndDetach();
            if (action != null) action.run();
            return true;
        }
        if (hover(mx, my, cancelX)) {
            closeAndDetach();
            return true;
        }
        return true;   // 模态:卡外点击一律吞掉,不关闭不透传——危险操作不给误触留门
    }

    @Override
    public void closeOverlay() {
        // root 侧关闭(ESC)= 取消。
    }

    private void closeAndDetach() {
        if (root != null) root.closeOverlay(this);
    }
}
