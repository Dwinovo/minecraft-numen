package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.client.agent.BrainSite;
import com.dwinovo.numen.api.client.chat.ChatLines;
import com.dwinovo.numen.api.mcp.client.McpClientManager;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

/**
 * 外接大脑宿主在客户端的那一份:同伴的大脑在主人客户端时({@link BrainSite#CLIENT})由两个加载器的客户端入口装载、
 * 每刻驱动;大脑在服务端时宿主跟着大脑在服务端,这里什么都不做。
 */
public final class ClientMcp {

    private ClientMcp() {}

    public static void init(Path configDir) {
        if (BrainSite.CURRENT != BrainSite.CLIENT) {
            return;
        }
        NumenMcp.start(configDir.resolve(Constants.CONFIG_ROOT).resolve("mcp_server.json"), new ClientHands(),
                new McpMode.Notices() {
                    @Override
                    public void quiet(boolean fallback) {
                        ChatLines.notice(I18n.get("numen.brain.title"),
                                I18n.get(fallback ? "numen.brain.quiet_fallback" : "numen.brain.quiet_standby"));
                    }

                    @Override
                    public void back() {
                        ChatLines.notice(I18n.get("numen.brain.title"), I18n.get("numen.brain.back_active"));
                    }
                });
    }

    /**
     * 接进来的 MCP 工具(旧循环在客户端时):连接在这里管,要授权的服务器把网址交给浏览器——每个网址只开一次。
     * 大脑在服务端时这件事在服务端做,客户端不碰。
     */
    public static void initClients(Path numenConfigDir) {
        if (BrainSite.CURRENT != BrainSite.CLIENT) {
            return;
        }
        Set<String> opened = new HashSet<>();
        McpClientManager.init(numenConfigDir, task -> Minecraft.getInstance().execute(task), () -> {
            for (McpClientManager.ServerHandle h : McpClientManager.servers()) {
                if (h.authUrl() != null && opened.add(h.authUrl())) {
                    Util.getPlatform().openUri(URI.create(h.authUrl()));
                }
            }
        });
    }

    public static void tick() {
        if (BrainSite.CURRENT == BrainSite.CLIENT) {
            McpMode.instance().tick();
        }
    }
}
