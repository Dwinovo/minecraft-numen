package com.dwinovo.numen.mcp.protocol;

/**
 * 握手的答复里客户端要的那几样。
 *
 * @param toolsListChanged 服务端说它会发工具表变了的通知
 * @param instructions     使用说明;没有是 null
 */
public record InitializeResult(ProtocolVersion protocolVersion, Implementation serverInfo, boolean toolsListChanged,
                               String instructions) {
}
