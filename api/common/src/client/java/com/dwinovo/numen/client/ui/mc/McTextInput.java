package com.dwinovo.numen.client.ui.mc;

import com.dwinovo.numen.client.ui.widget.TextInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
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

    /**
     * 当前屏幕把真控件挂上去的那个口。由 {@code Screen} 开屏装上、关屏卸掉——
     * 所以它是<b>屏幕作用域</b>的,不是全局状态(Minecraft 同时只有一个 Screen)。
     *
     * <p>为什么不让每个面板自己传:面板拿不到宿主——它们的 host 既不是字段也不在
     * build 的参数里。硬要传就得改十个面板的签名,而那和"换个输入引擎"没有关系。
     */
    private static java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> mounter;

    /**
     * 屏幕交出键盘焦点的那个口。<b>由屏幕自己提供,不去查"当前 Screen"</b>——
     * 那个入口跨版本会变(26.x 上它挪到了 {@code Minecraft.gui} 底下),而这个类要活在
     * 十三条分支上。屏幕自己调 {@code this::setFocused} 一律成立。
     */
    private static java.util.function.Consumer<net.minecraft.client.gui.components.events.GuiEventListener> focuser;

    /** 开屏装上;传 null 卸掉。没装的时候输入框退回纯内存模式,打字照常。 */
    public static void mountVia(java.util.function.Consumer<net.minecraft.client.gui.components.AbstractWidget> m,
                                java.util.function.Consumer<net.minecraft.client.gui.components.events.GuiEventListener> f) {
        mounter = m;
        focuser = f;
    }

    /** 交给 {@code UiRoot.setInputFactory} 的那个工厂。 */
    public static java.util.function.BiFunction<String, Consumer<String>, TextInput> factory() {
        return (initial, onChange) -> {
            var m = mounter;
            if (m == null) return null;   // 不在受支持的屏幕里,保持纯内存
            var in = new McTextInput(net.minecraft.client.Minecraft.getInstance().font, initial, onChange);
            m.accept(in.widget());
            return in;
        };
    }

    private final EditBox box;
    private boolean numericOnly;
    private boolean reverting;
    private String lastGood = "";

    public McTextInput(Font font, String initial, Consumer<String> onChange) {
        // 不覆写 renderWidget:控件走 addWidget 注册,压根不进 renderables,永远不会被
        // 绘制。覆写只是"双保险",代价却是把 GuiGraphics 钉进类型签名——那个类在 26.2
        // 上已经不存在了(换成 GuiGraphicsExtractor),为一道用不上的保险换来跨版本分叉,
        // 不划算。
        this.box = new EditBox(font, 0, 0, 1, 1, CommonComponents.EMPTY);
        box.setMaxLength(MAX_LEN);
        box.setBordered(false);
        box.setValue(initial == null ? "" : initial);
        this.lastGood = box.getValue();
        box.setResponder(v -> {
            if (reverting) return;              // 自己退回去触发的那一次,不再往下传
            if (numericOnly && !v.chars().allMatch(Character::isDigit)) {
                reverting = true;
                box.setValue(lastGood);         // 退回上一个合规值
                reverting = false;
                return;
            }
            lastGood = v;
            onChange.accept(v);
        });
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

    /**
     * 焦点要<b>同时</b>给控件自己和屏幕。
     *
     * <p>只调 {@code box.setFocused(true)} 是不够的:那只设控件自己的标志位,而香草
     * 分发字符走的是 {@code Screen.getFocused()}——屏幕不认这个控件,
     * {@code super.charTyped} 就找不到接收者,字打不进去。
     */
    @Override
    public void setFocused(boolean f) {
        box.setFocused(f);
        // 只设控件自己的标志位是不够的:香草分发字符走 Screen.getFocused(),
        // 屏幕不认这个控件,super.charTyped 就找不到接收者,字打不进去。
        var fc = focuser;
        if (fc != null && f) fc.accept(box);
    }

    /**
     * 只收数字。<b>自己拦,不用 {@code EditBox.setFilter}</b>——那个口 26.x 上没有了,
     * 而这个类要活在十三条分支上。在回调里把不合规的改动退回去,效果一样。
     */
    @Override
    public void setNumericOnly(boolean on) {
        this.numericOnly = on;
    }

    @Override
    public void moveTo(int x, int y, int w, int h) {
        box.setPosition(x, y);
        box.setWidth(Math.max(1, w));
    }
}
