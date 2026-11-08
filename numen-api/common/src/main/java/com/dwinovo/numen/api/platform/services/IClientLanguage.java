package com.dwinovo.numen.api.platform.services;

import net.minecraft.server.level.ServerPlayer;

/**
 * 一位玩家的客户端报上来的语言。这一代的原版服务端收到客户端设置包({@code ServerboundClientInformationPacket})时不记语言,
 * 记在哪是平台的事(模组上用 Mixin 在收包处记下)。
 */
public interface IClientLanguage {

    /** 他客户端报上来的语言,如 {@code zh_cn};还没报过是 {@code en_us}。 */
    String of(ServerPlayer player);
}
