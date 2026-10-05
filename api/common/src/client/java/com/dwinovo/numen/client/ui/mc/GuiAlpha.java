package com.dwinovo.numen.client.ui.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 整块画面的不透明度:此后到下一次 {@link #set} 之前画的一切(字、色块、贴图)一起按它淡,
 * 1 就是不淡。
 *
 * <p>1.21.2 起 {@code GuiGraphics} 没有 {@code setColor} 了,画下来的东西先攒进缓冲,等 {@link GuiGraphics#flush}
 * 才真画,画的那一刻用的是着色器颜色。所以换不透明度之前先 flush,把攒着的按旧的画掉;
 * 之后攒的东西等下一次 {@code set} 或帧末的 flush 时按这一次设的画。
 */
public final class GuiAlpha {

    private GuiAlpha() {}

    public static void set(GuiGraphics g, float alpha) {
        g.flush();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
    }
}
