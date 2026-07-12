package com.dwinovo.numen.mcp.client;

import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * Moves JSON-RPC frames to one external MCP server and back. Two impls:
 * {@link HttpMcpTransport} (POST per frame) and {@link StdioMcpTransport}
 * (newline-delimited over a subprocess's stdin/stdout). The client
 * ({@link McpClient}) owns id generation and request/response semantics;
 * a transport only ships a frame and returns the matching response.
 */
public interface McpTransport extends AutoCloseable {

    /**
     * Send a request frame (it already carries an {@code id}) and complete with
     * the response envelope ({@code {jsonrpc, id, result|error}}). The future
     * completes exceptionally on transport failure (network error, non-2xx HTTP,
     * broken pipe) or after {@code timeoutMs}.
     */
    CompletableFuture<JsonObject> request(JsonObject frame, long timeoutMs);

    /** Fire a notification frame (no {@code id}); no response is expected. */
    void notify(JsonObject frame);

    /**
     * Tell the transport the protocol version negotiated during {@code initialize}
     * (the value the <em>server</em> returned). HTTP transports MUST send it as the
     * {@code MCP-Protocol-Version} header on every later request (spec 2025-06-18).
     * No-op for stdio. Called by {@link McpClient} right after the handshake.
     */
    default void setProtocolVersion(String version) {}

    @Override
    void close();
}
