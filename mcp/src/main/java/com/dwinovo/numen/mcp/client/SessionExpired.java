package com.dwinovo.numen.mcp.client;

import java.io.IOException;

/** Streamable HTTP 的服务端对这个会话号答了 404:会话已被它结束,规范要求客户端重新握手开新会话。 */
public final class SessionExpired extends IOException {

    public SessionExpired(String sessionId) {
        super("the server ended session " + sessionId);
    }
}
