package com.dwinovo.numen.mcp.server;

import java.io.IOException;
import java.net.URI;

/**
 * 处理链的第一环,防 DNS 重绑定:只绑回环时,请求的 {@code Host} 必须是回环的名字(重绑定来的请求 Host 是攻击者的域名);
 * 浏览器发的请求带 {@code Origin},带了就必须是访问这个地址的同一个源,否则照规范答 403。合法的客户端是命令行或桌面程序,不带 Origin。
 */
final class OriginGuard implements Stage {

    private final boolean lanExposed;

    /** @param lanExposed 端点绑在回环之外(见 {@link McpConfig#lanExposed}) */
    OriginGuard(boolean lanExposed) {
        this.lanExposed = lanExposed;
    }

    @Override
    public void handle(HttpCall call, Next next) throws IOException {
        if (!allowed(call.header("Host"), call.header("Origin"))) {
            call.refuse(403, -32000, "Forbidden: the Host or Origin header is not allowed");
            return;
        }
        next.handle(call);
    }

    private boolean allowed(String host, String origin) {
        if (!lanExposed && !loopbackHost(host)) {
            return false;
        }
        if (origin == null) {
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String originHost = uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort();
            return host != null && host.equalsIgnoreCase(originHost);
        } catch (IllegalArgumentException bad) {
            return false;
        }
    }

    /** {@code Host} 头的主机名(去掉端口)是回环的名字吗。 */
    private static boolean loopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String name = host.startsWith("[") ? host.substring(0, host.indexOf(']') + 1)
                : host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
        return name.equalsIgnoreCase("localhost") || name.equals("127.0.0.1") || name.equals("[::1]");
    }
}
