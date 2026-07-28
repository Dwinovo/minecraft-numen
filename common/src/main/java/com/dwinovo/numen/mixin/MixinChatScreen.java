package com.dwinovo.numen.mixin;

import com.dwinovo.numen.client.chat.NumenChatRouter;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 聊天框直连同伴的两只手:{@code @名字 消息} 在发送前被路由给同伴(不进
 * 公屏),Tab 在名字段内循环补全同伴名。名字没命中一律放行原生行为。
 */
@Mixin(ChatScreen.class)
public abstract class MixinChatScreen {

    @Shadow protected EditBox input;

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true)
    private void numen$routeCompanionChat(String message, boolean addToRecentChat, CallbackInfo ci) {
        if (NumenChatRouter.route(message)) {
            if (addToRecentChat) {
                Minecraft.getInstance().gui.getChat().addRecentChat(message);
            }
            ci.cancel();
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void numen$tabCompleteCompanion(int keyCode, int scanCode, int modifiers,
                                            CallbackInfoReturnable<Boolean> cir) {
        if (keyCode == 258 && NumenChatRouter.tabComplete(input)) {
            cir.setReturnValue(true);
        }
    }
}
