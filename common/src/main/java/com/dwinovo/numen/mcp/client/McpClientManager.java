package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wires the MCP client into the engine: read {@code config/numen/mcp_clients.json},
 * connect to each enabled server on a background thread, and register every tool
 * it exposes into the global {@link ToolRegistry} — after which the built-in
 * brain sees those tools automatically ({@code EntityAgentLoop} feeds
 * {@code ToolRegistry.all()} to every LLM turn, so late registration is fine).
 *
 * <p>Called once from each loader's client init. A server that fails to connect
 * is logged and skipped — never crashes init. A JVM shutdown hook closes every
 * transport so stdio subprocesses are killed rather than orphaned.
 */
public final class McpClientManager {

    private static final List<McpTransport> TRANSPORTS = new CopyOnWriteArrayList<>();
    private static volatile boolean initialized;

    private McpClientManager() {}

    /**
     * @param numenConfigDir the {@code config/numen} directory; the config file is
     *                       {@code mcp_clients.json} inside it.
     */
    public static synchronized void initClient(Path numenConfigDir) {
        if (initialized) return;
        initialized = true;

        Path file = numenConfigDir.resolve("mcp_clients.json");
        McpClientConfig config = McpClientConfig.load(file);
        if (!config.enabled()) {
            Constants.LOG.info("[numen-mcp-client] disabled by config");
            return;
        }
        List<ServerSpec> servers = config.servers().stream().filter(ServerSpec::enabled).toList();
        if (servers.isEmpty()) {
            Constants.LOG.info("[numen-mcp-client] no enabled servers in {}", file);
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(McpClientManager::shutdown, "numen-mcp-client-shutdown"));
        Constants.LOG.info("[numen-mcp-client] connecting to {} server(s)", servers.size());
        for (ServerSpec spec : servers) {
            Thread t = new Thread(() -> connectServer(spec), "numen-mcp-connect-" + spec.name());
            t.setDaemon(true);
            t.start();
        }
    }

    /** Close every transport (kills stdio subprocesses). Safe to call more than once. */
    public static void shutdown() {
        for (McpTransport t : TRANSPORTS) {
            try {
                t.close();
            } catch (RuntimeException ex) {
                Constants.LOG.debug("[numen-mcp-client] transport close failed: {}", ex.toString());
            }
        }
        TRANSPORTS.clear();
    }

    private static void connectServer(ServerSpec spec) {
        long connectMs = spec.connectTimeoutSeconds() * 1000L;
        long callMs = spec.callTimeoutSeconds() * 1000L;
        McpTransport transport = null;
        try {
            transport = spec.isStdio()
                    ? new StdioMcpTransport(spec.name(), stdioCommand(spec), spec.env())
                    : new HttpMcpTransport(spec.url(), spec.headers(), spec.connectTimeoutSeconds());
            TRANSPORTS.add(transport);

            McpClient client = new McpClient(spec.name(), transport);
            client.connect(connectMs);
            List<JsonObject> tools = client.listTools(connectMs);

            List<RemoteMcpTool> wrapped = new ArrayList<>();
            for (JsonObject def : tools) {
                if (def.has("name") && !def.get("name").isJsonNull()) {
                    wrapped.add(new RemoteMcpTool(spec.name(), client, def, callMs));
                }
            }
            registerOnMainThread(spec.name(), wrapped);
            Constants.LOG.info("[numen-mcp-client] '{}' connected — {} tool(s)", spec.name(), wrapped.size());
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-mcp-client] server '{}' failed — skipped: {}", spec.name(), ex.toString());
            if (transport != null) {
                TRANSPORTS.remove(transport);
                try {
                    transport.close();
                } catch (RuntimeException ignored) {
                    // best effort
                }
            }
        }
    }

    private static void registerOnMainThread(String server, List<RemoteMcpTool> tools) {
        Minecraft.getInstance().execute(() -> {
            for (RemoteMcpTool tool : tools) {
                try {
                    ToolRegistry.register(tool);
                } catch (RuntimeException ex) {
                    Constants.LOG.warn("[numen-mcp-client] '{}' skip tool {}: {}", server, tool.name(), ex.getMessage());
                }
            }
        });
    }

    private static List<String> stdioCommand(ServerSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(spec.command());
        cmd.addAll(spec.args());
        return cmd;
    }
}
