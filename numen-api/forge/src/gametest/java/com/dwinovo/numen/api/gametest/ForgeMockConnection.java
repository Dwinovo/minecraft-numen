package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.network.NumenPayload;
import com.dwinovo.numen.api.platform.ForgeNetworkChannel;
import com.dwinovo.numen.api.platform.Services;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.NetworkRegistry;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * Forge 的模拟连接配法。Forge 判对端有没有某条频道({@code SimpleChannel.isRemotePresent})看的是连接上的握手数据:真客户端登录时
 * FML 握手把它的频道表报上来,服务端用 {@code NetworkHooks.appendConnectionData} 挂到连接上。模拟连接没走登录握手,这里替装着
 * 我们客户端的主人报同一张表:两边模组一样,客户端的频道与版本就是服务端登记的那一张({@code NetworkRegistry.buildChannelVersions})。
 * 这两个方法 Forge 只留在包内,开发期按名字取。
 *
 * <p>收到的包交给 {@link ForgeNetworkChannel#payloadOf},与客户端收包走同一张解码表。
 */
public final class ForgeMockConnection implements MockConnection {

    @Override
    public void configure(Connection connection) {
        try {
            Method versions = NetworkRegistry.class.getDeclaredMethod("buildChannelVersions");
            versions.setAccessible(true);
            Method append = NetworkHooks.class.getDeclaredMethod("appendConnectionData", Connection.class, Map.class,
                    Map.class);
            append.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<ResourceLocation, String> channels = (Map<ResourceLocation, String>) versions.invoke(null);
            append.invoke(null, connection, Map.of(), channels);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("配不上 Forge 的握手数据", e);
        }
    }

    @Override
    public NumenPayload received(ClientboundCustomPayloadPacket packet) {
        return ((ForgeNetworkChannel) Services.network()).payloadOf(packet);
    }
}
