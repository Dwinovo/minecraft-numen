package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.chat.CompanionWheelScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.KeyboardInput;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 同伴转盘不断步的唯一通路:原版每 tick 在这里采样移动输入(界面开着
 * 时按键被闸,采出来全是零),转盘开着就在采样之后按 GLFW 物理按键状态
 * 重建——两个加载器共用这一个点,NeoForge 的按键冲突上下文与 Fabric 的
 * 屏内按键失活都被绕过。选 tick 尾部而非包装 isDown:改写的是"这一
 * tick 的移动意图"这个单一出口,不碰按键系统本身。
 */
@Mixin(KeyboardInput.class)
public abstract class MixinKeyboardInput extends Input {

    @Inject(method = "tick", at = @At("TAIL"))
    private void numen$wheelMovement(boolean isSneaking, float sneakingSpeedMultiplier,
                                     CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // 只喂本地玩家的真输入:Freecam 之类会给玩家换假输入对象,别喂错人
        if (mc.player != null && mc.player.input == (Object) this
                && mc.screen instanceof CompanionWheelScreen) {
            CompanionWheelScreen.feedMovement(this, sneakingSpeedMultiplier);
        }
    }
}
