package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.brain.OwnerPresence;
import com.dwinovo.numen.api.home.Homes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 远程端点宿主在服务端的装载:同伴的大脑在服务端,宿主跟着在服务端,配置与令牌住在服务器的家里({@link Homes#server}),
 * 手是每位主人一份的 {@link ServerActuator}。单人游戏与专用服走同一条路;专用服的端点默认关,服主在配置里开。
 * 外部大脑连上、断开作为系统消息发给主人,话由他客户端的语言翻。
 */
public final class ServerMcp {

    private ServerMcp() {}

    public static void start(MinecraftServer server) {
        McpMode.instance().bootstrap(Homes.of(server).server(), owner -> new ServerActuator(server, owner),
                new McpMode.Watch() {
                    @Override
                    public void connected(UUID owner) {
                        tell(server, owner, "numen.brain.back_active");
                    }

                    @Override
                    public void gone(UUID owner) {
                        tell(server, owner, "numen.brain.quiet_fallback");
                        OwnerPresence.settle(server, owner);
                    }
                });
    }

    private static void tell(MinecraftServer server, UUID owner, String key) {
        var player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            player.sendSystemMessage(Component.literal("[").append(Component.translatable("numen.brain.title"))
                    .append("] ").append(Component.translatable(key)));
        }
    }
}
