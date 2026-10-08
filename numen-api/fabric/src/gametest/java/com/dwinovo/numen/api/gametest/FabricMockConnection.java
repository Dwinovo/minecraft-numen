package com.dwinovo.numen.api.gametest;

import net.minecraft.network.Connection;

/**
 * Fabric 的模拟连接配法:不需要配。Fabric 联网层没有"频道协商过没有"的发送检查:{@code ServerPlayNetworking.send} 只是把载荷包成
 * 下行包交给 {@code connection.send},fabric-networking-api-v1 的 mixin 里没有谁拦截发送(NeoForge 在
 * {@code ServerCommonPacketListenerImpl.send} 里按连接的频道表查,所以那边要 {@code configureMockConnection} 配一张)。
 * 模拟主人的连接 {@link OwnerLine} 自己收下行的模组载荷,什么也不用加。
 */
public final class FabricMockConnection implements MockConnection {

    @Override
    public void configure(Connection connection) {
    }
}
