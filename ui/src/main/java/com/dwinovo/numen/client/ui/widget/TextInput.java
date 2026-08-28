package com.dwinovo.numen.client.ui.widget;

/**
 * 真正管文本编辑的那个东西——由<b>宿主</b>提供。
 *
 * <h2>为什么要把编辑权交出去</h2>
 * 文本输入不只是"把字符塞进字符串":光标、选区、剪贴板,尤其是<b>输入法</b>,
 * 都是操作系统那一层的事。自绘一个输入框等于把整个输入法生态挡在门外——
 * 输入法辅助模组(IMBlocker 一类)靠给<b>已知控件</b>的 charTyped 注入 mixin 来
 * 判断"现在该不该开输入法",认不出的控件就一直按"不在输入框里"处理,强制关着,
 * 表现是无论如何都只能输入英文。
 *
 * <p>所以分工是:{@link TextField} 只管<b>画</b>(边框、遮罩点、占位符、token 高亮、
 * 视窗滚动),编辑交给宿主的真控件。外观一个像素不变,输入法照常认得出来。
 *
 * <h2>本模块保持零 MC 依赖</h2>
 * 这个接口只说"文本、光标、焦点、摆在哪",一个 Minecraft 类都不提。实现住在宿主侧
 * (客户端的 {@code client.ui.mc}),和 {@code IDrawSurface}、剪贴板同一个路子。
 */
public interface TextInput {

    String text();

    void setText(String s);

    /** 光标在第几个字符前。画光标竖线与算视窗要用。 */
    int cursor();

    boolean focused();

    void setFocused(boolean f);

    /**
     * 把真控件摆到这个矩形上。{@link TextField} 每帧调一次。
     *
     * <p>不是为了让它显示——它永远不画——而是<b>输入法候选框要靠它定位</b>:
     * 候选窗跟着插入符走,插入符坐标来自这个矩形。摆错了字打得出来,候选框却飘在
     * 屏幕角落。
     */
    void moveTo(int x, int y, int w, int h);
}
