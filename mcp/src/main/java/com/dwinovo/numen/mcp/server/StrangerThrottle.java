package com.dwinovo.numen.mcp.server;

import java.io.IOException;

/**
 * 处理链的一环:没认出令牌的请求到这里收场。认出了的照常放行,不受来源地址牵连(反向代理后面所有人共用一个地址);
 * 认不出的记到地址上({@link McpStrangers}),这个地址的失败满了答 429,否则答 401。
 */
final class StrangerThrottle implements Stage {

    private final McpStrangers strangers;

    StrangerThrottle(McpStrangers strangers) {
        this.strangers = strangers;
    }

    @Override
    public void handle(HttpCall call, Next next) throws IOException {
        if (call.token != null) {
            next.handle(call);
            return;
        }
        String address = call.address();
        // 限没限看的是这次之前的失败:这一次记上之后才满的,这一次仍答 401
        boolean throttled = strangers.blocked(address);
        strangers.failed(address, call.refusal);
        if (throttled) {
            call.setHeader("Retry-After", "60");
            call.respond(429);
        } else {
            call.setHeader("WWW-Authenticate", "Bearer realm=\"numen\"");
            call.respond(401);
        }
    }
}
