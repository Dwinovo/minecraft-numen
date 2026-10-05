package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.ui.mc.GuiAlpha;

import net.minecraft.client.gui.GuiGraphics;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 把 {@link GuiAlpha} 乘进 {@code GuiGraphics} 的三个提交口:填充、文字、贴图(精灵与整图都落在贴图那一个口上,
 * 玩家脸也是)。三处都是"带颜色的元素入队"的那一步,于是整块界面的淡入淡出不必改任何一处绘制调用。
 * 不淡的时候({@code alpha == 1})原样返回,无开销。
 */
@Mixin(GuiGraphics.class)
public abstract class MixinGuiGraphics {

    @ModifyVariable(method = "submitColoredRectangle", at = @At("HEAD"), argsOnly = true, ordinal = 4)
    private int numen$fillColor(int color) {
        return GuiAlpha.apply(color);
    }

    @ModifyVariable(method = "submitColoredRectangle", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Integer numen$fillColorTo(Integer color) {
        return color == null ? null : GuiAlpha.apply(color);
    }

    @ModifyVariable(method = "drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 2)
    private int numen$textColor(int color) {
        return GuiAlpha.apply(color);
    }

    @ModifyVariable(method = "submitBlit", at = @At("HEAD"), argsOnly = true, ordinal = 4)
    private int numen$blitColor(int color) {
        return GuiAlpha.apply(color);
    }
}
