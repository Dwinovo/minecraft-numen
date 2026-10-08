package com.dwinovo.numen.api.gametest;

import net.minecraft.network.Connection;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * NeoForge 的模拟连接配法。发往玩家的自定义载荷在 {@code ServerCommonPacketListenerImpl.send} 里先过
 * {@code NetworkRegistry.checkPacket}:连接的频道表里没有它就抛 {@code UnsupportedOperationException}。
 * {@code configureMockConnection} 是 NeoForge 给 GameTest 的现成做法,按"两边都是 NeoForge、登记过的频道全都协商好了"写上,
 * 和一个装齐了模组的真客户端协商出来的一样。
 */
public final class NeoForgeMockConnection implements MockConnection {

    @Override
    public void configure(Connection connection) {
        NetworkRegistry.configureMockConnection(connection);
    }
}
