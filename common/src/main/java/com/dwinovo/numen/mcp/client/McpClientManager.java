package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires the MCP client into the engine and owns the live enable/disable of each
 * external server. On init it reads {@code config/numen/mcp_clients.json},
 * connects to every enabled server on a background thread, and registers the
 * tools it exposes into the global {@link ToolRegistry} — after which the
 * built-in brain sees those tools automatically ({@code EntityAgentLoop} feeds
 * {@code ToolRegistry.all()} to every LLM turn, so late registration/removal is
 * fine).
 *
 * <h2>Live toggles</h2>
 * The companion panel calls {@link #enableServer}/{@link #disableServer} at
 * runtime. Enabling connects (on a background thread) and registers; disabling
 * removes that server's tools from the {@link ToolRegistry} and closes its
 * transport (killing any stdio subprocess). Both persist the flip back to
 * {@code mcp_clients.json} so it survives a restart. Every {@link ToolRegistry}
 * mutation is marshalled onto the client main thread (the registry's backing map
 * is not synchronized and {@code all()} is read on main), mirroring
 * {@code NumenActuator}.
 *
 * <p>A server that fails to connect is logged and its handle marked
 * {@link Status#FAILED}; it never crashes init or the toggle. A JVM shutdown hook
 * closes every transport so stdio subprocesses are killed rather than orphaned.
 */
public final class McpClientManager {

    /** Connection state of one configured server, surfaced to the panel. */
    public enum Status { DISABLED, CONNECTING, CONNECTED, FAILED }

    /** Live runtime state for one configured server (status + what it registered). */
    public static final class ServerHandle {
        private final String name;
        private final String type;                 // "http" | "stdio" — for the panel badge
        private volatile Status status;
        private volatile McpTransport transport;   // set once connected; closed on disable
        private volatile List<String> toolNames = List.of();
        private volatile String error = "";

        ServerHandle(String name, String type, Status status) {
            this.name = name;
            this.type = type;
            this.status = status;
        }

        public String name() { return name; }
        public String type() { return type; }
        public Status status() { return status; }
        public int toolCount() { return toolNames.size(); }
        public List<String> toolNames() { return toolNames; }
        public String error() { return error; }
        /** The panel's toggle is ON whenever the server isn't disabled (FAILED still reads ON). */
        public boolean toggledOn() { return status != Status.DISABLED; }
    }

    private static final Map<String, ServerHandle> HANDLES = new ConcurrentHashMap<>();
    private static volatile McpClientConfig config;
    private static volatile Path configFile;
    private static volatile boolean initialized;

    private McpClientManager() {}

    /**
     * @param numenConfigDir the {@code config/numen} directory; the config file is
     *                       {@code mcp_clients.json} inside it.
     */
    public static synchronized void initClient(Path numenConfigDir) {
        if (initialized) return;
        initialized = true;

        configFile = numenConfigDir.resolve("mcp_clients.json");
        config = McpClientConfig.load(configFile);

        Runtime.getRuntime().addShutdownHook(new Thread(McpClientManager::shutdown, "numen-mcp-client-shutdown"));

        int connecting = 0;
        for (ServerSpec spec : config.servers()) {
            boolean on = config.enabled() && spec.enabled();
            HANDLES.put(spec.name(), new ServerHandle(spec.name(), badge(spec), on ? Status.CONNECTING : Status.DISABLED));
            if (on) {
                spawnConnect(spec);
                connecting++;
            }
        }
        Constants.LOG.info("[numen-mcp-client] {} server(s) configured, {} connecting",
                config.servers().size(), connecting);
    }

    // ---- panel-facing state ----

    /** All configured servers as live handles, in config order (stable for the panel). */
    public static List<ServerHandle> servers() {
        List<ServerHandle> out = new ArrayList<>();
        McpClientConfig cfg = config;
        if (cfg != null) {
            for (ServerSpec s : cfg.servers()) {
                ServerHandle h = HANDLES.get(s.name());
                if (h != null) out.add(h);
            }
        }
        return out;
    }

    /** The persisted spec for a server (url/command/etc., for the expanded panel row), or null. */
    public static ServerSpec spec(String name) {
        return specByName(name);
    }

    // ---- live toggles (called from the panel, on the main thread) ----

    /** Connect a server that is currently off (or retry a failed one) and register its tools. */
    public static void enableServer(String name) {
        ServerSpec spec = specByName(name);
        if (spec == null) return;
        ServerHandle handle = HANDLES.computeIfAbsent(name,
                n -> new ServerHandle(n, badge(spec), Status.DISABLED));
        if (handle.status == Status.CONNECTING || handle.status == Status.CONNECTED) return;  // already on
        handle.status = Status.CONNECTING;
        handle.error = "";
        setEnabledPersist(name, true);
        spawnConnect(spec);
    }

    /** Remove a server's tools, close its transport (killing any subprocess), and mark it off. */
    public static void disableServer(String name) {
        setEnabledPersist(name, false);
        ServerHandle handle = HANDLES.get(name);
        if (handle == null) return;
        runOnMain(() -> {
            for (String tool : handle.toolNames) ToolRegistry.remove(tool);
            handle.toolNames = List.of();
            handle.status = Status.DISABLED;
            closeTransport(handle);
            Constants.LOG.info("[numen-mcp-client] '{}' disabled", name);
        });
    }

    /** Add (or replace) a server from the panel: persist it to config and connect if enabled. */
    public static void upsertServer(ServerSpec spec) {
        if (HANDLES.containsKey(spec.name())) disableServer(spec.name());   // tear down any prior with this name
        McpClientConfig cfg = config;
        Path file = configFile;
        if (cfg == null || file == null) return;
        List<ServerSpec> updated = new ArrayList<>(cfg.servers().size() + 1);
        boolean replaced = false;
        for (ServerSpec s : cfg.servers()) {
            if (s.name().equals(spec.name())) { updated.add(spec); replaced = true; }
            else updated.add(s);
        }
        if (!replaced) updated.add(spec);
        config = new McpClientConfig(cfg.enabled(), List.copyOf(updated));
        McpClientConfig.save(file, config);
        HANDLES.put(spec.name(),
                new ServerHandle(spec.name(), badge(spec), spec.enabled() ? Status.CONNECTING : Status.DISABLED));
        if (spec.enabled()) spawnConnect(spec);
    }

    /** Remove a server entirely: tear it down, drop it from config + handles, persist. */
    public static void deleteServer(String name) {
        disableServer(name);   // removes tools, closes transport (runs on main → synchronous from the panel)
        McpClientConfig cfg = config;
        Path file = configFile;
        if (cfg != null && file != null) {
            List<ServerSpec> updated = new ArrayList<>(cfg.servers().size());
            for (ServerSpec s : cfg.servers()) {
                if (!s.name().equals(name)) updated.add(s);
            }
            config = new McpClientConfig(cfg.enabled(), List.copyOf(updated));
            McpClientConfig.save(file, config);
        }
        HANDLES.remove(name);
    }

    // ---- connect / teardown ----

    private static void spawnConnect(ServerSpec spec) {
        Thread t = new Thread(() -> connectServer(spec), "numen-mcp-connect-" + spec.name());
        t.setDaemon(true);
        t.start();
    }

    private static void connectServer(ServerSpec spec) {
        ServerHandle handle = HANDLES.get(spec.name());
        long connectMs = spec.connectTimeoutSeconds() * 1000L;
        long callMs = spec.callTimeoutSeconds() * 1000L;
        McpTransport transport = null;
        try {
            transport = spec.isStdio()
                    ? new StdioMcpTransport(spec.name(), stdioCommand(spec), spec.env())
                    : new HttpMcpTransport(spec.url(), spec.headers(), spec.connectTimeoutSeconds());

            McpClient client = new McpClient(spec.name(), transport);
            client.connect(connectMs);
            List<JsonObject> tools = client.listTools(connectMs);

            List<RemoteMcpTool> wrapped = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (JsonObject def : tools) {
                if (def.has("name") && !def.get("name").isJsonNull()) {
                    RemoteMcpTool tool = new RemoteMcpTool(spec.name(), client, def, callMs);
                    wrapped.add(tool);
                    names.add(tool.name());
                }
            }
            if (handle != null) handle.transport = transport;
            registerOnMainThread(spec.name(), wrapped, names, handle);
            Constants.LOG.info("[numen-mcp-client] '{}' connected — {} tool(s)", spec.name(), wrapped.size());
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-mcp-client] server '{}' failed — skipped: {}", spec.name(), ex.toString());
            closeQuietly(transport);
            if (handle != null) {
                handle.transport = null;
                handle.status = Status.FAILED;
                handle.error = rootMessage(ex);
            }
        }
    }

    private static void registerOnMainThread(String server, List<RemoteMcpTool> tools,
                                             List<String> names, ServerHandle handle) {
        runOnMain(() -> {
            // Disabled while this connect was in flight → tear the fresh connection back down.
            if (handle != null && handle.status == Status.DISABLED) {
                closeTransport(handle);
                return;
            }
            for (RemoteMcpTool tool : tools) {
                try {
                    ToolRegistry.register(tool);
                } catch (RuntimeException ex) {
                    Constants.LOG.warn("[numen-mcp-client] '{}' skip tool {}: {}", server, tool.name(), ex.getMessage());
                }
            }
            if (handle != null) {
                handle.toolNames = List.copyOf(names);
                handle.status = Status.CONNECTED;
            }
        });
    }

    /** Close every transport (kills stdio subprocesses). Safe to call more than once. */
    public static void shutdown() {
        for (ServerHandle h : HANDLES.values()) closeTransport(h);
    }

    // ---- helpers ----

    private static void setEnabledPersist(String name, boolean enabled) {
        McpClientConfig cfg = config;
        Path file = configFile;
        if (cfg == null || file == null) return;
        List<ServerSpec> updated = new ArrayList<>(cfg.servers().size());
        for (ServerSpec s : cfg.servers()) {
            updated.add(s.name().equals(name) ? McpClientConfig.withEnabled(s, enabled) : s);
        }
        config = new McpClientConfig(cfg.enabled(), List.copyOf(updated));
        McpClientConfig.save(file, config);
    }

    private static ServerSpec specByName(String name) {
        McpClientConfig cfg = config;
        if (cfg == null) return null;
        for (ServerSpec s : cfg.servers()) {
            if (s.name().equals(name)) return s;
        }
        return null;
    }

    private static void closeTransport(ServerHandle handle) {
        McpTransport t = handle.transport;
        handle.transport = null;
        closeQuietly(t);
    }

    private static void closeQuietly(McpTransport t) {
        if (t == null) return;
        try {
            t.close();
        } catch (RuntimeException ex) {
            Constants.LOG.debug("[numen-mcp-client] transport close failed: {}", ex.toString());
        }
    }

    /** Run now if already on the client main thread, otherwise marshal onto it. */
    private static void runOnMain(Runnable r) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isSameThread()) r.run();
        else mc.execute(r);
    }

    private static String badge(ServerSpec spec) {
        return spec.isStdio() ? "stdio" : "http";
    }

    private static List<String> stdioCommand(ServerSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(spec.command());
        cmd.addAll(spec.args());
        return cmd;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}
