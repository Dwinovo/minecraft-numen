package com.dwinovo.numen.client.ui.widget;

import com.dwinovo.numen.client.ui.Animation;
import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenStyle;
import com.dwinovo.numen.client.ui.NumenTheme;

/**
 * 对话框的外壳:暗幕 + 方角卡,以及它的出现与消失。确认卡({@link ConfirmDialog})和面板里召唤、编辑、
 * 改名、邀请那几张卡都画这一个,暗幕与卡的样子、动效、卡宽、边距、按钮行只在这里定。
 *
 * <p>动效照 Telegram 的 box(lib_ui {@code layer_widget.cpp} 的 BackgroundWidget):暗幕和卡同一段
 * {@link #SHOW_MS},暗幕的不透明度走 easeOutCirc,卡的不透明度线性走;关的时候同一条路倒着走,走完才算没了。
 * Telegram 的 box 只淡不缩放,这里也不缩放。时间由调用方逐帧注入({@link #advance})。
 */
public final class DialogBox {

    /** Telegram 的 boxDuration。 */
    public static final int SHOW_MS = 200;
    /** 暗幕:Telegram 的 layerBg(#0000007F),日间夜间同一个值。 */
    public static final int SCRIM = 0x7F000000;

    // ---- 版式:Telegram 的 box(lib_ui layers.style)折成 GUI 像素 ----
    // 横向按字宽折 0.7:MC 字形比 Telegram 13px 的正文窄三成,折完一行装下的字数相当;
    // 纵向按行高折 0.5:MC 一行 11,Telegram 正文一行 22。
    /** boxWidth 320:确认卡这类只有一段话的。 */
    public static final int WIDTH = 224;
    /** boxWideWidth 364:编辑联系人、建群这类带字段的。 */
    public static final int WIDE_WIDTH = 256;
    /** boxPadding / boxRowPadding 左右 24:卡里文字、字段到卡边。 */
    public static final int PAD_X = 17;
    /** 标题一行高(boxTitleHeight 48),标题字的顶边(boxTitlePosition.y 13)。 */
    public static final int TITLE_H = 24;
    public static final int TITLE_TOP = 7;
    /** 没有标题的卡(确认卡):一段话上下的留白(boxPadding 上 14、下 8)。 */
    public static final int TEXT_TOP = 7;
    public static final int TEXT_BOTTOM = 4;
    /** 按钮行(buttonPadding 6/10/10/10、buttonHeight 34):纯字钮靠右下,钮宽 = 字宽 + 30。 */
    public static final int BUTTON_H = 17;
    public static final int BUTTON_GAP = 4;
    public static final int BUTTON_RIGHT = 7;
    public static final int BUTTON_BOTTOM = 5;
    public static final int FOOTER_H = 5 + BUTTON_H + BUTTON_BOTTOM;

    /** 纯字钮的宽:字宽 + 两侧留白。 */
    public static int buttonW(int textW) {
        return textW + 21;
    }

    /** 按钮行的顶边:贴卡底。 */
    public static int buttonTop(int cardY, int cardH) {
        return cardY + cardH - BUTTON_BOTTOM - BUTTON_H;
    }

    private boolean shown;
    /** 线性进度 0(没了)~1(全在);两个方向都以同一速度走。 */
    private float t;
    /** 这一段从哪个进度起步;中途反向时从当下的进度接着走,不跳。 */
    private float from;
    /** 这一段的起点时刻;-1 = 下一帧才定(开关的那一刻手里没有时间)。 */
    private long since = -1;

    /** 开:从当下的样子往全在走。 */
    public void show() {
        retarget(true);
    }

    /** 关:从当下的样子往没了走;走完之前 {@link #advance} 一直返回 true。 */
    public void hide() {
        retarget(false);
    }

    private void retarget(boolean target) {
        if (shown == target) return;
        shown = target;
        from = t;
        since = -1;
    }

    /** 开着(关的那一下之后就是 false,哪怕还在淡出)——只有开着的卡接事件。 */
    public boolean shown() {
        return shown;
    }

    /** 推进到 {@code nowMs}。返回 false = 已经关完、淡没了,宿主可以拆了。每帧画之前调一次。 */
    public boolean advance(long nowMs) {
        if (since < 0) since = nowMs;
        float step = Animation.progress(nowMs - since, SHOW_MS);
        t = shown ? Math.min(1f, from + step) : Math.max(0f, from - step);
        return shown || t > 0f;
    }

    /** 暗幕此刻的不透明度(乘在 {@link #SCRIM} 上)。 */
    public float scrim() {
        return shown ? Animation.easeOutCirc(t) : 1f - Animation.easeOutCirc(1f - t);
    }

    /** 卡(含卡里的一切)此刻的不透明度。 */
    public float card() {
        return t;
    }

    /** 画暗幕和卡的外框;卡里的东西由调用方接着画,不透明度取 {@link #card()}。 */
    public void paint(IDrawSurface s, NumenTheme.Colors c,
                      int dimX, int dimY, int dimW, int dimH,
                      int cardX, int cardY, int cardW, int cardH) {
        s.fillRect(dimX, dimY, dimW, dimH, fade(SCRIM, scrim()));
        float a = card();
        NumenStyle.box(s, cardX, cardY, cardW, cardH, fade(c.inputBg(), a), fade(c.inputBorder(), a));
    }

    /** 把 {@code a} 乘进颜色的不透明度。 */
    public static int fade(int argb, float a) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a)));
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
