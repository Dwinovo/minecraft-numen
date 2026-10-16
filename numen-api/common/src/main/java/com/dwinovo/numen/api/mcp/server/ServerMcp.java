package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.NumenPaths;
import com.mojang.authlib.GameProfile;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * 外接大脑宿主在服务端的装载:同伴的大脑在服务端,宿主跟着在服务端({@link NumenMcp}),手是 {@link ServerActuator}。
 * 失联与回归的播报作为系统消息发给主人,话由他客户端的语言翻。
 *
 * <p>单人游戏(含对局域网开放)的外脑驱动房主名下的同伴,本机端口和旧版一样。专用服务器上"外脑驱动谁的同伴"要有令牌、
 * 作用域和限流才说得清,那是 {@code docs/input-pipeline.md} 第 4 步,这里不起。
 */
public final class ServerMcp {

    private ServerMcp() {}

    public static void start(MinecraftServer server) {
        GameProfile host = server.getSingleplayerProfile();
        if (host == null) {
            Constants.LOG.info("[numen-mcp] 专用服务器不起外接大脑宿主(令牌与作用域是后面的事)");
            return;
        }
        NumenMcp.start(NumenPaths.config().resolve("mcp_server.json"), new ServerActuator(server, host.getId()),
                new McpMode.Notices() {
                    @Override
                    public void quiet(boolean fallback) {
                        tell(server, host, fallback ? "numen.brain.quiet_fallback" : "numen.brain.quiet_standby");
                    }

                    @Override
                    public void back() {
                        tell(server, host, "numen.brain.back_active");
                    }
                });
    }

    private static void tell(MinecraftServer server, GameProfile host, String key) {
        var player = server.getPlayerList().getPlayer(host.getId());
        if (player != null) {
            player.sendSystemMessage(Component.literal("[").append(Component.translatable("numen.brain.title"))
                    .append("] ").append(Component.translatable(key)));
        }
    }
}
