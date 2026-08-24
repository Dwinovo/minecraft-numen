package com.dwinovo.numen.mcp.client;

/**
 * Thrown by an HTTP transport when the server reports the MCP session is gone
 * (HTTP 404 on a request that carried an {@code Mcp-Session-Id}). Per the
 * 2025-06-18 spec the client MUST then start a new session by re-{@code initialize}-ing.
 * {@link McpClient#callTool} catches this, reconnects, and retries the call once.
 */
final class McpSessionExpired extends RuntimeException {
    McpSessionExpired(String message) {
        super(message);
    }
}
