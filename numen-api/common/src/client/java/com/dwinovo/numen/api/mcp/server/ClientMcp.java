package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.client.agent.BrainSite;
import com.dwinovo.numen.api.client.chat.ChatLines;
import net.minecraft.client.resources.language.I18n;

import java.nio.file.Path;

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

    public static void tick() {
        if (BrainSite.CURRENT == BrainSite.CLIENT) {
            McpMode.instance().tick();
        }
    }
}
