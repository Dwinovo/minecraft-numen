package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;
import com.dwinovo.numen.client.ui.TextWrap;

import java.util.List;

/**
 * 模态确认卡(危险操作的最后一道闸):暗幕 + 居中小卡 + [取消][确认]。
 * 走 {@link UiRoot.Overlay} 通道——模态语义由 root 统一保证:卡外点击
 * 一律吞掉(不误触背景),ESC = 取消。确认按钮 danger 色,取消在左
 * (安全选项在逃跑方向)。外壳与出没是 {@link DialogBox}:关掉之后浮层通道已放手,
 * 淡出那几帧走 {@link #renderLeaving}。
 *
 * <p>版式照 Telegram 的 ConfirmBox:没有标题,一段话(后果用次级色另起一段),右下一排纯字钮;
 * 卡宽是固定的 {@link DialogBox#WIDTH},不随字数伸缩。
 */
public final class ConfirmDialog implements UiRoot.Overlay {

    private UiRoot root;
    private final DialogBox box = new DialogBox();
    private int dimX, dimY, dimW, dimH;
    private String message;
    /** 可选副文本(次级色小字):危险操作要说清后果,而不是只问一句"确定吗"。 */
    private String detail;
    /** 右下的两颗纯字钮:取消是强调色,确定是红字(删这类回不去的)。几何在渲染时定,事件无画布也能判命中。 */
    private Button cancel;
    private Button confirm;
    private List<String> lines;
    private List<String> detailLines;

    // 渲染时缓存的几何
    private int cardX, cardY, cardW, cardH;
    /** 正文与后果那段之间的空。 */
    private static final int DETAIL_GAP = 3;

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
        this.cancel = new Button(cancelLabel, Button.Style.LINK, this::closeAndDetach);
        this.confirm = new Button(confirmLabel, Button.Style.LINK_DANGER, () -> {
            closeAndDetach();
            if (onConfirm != null) onConfirm.run();
        });
        this.lines = null;
        box.show();
        root.openOverlay(this);
    }

    public boolean isOpen() {
        return root != null && root.hasOverlay();
    }

    @Override
    public void renderOverlay(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        box.advance(nowMs);
        draw(s, c, mouseX, mouseY, nowMs);
    }

    @Override
    public boolean renderLeaving(IDrawSurface s, NumenTheme.Colors c, long nowMs) {
        if (!box.advance(nowMs)) return false;
        draw(s, c, Integer.MIN_VALUE, Integer.MIN_VALUE, nowMs);   // 淡出中不接指针,也就不画悬停
        return true;
    }

    private void draw(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs) {
        cardW = Math.min(DialogBox.WIDTH, dimW);
        int textW = cardW - DialogBox.PAD_X * 2;
        if (lines == null) {
            lines = TextWrap.wrap(message, textW, s::textWidth, 3);
        }
        if (detail != null && detailLines == null) {
            detailLines = TextWrap.wrap(detail, textW, s::textWidth, 3);
        }
        int detailH = detailLines == null ? 0 : DETAIL_GAP + detailLines.size() * s.lineHeight();
        cardH = DialogBox.TEXT_TOP + lines.size() * s.lineHeight() + detailH + DialogBox.TEXT_BOTTOM
                + DialogBox.FOOTER_H;
        cardX = dimX + (dimW - cardW) / 2;
        cardY = dimY + (dimH - cardH) / 2;
        DialogBox.placeButtons(s::textWidth, cardX + cardW - DialogBox.BUTTON_RIGHT,
                DialogBox.buttonTop(cardY, cardH), confirm, cancel);
        box.paint(s, c, dimX, dimY, dimW, dimH, cardX, cardY, cardW, cardH);
        float a = box.card();
        // 淡到几乎看不见就不画字:MC 把不透明度低于 4/255 的字当成不透明来画
        if (a < 0.02f) return;
        NumenTheme.Colors fc = DialogBox.fade(c, a);

        int tx = cardX + DialogBox.PAD_X;
        int ty = cardY + DialogBox.TEXT_TOP;
        for (String line : lines) {
            s.drawText(line, tx, ty, fc.textPrimary(), false);
            ty += s.lineHeight();
        }
        if (detailLines != null) {
            ty += DETAIL_GAP;
            for (String line : detailLines) {
                s.drawText(line, tx, ty, fc.textSecondary(), false);
                ty += s.lineHeight();
            }
        }
        cancel.render(s, fc, mouseX, mouseY, nowMs);
        confirm.render(s, fc, mouseX, mouseY, nowMs);
    }

    @Override
    public boolean overlayClicked(double mx, double my, int button) {
        if (!confirm.mouseClicked(mx, my, button)) cancel.mouseClicked(mx, my, button);
        return true;   // 模态:卡上的钮照常按,卡外点击一律吞掉,不关闭不透传——危险操作不给误触留门
    }

    @Override
    public void closeOverlay() {
        // root 侧关闭(ESC)= 取消;卡转进淡出。
        box.hide();
    }

    private void closeAndDetach() {
        box.hide();
        if (root != null) root.closeOverlay(this);
    }
}
