package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.network.NumenPayload;
import java.util.ServiceLoader;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * 把一条模拟连接配成能收发模组自己的载荷。模拟主人的连接({@link OwnerLine})没有客户端,要收下行的模组载荷,而载荷过不过得了
 * 检查、在连接上收到的是什么样子是平台的事,所以做法由各平台的开发期实现给出({@link java.util.ServiceLoader},
 * {@code META-INF/services/com.dwinovo.numen.api.gametest.MockConnection}),此处只有这一个口子。
 */
public interface MockConnection {

    MockConnection INSTANCE = ServiceLoader.load(MockConnection.class, MockConnection.class.getClassLoader())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("没有 MockConnection 的实现:平台的开发期源码集要提供一个"));

    /** 把 {@code connection} 配成两边都装齐了本仓模组、频道全都协商好的样子。 */
    void configure(Connection connection);

    /** 装着我们客户端的主人进服之后,平台还要为这位玩家做的事(模组上频道表在连接上,配过就够了,默认什么也不用做)。 */
    default void installed(ServerPlayer player) {
    }

    /**
     * 服务端发出的一个自定义载荷包,在这条连接的对端读出来的 Numen 载荷;不是我们的包是 null。这一代没有载荷类型,线上只有
     * 频道 id 与字节,怎么从加载器的频道里读回载荷是平台的事。
     */
    NumenPayload received(ClientboundCustomPayloadPacket packet);
}
