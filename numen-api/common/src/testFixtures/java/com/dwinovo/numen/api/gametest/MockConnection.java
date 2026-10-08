package com.dwinovo.numen.api.gametest;

import net.minecraft.network.Connection;

/**
 * 把一条模拟连接配成能收发模组自己的载荷。模拟主人的连接({@link OwnerLine})没有客户端,要收下行的模组载荷,而载荷过不过得了
 * 检查是加载器的事,所以配法由各加载器的开发期实现给出({@link java.util.ServiceLoader},
 * {@code META-INF/services/com.dwinovo.numen.api.gametest.MockConnection}),此处只有这一个口子。
 */
public interface MockConnection {

    /** 把 {@code connection} 配成两边都装齐了本仓模组、频道全都协商好的样子。 */
    void configure(Connection connection);
}
