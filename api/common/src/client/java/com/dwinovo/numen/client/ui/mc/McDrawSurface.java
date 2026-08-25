package com.dwinovo.numen.client.ui.mc;

import com.dwinovo.numen.client.ui.IDrawSurface;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * IDrawSurface 的 26.2 实现——本类是 NumenUI 在每个版本分支上唯一需要
 * 重写的文件。保持薄:只做坐标与 API 形态的转译,不藏任何布局/状态逻辑。
 */
public final class McDrawSurface implements IDrawSurface {

    private final GuiGraphicsExtractor g;
    private final Font font;

    /** 逃生口:屏幕层的行渲染回调偶需原生画布(皮肤脸预览等 MC 专属绘制)。 */
    public GuiGraphicsExtractor graphics() { return g; }

    public McDrawSurface(GuiGraphicsExtractor g, Font font) {
        this.g = g;
        this.font = font;
    }

    @Override
    public void fillRect(int x, int y, int w, int h, int argb) {
        g.fill(x, y, x + w, y + h, argb);
    }

    @Override
    public void fillRoundRect(int x, int y, int w, int h, int radius, int argb) {
        // 真圆角:SDF shader(RoundRect),注册失败时它自己降级方角——两级兜底。
        com.dwinovo.numen.client.ui.RoundRect.fill(g, x, y, x + w, y + h, radius, argb);
    }

    @Override
    public void drawText(String text, int x, int y, int argb, boolean shadow) {
        g.text(font, text, x, y, argb, shadow);
    }

    @Override
    public int textWidth(String text) {
        return font.width(text);
    }

    @Override
    public int lineHeight() {
        return font.lineHeight + 2;
    }

    @Override
    public void pushScissor(int x, int y, int w, int h) {
        g.enableScissor(x, y, x + w, y + h);
    }

    @Override
    public void popScissor() {
        g.disableScissor();
    }
}
