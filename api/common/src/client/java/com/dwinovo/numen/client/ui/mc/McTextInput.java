package com.dwinovo.numen.client.ui.mc;

import com.dwinovo.numen.client.ui.widget.TextInput;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.CommonComponents;

import java.util.function.Consumer;

/**
 * 用一个<b>真的 {@link EditBox}</b> 给 NumenUI 的文本框当引擎。
 *
 * <h2>为什么非得是真的 EditBox</h2>
 * 输入法辅助模组(IMBlocker 一类)靠给<b>已知控件</b>的 {@code charTyped} 注入 mixin
 * 来判断"此刻该不该开输入法"。它认得香草的 EditBox;认不出的控件就一直按
 * "不在输入框里"处理、把输入法强制关着——表现是无论如何都只能输入英文,而且玩家
 * 手动切也没用,下一帧又被关回去。自绘的输入框就撞在这上面。
 *
 * <p>顺带白拿一整套香草行为:Home/End、Shift 选区、Ctrl+方向键按词跳、双击选词、
 * Ctrl+X。自绘那版只有 Ctrl+A/C/V 三个键。
 *
 * <h2>它一个像素都不画</h2>
 * 挂载走 {@code Screen.addWidget}——只进 {@code children} 与 {@code narratables},
 * <b>不进 {@code renderables}</b>,所以永远不会被自动绘制,连覆写 render 都不必。
 * 画面仍旧是 NumenUI 那套(遮罩点、占位符、token 高亮、视窗滚动),外观一个像素不变。
 *
 * <p>但 {@code visible} 必须留 true:{@code AbstractWidget.mouseClicked} 会检查
 * {@code active && visible},设成 false 连事件一起停掉。不进 renderables,true 也画不出东西。
 */
public final class McTextInput implements TextInput {

    /**
     * 上限给足。{@code EditBox} 的默认 {@code maxLength} 是 <b>32</b>——不改的话
     * 任何一条 API 密钥都会被<b>静默截断</b>,比填不进去更难查。
     */
    private static final int MAX_LEN = 32768;

    private final EditBox box;

    public McTextInput(Font font, String initial, Consumer<String> onChange) {
        this.box = new EditBox(font, 0, 0, 1, 1, CommonComponents.EMPTY) {
            @Override
            public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
                // 双保险:即便哪天被误挂进 renderables 也不会画出第二个输入框
            }
        };
        box.setMaxLength(MAX_LEN);
        box.setBordered(false);
        box.setValue(initial == null ? "" : initial);
        box.setResponder(onChange);
    }

    /** 交给宿主屏幕去 {@code addWidget} / {@code setFocused} 的那个控件。 */
    public EditBox widget() {
        return box;
    }

    @Override
    public String text() {
        return box.getValue();
    }

    @Override
    public void setText(String s) {
        box.setValue(s == null ? "" : s);   // 香草的 setValue 自带"光标去末尾"
    }

    @Override
    public int cursor() {
        return box.getCursorPosition();
    }

    @Override
    public boolean focused() {
        return box.isFocused();
    }

    @Override
    public void setFocused(boolean f) {
        box.setFocused(f);
    }

    @Override
    public void moveTo(int x, int y, int w, int h) {
        box.setPosition(x, y);
        box.setWidth(Math.max(1, w));
    }
}
