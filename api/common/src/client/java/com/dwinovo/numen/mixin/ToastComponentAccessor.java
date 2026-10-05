package com.dwinovo.numen.mixin;

import net.minecraft.client.gui.components.toasts.ToastManager;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.BitSet;

/**
 * 原版右上角的 toast 占着哪几格:我们的通知排在它们下面,不叠在成就提示上。只读。
 */
@Mixin(ToastManager.class)
public interface ToastComponentAccessor {

    @Accessor("occupiedSlots")
    BitSet numen$occupiedSlots();
}
