package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.NumenPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Function;

/**
 * Fabric 的模拟连接配法。发送本身没有频道协商检查({@code ServerPlayNetworking.send} 只是把载荷包成下行包交给
 * {@code connection.send}),连接上不用配什么;但 Numen API 发之前先问 {@code ServerPlayNetworking.canSend},那查的是客户端登记过的
 * 频道。这一代的 Fabric 客户端进入游戏之后才发一个 {@code minecraft:register} 包报它的频道;装着我们客户端的主人进服之后,这里替他
 * 发同一个包(我们的全部下行频道),服务端照收真客户端的那一路处理它,平台的开场也就照样在频道登记的那一刻发出。
 *
 * <p>收到的包按通道 id 找回登记时的解码器读回载荷,和客户端收包是同一张表。下行表是 {@link NumenNetwork} 私有的,开发期按名字取。
 */
public final class FabricMockConnection implements MockConnection {

    private static final ResourceLocation REGISTER = new ResourceLocation("minecraft", "register");

    @Override
    public void configure(Connection connection) {
        // 频道在进服之后由 installed 报上去,连接本身不用配
    }

    @Override
    public void installed(ServerPlayer player) {
        FriendlyByteBuf channels = new FriendlyByteBuf(Unpooled.buffer());
        boolean first = true;
        for (ResourceLocation id : downlink().keySet()) {
            if (!first) {
                channels.writeByte(0);
            }
            channels.writeBytes(id.toString().getBytes(StandardCharsets.US_ASCII));
            first = false;
        }
        player.connection.handleCustomPayload(new ServerboundCustomPayloadPacket(REGISTER, channels));
    }

    @Override
    public NumenPayload received(ClientboundCustomPayloadPacket packet) {
        Function<FriendlyByteBuf, NumenPayload> decoder = decoder(packet.getIdentifier());
        return decoder == null ? null : decoder.apply(new FriendlyByteBuf(packet.getData().copy()));
    }

    /** 下行这一种包登记的解码器;不是我们的下行包是 null。 */
    @SuppressWarnings("unchecked")
    private static Function<FriendlyByteBuf, NumenPayload> decoder(ResourceLocation id) {
        Object route = downlink().get(id);
        if (route == null) {
            return null;
        }
        try {
            Method decoder = route.getClass().getDeclaredMethod("decoder");
            decoder.setAccessible(true);
            return (Function<FriendlyByteBuf, NumenPayload>) decoder.invoke(route);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("取不到下行包 " + id + " 的解码器", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<ResourceLocation, ?> downlink() {
        try {
            Field field = NumenNetwork.class.getDeclaredField("TO_CLIENT");
            field.setAccessible(true);
            return (Map<ResourceLocation, ?>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("取不到 Numen API 网络层的下行包表", e);
        }
    }
}
