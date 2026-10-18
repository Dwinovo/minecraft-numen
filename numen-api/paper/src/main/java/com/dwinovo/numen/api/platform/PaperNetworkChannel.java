package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.INetworkChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Paper 上的 {@link INetworkChannel}。Paper 没有自己的包注册表,我们的包是 Bukkit 的"插件频道"(频道名就是包的 id):
 * <ul>
 *   <li>下行:每次发送各编一次字节(包不能复用,{@link DiscardedPayload} 的字节缓冲发一次就读空了),包进 {@code DiscardedPayload}
 *       直接发进玩家的连接;</li>
 *   <li>上行:登记成插件频道的接收者,收到的字节交 {@code StreamCodec} 解码。Paper 把插件频道的包交到服务端主线程再回调,
 *       所以 {@link Execution#NETWORK} 与 {@link Execution#MAIN} 的处理器都直接在主线程上调,{@code NETWORK} 的要求(哪个线程都必须对)
 *       自然满足;</li>
 *   <li>{@link #canSend}:客户端登记过的频道。NeoForge 客户端在配置阶段末尾登记,Fabric 客户端进入游戏阶段之后才登记,
 *       Paper 把两处的登记都记在这位玩家身上,查的时候直接问。</li>
 * </ul>
 */
public final class PaperNetworkChannel implements INetworkChannel {

    /** 下行包的 id → 编码器。 */
    private final Map<ResourceLocation, StreamCodec<? super RegistryFriendlyByteBuf, ?>> downlink = new HashMap<>();

    @Override
    public <T extends CustomPacketPayload> void registerClientToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler,
            Execution execution) {
        JavaPlugin plugin = JavaPlugin.getProvidingPlugin(PaperNetworkChannel.class);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, type.id().toString(),
                (channel, bukkitPlayer, bytes) -> {
                    ServerPlayer player = ((CraftPlayer) bukkitPlayer).getHandle();
                    ByteBuf in = Unpooled.wrappedBuffer(bytes);
                    handler.accept(codec.decode(new RegistryFriendlyByteBuf(in, player.server.registryAccess())), player);
                });
    }

    @Override
    public void sendToServer(CustomPacketPayload payload) {
        throw new UnsupportedOperationException("Paper 是服务端,没有上行可发");
    }

    /** 下行包只需要编码器;处理体是客户端的,服务端上永远不会被调。 */
    @Override
    public <T extends CustomPacketPayload> void registerServerToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {
        downlink.put(type.id(), codec);
    }

    @Override
    public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        ResourceLocation id = payload.type().id();
        StreamCodec<? super RegistryFriendlyByteBuf, ?> registered = downlink.get(id);
        if (registered == null) {
            throw new IllegalStateException("下行包 " + id + " 没有登记过");
        }
        @SuppressWarnings("unchecked")
        StreamCodec<? super RegistryFriendlyByteBuf, CustomPacketPayload> codec =
                (StreamCodec<? super RegistryFriendlyByteBuf, CustomPacketPayload>) registered;
        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.server.registryAccess());
        try {
            codec.encode(out, payload);
            player.connection.send(new ClientboundCustomPayloadPacket(
                    new DiscardedPayload(id, Unpooled.wrappedBuffer(ByteBufUtil.getBytes(out)))));
        } finally {
            out.release();
        }
    }

    @Override
    public boolean canSend(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.getBukkitEntity().getListeningPluginChannels().contains(type.id().toString());
    }
}
