package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.network.NumenNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.impl.networking.CommonRegisterPayload;
import net.fabricmc.fabric.impl.networking.server.ServerNetworkingImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.stream.Collectors;

/**
 * Fabric 的模拟连接配法。发送本身没有频道协商检查({@code ServerPlayNetworking.send} 只是把载荷包成下行包交给
 * {@code connection.send}),连接上不用配什么;但 Numen API 发之前先问 {@code ServerPlayNetworking.canSend},那查的是客户端登记过的
 * 频道。装着我们客户端的主人进服之后要替他登记我们的全部下行频道,就是真客户端进入游戏阶段时报上来的那一步
 * (先商定通用包的版本,再报频道)。下行频道表是 {@link NumenNetwork} 私有的,开发期按名字取。
 */
public final class FabricMockConnection implements MockConnection, ModInitializer {

    /** Fabric 通用包协议目前只有这一个版本。 */
    private static final int COMMON_PACKET_VERSION = 1;

    /** 配成"装着我们客户端"的连接:真客户端的频道在进入游戏阶段、登入事件之前就报上来了,进服时按这张表替它登记。 */
    private static final Set<Connection> CONFIGURED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    @Override
    public void configure(Connection connection) {
        CONFIGURED.add(connection);
    }

    /**
     * 进服时的登记要赶在 {@code ServerPlayConnectionEvents.JOIN} 之前(INIT 是游戏阶段的连接监听器刚建好),这样平台的登入响应看到的和真客户端
     * 进服时一样:频道已经能发。进服之后才登记的话,登入那一刻问"能不能发"得到否,用例就测不到平台自己的开场。
     */
    @Override
    public void onInitialize() {
        ServerPlayConnectionEvents.INIT.register((handler, server) -> {
            if (CONFIGURED.contains(connectionOf(handler))) {
                installed(handler.getPlayer());
            }
        });
    }

    @Override
    public void installed(ServerPlayer player) {
        var addon = ServerNetworkingImpl.getAddon(player.connection);
        addon.onCommonVersionPacket(COMMON_PACKET_VERSION);
        addon.onCommonRegisterPacket(
                new CommonRegisterPayload(COMMON_PACKET_VERSION, CommonRegisterPayload.PLAY_PHASE, downlink()));
    }

    /** 监听器背后的连接:原版把它写成了受保护字段,开发期按名字取。 */
    private static Connection connectionOf(ServerGamePacketListenerImpl handler) {
        try {
            Field field = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
            field.setAccessible(true);
            return (Connection) field.get(handler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("取不到游戏阶段监听器的连接", e);
        }
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
