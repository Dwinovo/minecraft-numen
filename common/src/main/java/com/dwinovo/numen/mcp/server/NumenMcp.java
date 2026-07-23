package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.Constants;

import java.nio.file.Path;

/**
 * Loader-agnostic core — both entry points call {@link #initClient} once during
 * client init.
 *
 * <p>This mod is a thin adapter over numen-api's {@code NumenActuator}: it stands
 * up an MCP (Model Context Protocol) server so an external agent (Claude Desktop,
 * via the {@code mcp-remote} stdio bridge) can list the owner's companions, take
 * control of a body, and call its tools directly — the external agent is the
 * brain, the companion is its hands and eyes. Client-only, because companions
 * and their tool registry live in the owner's game client.
 */
public final class NumenMcp {

    private static McpServer server;

    private NumenMcp() {}

    public static void initClient(Path configDir) {
        McpConfig cfg = McpConfig.load(configDir.resolve("numen").resolve("mcp_server.json"));
        if (!cfg.enabled()) {
            Constants.LOG.info("[numen-mcp] disabled in config (config/numen/mcp_server.json)");
            return;
        }
        server = new McpServer(cfg);
        try {
            server.start();
            Constants.LOG.info("[numen-mcp] MCP server up on http://{}:{}/mcp — reach it from Claude Desktop via "
                    + "`npx mcp-remote http://{}:{}/mcp`", cfg.host(), cfg.port(), cfg.host(), cfg.port());
        } catch (Exception ex) {
            Constants.LOG.error("[numen-mcp] failed to start MCP server on {}:{} — {}",
                    cfg.host(), cfg.port(), ex.toString());
            server = null;
        }
    }
}
