package com.dwinovo.numen.api.mixin;

import com.dwinovo.numen.api.platform.services.IClientLanguage;

import net.minecraft.server.level.ServerPlayer;

/** {@link IClientLanguage} 的模组实现:读 {@code ServerPlayerLanguageMixin} 在收客户端设置包时记下的那一份。 */
public final class ClientLanguageImpl implements IClientLanguage {

    /** 记着语言的玩家;由 {@code ServerPlayerLanguageMixin} 给 {@code ServerPlayer} 实现。 */
    public interface Reported {
        String numen$language();
    }

    @Override
    public String of(ServerPlayer player) {
        return ((Reported) player).numen$language();
    }
}
