package com.dwinovo.numen.mcp.server;

import java.io.IOException;
import java.util.List;

/**
 * 端点处理链上的一环:每一环只管一件事(查来源、认令牌、管陌生地址……),要么自己答掉这个请求,要么交给下一环。链由
 * {@link McpEndpoint} 拼起来,末端是 Streamable HTTP 传输({@link StreamableHttp})。
 */
@FunctionalInterface
interface Stage {

    void handle(HttpCall call, Next next) throws IOException;

    /** 链上剩下的部分。 */
    @FunctionalInterface
    interface Next {
        void handle(HttpCall call) throws IOException;
    }

    /** 把几环依次接到 {@code end} 前面。 */
    static Next chain(List<Stage> stages, Next end) {
        Next next = end;
        for (int i = stages.size() - 1; i >= 0; i--) {
            Stage stage = stages.get(i);
            Next rest = next;
            next = call -> stage.handle(call, rest);
        }
        return next;
    }
}
