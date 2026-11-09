package com.dwinovo.numen.mcp.server;

import java.io.IOException;

/**
 * 处理链的一环:认令牌。每个请求都要 {@code Authorization: Bearer <令牌>}({@link McpTokens});令牌不进查询串。认出的记在
 * {@link HttpCall#token},认不出的记下原因交给下一环({@link StrangerThrottle})去答——这一环只回答"这是谁"。
 */
final class BearerAuth implements Stage {

    private final McpTokens tokens;

    BearerAuth(McpTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public void handle(HttpCall call, Next next) throws IOException {
        String auth = call.header("Authorization");
        if (auth == null || !auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            call.refusal = "no bearer token";
        } else {
            call.token = tokens.authenticate(auth.substring(7).trim()).orElse(null);
            if (call.token == null) {
                call.refusal = "unknown or expired token";
            }
        }
        next.handle(call);
    }
}
