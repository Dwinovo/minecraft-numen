package com.dwinovo.numen.client.ui;

import com.dwinovo.numen.client.screen.UiTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

/**
 * 模态确认卡:半透明暗幕盖住整个工作台(背景只渲染不交互——调用方 rebuild 时只建
 * 卡上的两颗按钮),中央一张圆角卡:标题(自动换行)+ 可选的红色警告行。按钮走
 * Screen 的 widget 通道、在暗幕之后渲染,由调用方按 {@link Box#buttonY()} 定位——
 * build 与 render 必须共用 {@link #box} 的同一份几何,卡和按钮才不会漂。
 */
public final class ConfirmModal {

    public static final int BTN_H = 18;
    public static final int BTN_W = 64;
    public static final int BTN_GAP = 8;
    private static final int CARD_W = 236;
    private static final int PAD = 12;
    private static final int LINE_H = 12;

    private ConfirmModal() {}

    /** Card geometry; {@code buttonY} = the row the caller's Cancel/Delete buttons sit on. */
    public record Box(int x, int y, int w, int h, int buttonY) {}

    public static Box box(Font font, int scrimX0, int scrimX1, int top, int panelH,
                          Component title, Component warn) {
        int innerW = CARD_W - PAD * 2;
        int lines = font.split(title, innerW).size()
                + (warn != null ? font.split(warn, innerW).size() : 0);
        int h = PAD + lines * LINE_H + 8 + BTN_H + PAD;
        int x = (scrimX0 + scrimX1 - CARD_W) / 2;
        int y = top + (panelH - h) / 2;
        return new Box(x, y, CARD_W, h, y + h - PAD - BTN_H);
    }

    /** Scrim + card + text. Buttons render later in the widget pass, landing on top. */
    public static void render(GuiGraphics g, Font font, int scrimX0, int scrimX1, int top, int panelH,
                              Component title, Component warn) {
        UiTheme t = UiTheme.current();
        g.fill(scrimX0, top, scrimX1, top + panelH, (t.border() & 0xFFFFFF) | 0x99000000);
        Box b = box(font, scrimX0, scrimX1, top, panelH, title, warn);
        RoundRect.card(g, b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), 6, t.aiFill(), t.aiBorder());
        int innerW = b.w() - PAD * 2;
        int ty = b.y() + PAD + 1;
        ty = drawWrapped(g, font, title, t.text(), b.x() + PAD, ty, innerW);
        if (warn != null) drawWrapped(g, font, warn, t.fail(), b.x() + PAD, ty, innerW);
    }

    /** 无阴影逐行画换行文本;色进 Style(withColor 不改字宽,与 box 的行数测量一致)。 */
    private static int drawWrapped(GuiGraphics g, Font font, Component c, int color,
                                   int x, int y, int innerW) {
        Component colored = c.copy().withStyle(s -> s.withColor(TextColor.fromRgb(color & 0xFFFFFF)));
        for (FormattedCharSequence seq : font.split(colored, innerW)) {
            g.drawString(font, seq, x, y, -1, false);
            y += LINE_H;
        }
        return y;
    }
}
