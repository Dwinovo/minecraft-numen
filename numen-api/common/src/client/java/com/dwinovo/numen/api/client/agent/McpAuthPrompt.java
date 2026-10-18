package com.dwinovo.numen.api.client.agent;

import net.minecraft.Util;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/**
 * 接进来的 MCP 服务器要 OAuth 授权时,谁去开浏览器:服务端没有浏览器,授权网址作为那台服务器状态的一项推给管理员客户端
 * ({@link ServerLibraries.McpClientRow#authUrl}),由<b>让它接上的那一端</b>去开——别的管理员的客户端上不会无端弹出浏览器。
 * 授权完成后的回调网址回到服务端:浏览器就在服务器那台机器上时回环地址直接接住,不在时管理员把地址栏的网址贴回设置页。
 *
 * <p>线程:客户端主线程。
 */
public final class McpAuthPrompt {

    /** 这一端刚让它接上、可能要授权的那几台。授权网址开过、或者它不需要授权了,就不再等。 */
    private static final Set<String> EXPECTING = new HashSet<>();

    private McpAuthPrompt() {}

    /** 这一端让这台接上了(新建、启用、重试):它要授权的话,网址来时由这一端开。 */
    public static void expect(String server) {
        EXPECTING.add(server);
    }

    /** 服务端推来了接进来的 MCP 服务器的新状态。 */
    static void onServers() {
        EXPECTING.removeIf(name -> {
            ServerLibraries.McpClientRow row = ServerLibraries.mcpClients().stream()
                    .filter(r -> r.name().equals(name)).findFirst().orElse(null);
            if (row == null || !row.full()) {
                return true;
            }
            if (row.authUrl() != null) {
                Util.getPlatform().openUri(URI.create(row.authUrl()));
                return true;
            }
            return switch (row.status()) {
                case CONNECTED, FAILED, DISABLED -> true;
                case CONNECTING, AUTHORIZING -> false;
            };
        });
    }

    /** 断线:等着的都不算了。 */
    static void clear() {
        EXPECTING.clear();
    }
}
