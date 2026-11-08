package com.dwinovo.numen.api.gametest;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.dwinovo.numen.api.platform.ForgeNetworkChannel;
import com.dwinovo.numen.api.platform.Services;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkContext;
import net.minecraftforge.network.NetworkRegistry;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Forge 的模拟连接配法。Forge 判对端有没有某条频道({@code Channel.isRemotePresent})看的是连接上的网络上下文({@link NetworkContext})
 * 记下的对端频道:真客户端在配置阶段把它的频道表报上来,服务端记进去。模拟连接没走配置阶段,这里替装着我们客户端的主人记同一张表:
 * 两边模组一样,客户端的频道就是服务端登记的那一张({@link NetworkRegistry#buildChannelVersions})。记频道的那个集合 Forge 只留在包内,
 * 开发期按名字取。
 *
 * <p>收到的包交给 {@link ForgeNetworkChannel#payloadOf},与客户端收包走同一张解码表。
 */
public final class ForgeMockConnection implements MockConnection {

    @Override
    public void configure(Connection connection) {
        try {
            Field remote = NetworkContext.class.getDeclaredField("remoteChannels");
            remote.setAccessible(true);
            @SuppressWarnings("unchecked")
            Set<ResourceLocation> channels = (Set<ResourceLocation>) remote.get(NetworkContext.get(connection));
            channels.addAll(NetworkRegistry.buildChannelVersions().keySet());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("配不上 Forge 的对端频道表", e);
        }
    }

    @Override
    public CustomPacketPayload received(ClientboundCustomPayloadPacket packet) {
        return ((ForgeNetworkChannel) Services.network()).payloadOf(packet);
    }
}
