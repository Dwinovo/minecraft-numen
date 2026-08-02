package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.mcp.client.McpClientConfig.ServerSpec;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static volatile Path configDir;
    private static volatile boolean initialized;

    private McpClientManager() {}

    /**
     * @param numenConfigDir the {@code config/numen} directory; the config file is
     *                       {@code mcp_clients.json} inside it.
     */
    public static synchronized void initClient(Path numenConfigDir) {
        if (initialized) return;
        initialized = true;

        configDir = numenConfigDir;
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

    private static final int MAX_CONNECT_ATTEMPTS = 3;

    /** Connect with retries for transient failures, running the OAuth flow once on a 401. */
    private static void connectServer(ServerSpec spec) {
        ServerHandle handle = HANDLES.get(spec.name());
        Exception last = null;
        boolean authTried = false;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            if (handle != null && handle.status == Status.DISABLED) return;   // toggled off mid-connect
            try {
                connectOnce(spec, handle);
                return;
            } catch (McpAuthRequired auth) {
                if (authTried || configDir == null) { last = auth; break; }   // token still rejected — stop
                authTried = true;
                if (handle != null) {
                    handle.status = Status.CONNECTING;
                    handle.error = "等待浏览器授权…";
                }
                Constants.LOG.info("[numen-mcp-client] '{}' requires OAuth — launching browser flow", spec.name());
                try {
                    McpOAuth.authorize(spec, auth.resourceMetadataUrl(), configDir);
                    // token stored — the next connectOnce merges it in (see withOAuth); no extra delay
                } catch (Exception oe) {
                    last = oe;
                    Constants.LOG.warn("[numen-mcp-client] '{}' OAuth failed: {}", spec.name(), oe.toString());
                    break;
                }
            } catch (Exception ex) {
                last = ex;
                Constants.LOG.warn("[numen-mcp-client] '{}' connect attempt {}/{} failed: {}",
                        spec.name(), attempt, MAX_CONNECT_ATTEMPTS, ex.toString());
                if (attempt < MAX_CONNECT_ATTEMPTS) sleepQuiet(1500);
            }
        }
        if (handle != null && handle.status != Status.DISABLED) {
            handle.transport = null;
            handle.status = Status.FAILED;
            handle.error = rootMessage(last);
        }
    }

    /** One connect attempt: build transport, initialize, list tools, register. Throws on any failure. */
    private static void connectOnce(ServerSpec spec, ServerHandle handle) throws Exception {
        long connectMs = spec.connectTimeoutSeconds() * 1000L;
        long callMs = spec.callTimeoutSeconds() * 1000L;
        McpTransport transport = null;
        try {
            transport = spec.isStdio()
                    ? new StdioMcpTransport(spec.name(), stdioCommand(spec), spec.env())
                    : new HttpMcpTransport(spec.url(), withOAuth(spec), spec.connectTimeoutSeconds());

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
            // Listen for server-pushed notifications and open the server→client stream (HTTP GET SSE).
            client.setNotificationListener((method, params) -> onServerNotification(spec, client, handle, method));
            client.startServerStream();
            Constants.LOG.info("[numen-mcp-client] '{}' connected — {} tool(s)", spec.name(), wrapped.size());
        } catch (Exception ex) {
            closeQuietly(transport);
            throw ex;
        }
    }

    /** Handle a server→client notification. Chiefly {@code tools/list_changed} → re-list & re-register. */
    private static void onServerNotification(ServerSpec spec, McpClient client, ServerHandle handle, String method) {
        switch (method) {
            case "notifications/tools/list_changed" -> {
                Constants.LOG.info("[numen-mcp-client] '{}' tools/list_changed — refreshing", spec.name());
                refreshTools(spec, client, handle);
            }
            case "notifications/message", "notifications/progress" ->
                    Constants.LOG.debug("[numen-mcp-client] '{}' {}", spec.name(), method);
            default -> Constants.LOG.debug("[numen-mcp-client] '{}' notification {}", spec.name(), method);
        }
    }

    /** Re-list a server's tools and swap them in the registry (on a background thread, mutate on main). */
    private static void refreshTools(ServerSpec spec, McpClient client, ServerHandle handle) {
        Thread t = new Thread(() -> {
            try {
                long listMs = spec.connectTimeoutSeconds() * 1000L;
                long callMs = spec.callTimeoutSeconds() * 1000L;
                List<JsonObject> tools = client.listTools(listMs);
                List<RemoteMcpTool> wrapped = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (JsonObject def : tools) {
                    if (def.has("name") && !def.get("name").isJsonNull()) {
                        RemoteMcpTool tool = new RemoteMcpTool(spec.name(), client, def, callMs);
                        wrapped.add(tool);
                        names.add(tool.name());
                    }
                }
                runOnMain(() -> {
                    if (handle == null || handle.status != Status.CONNECTED) return;   // disabled meanwhile
                    for (String old : handle.toolNames) ToolRegistry.remove(old);
                    for (RemoteMcpTool tool : wrapped) {
                        try {
                            ToolRegistry.register(tool);
                        } catch (RuntimeException ignored) {
                            // duplicate name across a refresh race — skip
                        }
                    }
                    handle.toolNames = List.copyOf(names);
                    Constants.LOG.info("[numen-mcp-client] '{}' now {} tool(s)", spec.name(), names.size());
                });
            } catch (Exception ex) {
                Constants.LOG.warn("[numen-mcp-client] '{}' tools refresh failed: {}", spec.name(), ex.toString());
            }
        }, "numen-mcp-refresh-" + spec.name());
        t.setDaemon(true);
        t.start();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
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

    /** Merge a stored OAuth bearer token into the HTTP headers (an explicit Authorization header wins). */
    private static Map<String, String> withOAuth(ServerSpec spec) {
        String token = configDir == null ? null : McpOAuth.tokenFor(spec.name(), configDir);
        if (token == null || token.isBlank()) return spec.headers();
        Map<String, String> merged = new LinkedHashMap<>(spec.headers());
        merged.putIfAbsent("Authorization", "Bearer " + token);
        return merged;
    }

    private static List<String> stdioCommand(ServerSpec spec) {
        List<String> cmd = new ArrayList<>();
        cmd.add(spec.command());
        cmd.addAll(spec.args());
        // On Windows npx/uvx/npm/… are .cmd scripts, not .exe — ProcessBuilder can't launch them
        // directly (CreateProcess needs an executable), so wrap the whole line in `cmd /c`. Skip
        // when the user already wrapped it, or is launching a real executable / interpreter path.
        if (isWindows() && needsCmdWrap(spec.command())) {
            List<String> wrapped = new ArrayList<>(cmd.size() + 2);
            wrapped.add("cmd");
            wrapped.add("/c");
            wrapped.addAll(cmd);
            return wrapped;
        }
        return cmd;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** True for the common Node/Python launchers that ship as .cmd shims on Windows. */
    private static boolean needsCmdWrap(String command) {
        if (command == null) return false;
        String c = command.toLowerCase();
        if (c.equals("cmd") || c.endsWith(".exe") || c.contains("\\") || c.contains("/")) return false;
        return switch (c) {
            case "npx", "npm", "node", "uvx", "uv", "pnpm", "yarn", "bunx", "bun", "deno", "python", "python3" -> true;
            default -> false;
        };
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}
