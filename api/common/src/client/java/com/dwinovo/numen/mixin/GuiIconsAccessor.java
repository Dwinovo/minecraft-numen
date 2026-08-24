package com.dwinovo.numen.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.resources.ResourceLocation;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 原版 HUD 图集的位置。这一代的心/鸡腿等 HUD 图标还全部拼在同一张
 * 图集里、没有各自的 sprite id,原版自己就是按 UV 从这张图上取格子画的。
 * 图集常量在原版里是私有的,这里只把它读出来——不复制一份贴图进 mod
 * 的资源,也不把路径抄成字面量。
 */
@Mixin(Gui.class)
public interface GuiIconsAccessor {

    @Accessor("GUI_ICONS_LOCATION")
    static ResourceLocation numen$iconsAtlas() {
        throw new AssertionError();
    }
}
