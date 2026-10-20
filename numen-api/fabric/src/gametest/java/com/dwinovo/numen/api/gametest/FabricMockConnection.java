package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.network.NumenNetwork;
import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fabric 的模拟连接配法。发送本身没有频道协商检查({@code ServerPlayNetworking.send} 只是把载荷包成下行包交给
 * {@code connection.send}),连接上不用配什么;但 Numen API 发之前先问 {@code ServerPlayNetworking.canSend},那查的是客户端登记过的
 * 频道。装着我们客户端的主人进服之后要替他登记我们的全部下行频道,就是真客户端进入游戏阶段时报上来的那一步
 * (先商定通用包的版本,再报频道)。下行频道表是 {@link NumenNetwork} 私有的,开发期按名字取。
 */
public final class FabricMockConnection implements MockConnection {

    /** Fabric 通用包协议目前只有这一个版本。 */
    private static final int COMMON_PACKET_VERSION = 1;

    @Override
    public void configure(Connection connection) {
    }

    @Override
    public void installed(ServerPlayer player) {
        var addon = ServerNetworkingImpl.getAddon(player.connection);
        addon.onCommonVersionPacket(COMMON_PACKET_VERSION);
        addon.onCommonRegisterPacket(
                new CommonRegisterPayload(COMMON_PACKET_VERSION, CommonRegisterPayload.PLAY_PHASE, downlink()));
    }

    private static Set<ResourceLocation> downlink() {
        try {
            Field field = NumenNetwork.class.getDeclaredField("TO_CLIENT");
            field.setAccessible(true);
            return ((Map<?, ?>) field.get(null)).keySet().stream()
                    .map(type -> ((CustomPacketPayload.Type<?>) type).id())
                    .collect(Collectors.toSet());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("取不到 Numen API 网络层的下行包表", e);
        }
    }
}
