package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.platform.Services;
import io.netty.buffer.Unpooled;
import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * Paper 的模拟连接配法。Paper 的联网层和模组加载器的两处不同:
 * <ul>
 *   <li>能不能发:Paper 看客户端登记过的插件频道({@code canSend}),装着我们客户端的主人进服之后要替他登记我们的全部下行频道,
 *       就是真客户端进服时报上来的那一步;</li>
 *   <li>收到什么:Paper 把载荷编成字节、包进 {@link DiscardedPayload} 再发,主人这一端收到的得照登记的编码器解回来,
 *       用例才看得到原来类型的载荷。编码器表是 Numen API 的 Paper 网络层私有的,开发期按名字取。</li>
 * </ul>
 * 发送本身没有频道协商检查,连接上不用配什么。
 */
public final class PaperMockConnection implements MockConnection {

    @Override
    public void configure(Connection connection) {
    }

    @Override
    public void installed(ServerPlayer player) {
        for (ResourceLocation id : downlink().keySet()) {
            player.getBukkitEntity().addChannel(id.toString());
        }
    }

    @Override
    public CustomPacketPayload received(CustomPacketPayload payload) {
        if (!(payload instanceof DiscardedPayload bytes)) {
            return payload;
        }
        StreamCodec<? super RegistryFriendlyByteBuf, ?> codec = downlink().get(bytes.id());
        if (codec == null) {
            return payload;
        }
        RegistryFriendlyByteBuf in = new RegistryFriendlyByteBuf(Unpooled.copiedBuffer(bytes.data()),
                net.minecraft.server.MinecraftServer.getServer().registryAccess());
        return (CustomPacketPayload) codec.decode(in);
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, StreamCodec<? super RegistryFriendlyByteBuf, ?>> downlink() {
        try {
            Field field = Services.network().getClass().getDeclaredField("downlink");
            field.setAccessible(true);
            return (Map<ResourceLocation, StreamCodec<? super RegistryFriendlyByteBuf, ?>>) field.get(Services.network());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("取不到 Paper 网络层的下行编码器表", e);
        }
    }
}
