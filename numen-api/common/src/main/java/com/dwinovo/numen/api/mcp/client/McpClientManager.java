package com.dwinovo.numen.api.mcp.client;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.mcp.client.McpClientConfig.ServerSpec;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 一处接进来的 MCP 服务器的连接:给定"该连着哪几台"({@link #reconcile}),它让实际连着的和这个一致——新的连上、没了的撤掉
 * (关掉子进程)、改了的重连、关着的不连——并把它们的工具登记进 {@link Tools} 给的地方,同伴的大脑下一轮就看得到。
 * 配置不在这里:真源是库里的文件({@link McpClientConfig#KIND}),谁改了库谁调 {@code reconcile}。
 *
 * <p>有两处这样的连接:服务器的(内置组,管理员配的,工具人人可见,登记进全局注册表),和每位玩家自己的(我的组,工具只有
 * 他的同伴看得到,登记在他名下)。每处一个 {@code McpClientManager}。它跑在大脑所在的线程上:登记表只在那里动,工具的结果也回到那里。
 *
 * <p>需要 OAuth 的服务器显示为 {@link Status#AUTHORIZING},带着浏览器要打开的 {@link ServerHandle#authUrl()};授权回调的网址经
 * {@link McpOAuth#complete} 回来。谁在看状态(管理员的页面)靠构造时给的 {@code changed} 回调知道每一次变化。
 * 连不上的记日志、标 {@link Status#FAILED},不会让 reconcile 抛出。JVM 关闭时关掉所有连接,免得 stdio 子进程成孤儿。
 */
public final class McpClientManager {

    /** Connection state of one configured server, surfaced to the panel. */
    public enum Status { DISABLED, CONNECTING, AUTHORIZING, CONNECTED, FAILED }

    /** 工具登记在哪:服务器的登记进全局注册表,玩家自己的登记在他名下。 */
    public interface Tools {
        void register(NumenTool tool);

        void remove(String name);
    }

    /** Live runtime state for one configured server (status + what it registered). */
    public static final class ServerHandle {
        private final String id;
        private final String name;
        private final String type;                 // "http" | "stdio" — for the panel badge
        private volatile Status status;
        private volatile McpTransport transport;   // set once connected; closed on disable
        private volatile List<String> toolNames = List.of();
        private volatile String error = "";
        private volatile String authUrl;   // set while AUTHORIZING

        ServerHandle(String id, String name, String type, Status status) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.status = status;
        }

        /** 库里的名字({@code 命名空间:名字})。 */
        public String id() { return id; }
        public String name() { return name; }
        public String type() { return type; }
        public Status status() { return status; }
        public int toolCount() { return toolNames.size(); }
        public List<String> toolNames() { return toolNames; }
        public String error() { return error; }
        /** The authorization URL a browser must open; {@code null} unless {@link Status#AUTHORIZING}. */
        public String authUrl() { return authUrl; }
    }

    /** 全 JVM 里还开着的连接处,关机钩子收尾用。 */
    private static final Set<McpClientManager> OPEN = ConcurrentHashMap.newKeySet();
    private static boolean hooked;

    private static final int MAX_CONNECT_ATTEMPTS = 3;

    private final Library.Group group;
    private final Executor main;
    private final Runnable onChange;
    private final Path stateDir;
    private final Tools tools;
    private final Function<String, String> keys;
    private final Map<String, ServerHandle> handles = new ConcurrentHashMap<>();
    /** 现在连着(或该连着)的各台怎么连,按库里的顺序;只在大脑所在的线程上动。 */
    private final Map<String, ServerSpec> specs = new LinkedHashMap<>();

    /**
     * @param group    这一处的连接属于哪一组(服务器的内置,还是玩家的我的):工具带着它,看得见看不见按它判
     * @param main     大脑所在的线程:登记表只在这里动,工具的结果也回到这里
     * @param changed  某台的状态变了通知(在 {@code main} 上调);可以是 {@code null}
     * @param stateDir 存 OAuth 令牌的目录
     * @param tools    工具登记在哪
     * @param keys     钥匙串里这个服务的 key(服务名 → key);没有是 {@code null}
     */
    public McpClientManager(Library.Group group, Executor main, Runnable changed, Path stateDir, Tools tools,
                            Function<String, String> keys) {
        this.group = group;
        this.main = main;
        this.onChange = changed;
        this.stateDir = stateDir;
        this.tools = tools;
        this.keys = keys;
        synchronized (McpClientManager.class) {
            OPEN.add(this);
            if (!hooked) {
                hooked = true;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> List.copyOf(OPEN).forEach(
                        McpClientManager::shutdown), "numen-mcp-client-shutdown"));
            }
        }
    }

    // ---- 让连着的和库一致 ----

    /**
     * 让实际连着的和 {@code desired}(库里的名字 → 怎么连)一致。没变的不碰;{@code enabled=false} 的不连。
     * 在大脑所在的线程上调。
     */
    public void reconcile(Map<String, ServerSpec> desired) {
        for (String id : List.copyOf(handles.keySet())) {
            if (!desired.containsKey(id)) {
                teardown(id);
            }
        }
        specs.keySet().retainAll(desired.keySet());
        int connecting = 0;
        for (Map.Entry<String, ServerSpec> e : desired.entrySet()) {
            String id = e.getKey();
            ServerSpec spec = e.getValue();
            ServerSpec before = specs.put(id, spec);
            ServerHandle handle = handles.get(id);
            if (handle != null && spec.equals(before)) {
                continue;   // 没变
            }
            if (handle != null) {
                teardown(id);   // 改了:撤掉旧的,按新的再来
            }
            boolean on = spec.enabled();
            handles.put(id, new ServerHandle(id, spec.name(), badge(spec), on ? Status.CONNECTING : Status.DISABLED));
            if (on) {
                spawnConnect(id, spec);
                connecting++;
            }
        }
        if (connecting > 0) {
            Constants.LOG.info("[numen-mcp-client] {} server(s) configured ({}), {} connecting", desired.size(), group,
                    connecting);
        }
        changed();
    }

    // ---- panel-facing state ----

    /** 所有配置着的服务器,按库里的顺序。 */
    public List<ServerHandle> servers() {
        List<ServerHandle> out = new ArrayList<>();
        for (String id : specs.keySet()) {
            ServerHandle h = handles.get(id);
            if (h != null) out.add(h);
        }
        return out;
    }

    public ServerHandle handle(String id) {
        return handles.get(id);
    }

    /** 这台服务器怎么连(地址、命令行等,给展开的那一行);没有是 null。 */
    public ServerSpec spec(String id) {
        return specs.get(id);
    }

    // ---- connect / teardown ----

    /** 撤掉一台:工具退出登记,连接关掉(杀子进程),句柄忘掉。 */
    private void teardown(String id) {
        ServerHandle handle = handles.remove(id);
        if (handle == null) return;
        handle.status = Status.DISABLED;   // 在飞的连接发现被撤了,自己放手
        runOnMain(() -> {
            for (String tool : handle.toolNames) tools.remove(tool);
            handle.toolNames = List.of();
            handle.authUrl = null;
            closeTransport(handle);
            Constants.LOG.info("[numen-mcp-client] '{}' disconnected", handle.name);
        });
    }

    private void spawnConnect(String id, ServerSpec spec) {
        Thread t = new Thread(() -> connectServer(id, spec), "numen-mcp-connect-" + spec.name());
        t.setDaemon(true);
        t.start();
    }

    /** Connect with retries for transient failures, running the OAuth flow once on a 401. */
    private void connectServer(String id, ServerSpec spec) {
        ServerHandle handle = handles.get(id);
        Exception last = null;
        boolean authTried = false;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            if (handle == null || handle.status == Status.DISABLED || handles.get(id) != handle) return;   // 撤了
            try {
                connectOnce(spec, handle);
                return;
            } catch (McpAuthRequired auth) {
                if (authTried) { last = auth; break; }   // token still rejected — stop
                authTried = true;
                Constants.LOG.info("[numen-mcp-client] '{}' requires OAuth — waiting for an authorization", spec.name());
                try {
                    McpOAuth.authorize(spec, auth.resourceMetadataUrl(), stateDir, url -> {
                        handle.authUrl = url;
                        handle.status = Status.AUTHORIZING;
                        changed();
                    });
                    handle.authUrl = null;
                    handle.status = Status.CONNECTING;
                    // token stored — the next connectOnce merges it in (see headers); no extra delay
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
        if (handles.get(id) == handle && handle.status != Status.DISABLED) {
            handle.transport = null;
            handle.authUrl = null;
            handle.status = Status.FAILED;
            handle.error = rootMessage(last);
            changed();
        }
    }

    /** One connect attempt: build transport, initialize, list tools, register. Throws on any failure. */
    private void connectOnce(ServerSpec spec, ServerHandle handle) throws Exception {
        long connectMs = spec.connectTimeoutSeconds() * 1000L;
        long callMs = spec.callTimeoutSeconds() * 1000L;
        McpTransport transport = null;
        try {
            transport = spec.isStdio()
                    ? new StdioMcpTransport(spec.name(), stdioCommand(spec), spec.env())
                    : new HttpMcpTransport(spec.url(), headers(spec), spec.connectTimeoutSeconds());

            McpClient client = new McpClient(spec.name(), transport);
            client.connect(connectMs);
            List<JsonObject> listed = client.listTools(connectMs);

            List<RemoteMcpTool> wrapped = wrap(handle, client, listed, callMs);
            handle.transport = transport;
            registerOnMainThread(wrapped, handle);
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
    private void onServerNotification(ServerSpec spec, McpClient client, ServerHandle handle, String method) {
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
    private void refreshTools(ServerSpec spec, McpClient client, ServerHandle handle) {
        Thread t = new Thread(() -> {
            try {
                long listMs = spec.connectTimeoutSeconds() * 1000L;
                long callMs = spec.callTimeoutSeconds() * 1000L;
                List<JsonObject> listed = client.listTools(listMs);
                List<RemoteMcpTool> wrapped = wrap(handle, client, listed, callMs);
                runOnMain(() -> {
                    if (handle.status != Status.CONNECTED) return;   // disabled meanwhile
                    for (String old : handle.toolNames) tools.remove(old);
                    // 只记真的注册上的:记多了,下次刷新会去 remove 一个不存在的名字
                    handle.toolNames = List.copyOf(registerEach(spec.name(), wrapped));
                    Constants.LOG.info("[numen-mcp-client] '{}' now {} tool(s)",
                            spec.name(), handle.toolNames.size());
                    changed();
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

    /**
     * 把 {@code tools/list} 的原始条目包成工具。
     *
     * <p>名字由 {@link McpToolName} 统一铸出来:同一批共用一个已用名集合,洗过之后撞车的
     * (例如 {@code my.server} 与 {@code my_server})在这里就分开了,不留到注册表去抛异常。
     */
    private List<RemoteMcpTool> wrap(ServerHandle handle, McpClient client, List<JsonObject> listed, long callMs) {
        List<RemoteMcpTool> wrapped = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        for (JsonObject def : listed) {
            if (def.has("name") && !def.get("name").isJsonNull()) {
                wrapped.add(new RemoteMcpTool(handle.name, handle.id, group, client, def, callMs, main, taken));
            }
        }
        return wrapped;
    }

    /**
     * 逐个注册,返回真的进了注册表的那些名字。
     *
     * <p>一个工具进不去不该带走整台服务器的其余工具——工具清单每轮都要发,少一个是少一个能力,
     * 全没了是这台服务器白接。
     */
    private List<String> registerEach(String server, List<RemoteMcpTool> remote) {
        List<String> registered = new ArrayList<>();
        for (RemoteMcpTool tool : remote) {
            try {
                tools.register(tool);
                registered.add(tool.name());
            } catch (RuntimeException ex) {
                Constants.LOG.warn("[numen-mcp-client] '{}' 跳过工具 {}: {}",
                        server, tool.name(), ex.getMessage());
            }
        }
        return registered;
    }

    private void registerOnMainThread(List<RemoteMcpTool> remote, ServerHandle handle) {
        runOnMain(() -> {
            // Disabled while this connect was in flight → tear the fresh connection back down.
            if (handle.status == Status.DISABLED) {
                closeTransport(handle);
                return;
            }
            handle.toolNames = List.copyOf(registerEach(handle.name, remote));
            handle.status = Status.CONNECTED;
            changed();
        });
    }

    /**
     * 关掉所有连接(杀 stdio 子进程)、把工具撤出登记表、忘掉每台服务器,下一次 {@link #reconcile} 从头来
     * (下一个世界,或下一个测试)。可以多次调用。
     */
    public synchronized void shutdown() {
        for (ServerHandle h : handles.values()) {
            closeTransport(h);
            for (String tool : h.toolNames) tools.remove(tool);
            h.toolNames = List.of();
            h.status = Status.DISABLED;
        }
        handles.clear();
        specs.clear();
        OPEN.remove(this);
    }

    /** Tell the watcher something changed; the callback runs on the brain's thread. */
    private void changed() {
        Runnable listener = onChange;
        if (listener != null) main.execute(listener);
    }

    // ---- helpers ----

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

    /** Marshal onto the brain's main thread (the executor runs it at once when already there). */
    private void runOnMain(Runnable r) {
        main.execute(r);
    }

    private static String badge(ServerSpec spec) {
        return spec.isStdio() ? "stdio" : "http";
    }

    /**
     * 连 http 服务器带的请求头:条目自己写的盖过一切;其次是钥匙串里它用的服务的 key(作为 Bearer);再其次是 OAuth 存下的令牌。
     * key 不进日志。
     */
    private Map<String, String> headers(ServerSpec spec) {
        Map<String, String> merged = new LinkedHashMap<>(spec.headers());
        String key = spec.service().isBlank() ? null : keys.apply(spec.service());
        if (key != null && !key.isBlank()) {
            merged.putIfAbsent("Authorization", "Bearer " + key);
        }
        String token = McpOAuth.tokenFor(spec.name(), stateDir);
        if (token != null && !token.isBlank()) {
            merged.putIfAbsent("Authorization", "Bearer " + token);
        }
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
