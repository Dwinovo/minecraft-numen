package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.AppendLog;
import com.google.gson.JsonObject;

import java.util.UUID;

/**
 * 远程端点的审计日志({@value #FILE},只追加):每次接入与每次工具调用一条。只记编号、令牌编号、工具名、成败、
 * 请求与回执的字节数——不记程序源码、事件正文、她说的话,也不记令牌本身。
 */
final class McpAudit {

    static final String FILE = "mcp_audit.jsonl";

    private final AppendLog log;

    McpAudit(Home home) {
        this.log = new AppendLog(home.file(FILE), AppendLog.Format.of(1), home.io());
    }

    /** 一次接入({@code initialize}):对方自报的名字不是内容,是"谁接进来了"。 */
    void connected(String session, McpToken token, String client) {
        JsonObject body = new JsonObject();
        body.addProperty("session", session);
        body.addProperty("token", token.id());
        body.addProperty("role", token.role().name());
        body.addProperty("client", client);
        log.append("connect", body);
    }

    /** 接入被拒(令牌不对、会话开满)——没有会话号,令牌认不出来就没有令牌编号。 */
    void refused(String why, String tokenId) {
        JsonObject body = new JsonObject();
        body.addProperty("why", why);
        if (tokenId != null) {
            body.addProperty("token", tokenId);
        }
        log.append("refused", body);
    }

    /** 一次工具调用。{@code companion} 没有是 null。 */
    void called(String session, McpToken token, String tool, UUID companion, boolean ok, int bytesIn, int bytesOut) {
        JsonObject body = new JsonObject();
        body.addProperty("session", session);
        body.addProperty("token", token.id());
        body.addProperty("tool", tool);
        if (companion != null) {
            body.addProperty("companion", companion.toString());
        }
        body.addProperty("ok", ok);
        body.addProperty("in", bytesIn);
        body.addProperty("out", bytesOut);
        log.append("call", body);
    }
}
