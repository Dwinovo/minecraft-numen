package com.dwinovo.numen.api.mixin.inject;

import com.dwinovo.numen.api.mixin.ClientLanguageImpl;

import net.minecraft.network.protocol.game.ServerboundClientInformationPacket;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 这一代原版收客户端设置包时不记语言:在收包处记下,经 {@link ClientLanguageImpl} 读。客户端还没报过时是 {@code en_us}。 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerLanguageMixin implements ClientLanguageImpl.Reported {

    @Unique
    private String numen$language = "en_us";

    @Inject(method = "updateOptions", at = @At("HEAD"))
    private void numen$rememberLanguage(ServerboundClientInformationPacket packet, CallbackInfo ci) {
        numen$language = packet.language();
    }

    @Override
    public String numen$language() {
        return numen$language;
    }
}
