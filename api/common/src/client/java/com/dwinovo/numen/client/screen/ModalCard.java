package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.client.ui.IDrawSurface;
import com.dwinovo.numen.client.ui.NumenTheme;

/**
 * 头部铅笔打开的那张居中卡。铅笔作用在左栏选中的那一格:就他俩时是改她
 * ({@link CompanionEditPanel}),落过盘的会话是改会话({@link ConversationEditPanel})。
 * 屏幕只认这一个面,暗幕、居中、Esc 收卡、事件转发都只写一份。
 */
interface ModalCard {

    /** 每次开卡:草稿从当下真相取一次基线。 */
    void reset();

    /** 卡的高度;屏幕据此居中。 */
    int height();

    void build(int x, int y, int w, int h, int dropBottom);

    void render(IDrawSurface s, NumenTheme.Colors c, int mouseX, int mouseY, long nowMs);

    /** 悬停提示(宿主画 tooltip);没有则 null。 */
    String tooltipAt(double mx, double my);

    boolean mouseClicked(double mx, double my, int button);

    boolean mouseScrolled(double mx, double my, double delta);

    boolean keyPressed(int keyCode, int modifiers);

    boolean charTyped(char ch);
}
