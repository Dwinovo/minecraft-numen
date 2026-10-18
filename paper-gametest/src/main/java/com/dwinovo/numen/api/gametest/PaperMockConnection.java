package com.dwinovo.numen.api.gametest;

import net.minecraft.network.Connection;

/**
 * Paper 的模拟连接配法:不需要配。Paper 的联网层没有发送前的频道协商检查(发往玩家的包直接交给连接),
 * 模拟主人的连接 {@link OwnerLine} 自己收下行的载荷。
 */
public final class PaperMockConnection implements MockConnection {

    @Override
    public void configure(Connection connection) {
    }
}
