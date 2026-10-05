package com.dwinovo.numen.client.ui;

/**
 * NumenUI 的画布契约——组件库与 Minecraft 渲染 API 之间的全部接口。
 *
 * <h2>为什么存在</h2>
 * MC 的 GUI API 逐代变动(GuiGraphics 签名、scissor、渲染管线),而我们有
 * 十一个版本分支。组件库(布局/状态/主题/动画)只面向本接口编程、零 MC
 * import;折入一个版本分支 = 重写一个百行级的适配器实现,组件库与屏幕层
 * 原样拷贝。这是 platform.Services 的隔离哲学在 UI 上的同款。
 *
 * <h2>纪律</h2>
 * 方法保持最小集——每加一个方法,十一个分支各多一份移植成本。能用现有
 * 方法组合表达的,不进接口。
 */
public interface IDrawSurface {

    /** 方角填充。NumenUI 不画圆角(见 {@code docs/ui-design-rules.md}):形状只有矩形,层级靠颜色、描边与间距。 */
    void fillRect(int x, int y, int w, int h, int argb);

    void drawText(String text, int x, int y, int argb, boolean shadow);

    /** 文本像素宽(排版判据;实现应保证与 drawText 同一字体度量)。 */
    int textWidth(String text);

    /** 单行行高(含行距)。 */
    int lineHeight();

    /** 裁剪区入栈(嵌套裁剪取交集由实现或底层 API 保证)。 */
    void pushScissor(int x, int y, int w, int h);

    void popScissor();
}
