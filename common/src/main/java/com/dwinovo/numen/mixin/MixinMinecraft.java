package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.agent.NumenRoster;
import com.dwinovo.numen.client.chat.CompanionChatScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 面对面搭话入口:准星指着自己的同伴按聊天键,打开的不是公屏聊天而是
 * 同伴的极简输入框。命令键("/"带默认文本)与没指着同伴的情况一律放行
 * 原生行为。选 {@code openChatScreen} 这个注入点是因为它在 tick 的按键
 * 处理里被调用——字符事件早已派发完,打开的屏不会吃进触发键本身。
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraft {

    @Inject(method = "openChatScreen", at = @At("HEAD"), cancellable = true)
    private void numen$faceToFaceChat(String defaultText, CallbackInfo ci) {
        if (defaultText != null && !defaultText.isEmpty()) {
            return;   // "/" 命令键带默认文本:不接管
        }
        AbstractClientPlayer body = CompanionChatScreen.crosshairCompanion();
        if (body == null) {
            return;
        }
        String name = NumenRoster.instance().name(body.getUUID());
        if (name == null || name.isBlank()) {
            name = body.getScoreboardName();
        }
        Minecraft mc = (Minecraft) (Object) this;
        mc.setScreen(new CompanionChatScreen(body.getUUID(), name));
        ci.cancel();
    }
}
