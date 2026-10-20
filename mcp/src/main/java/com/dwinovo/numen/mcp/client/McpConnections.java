package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.client.auth.McpOAuth;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * 一处接进来的 MCP 服务器的连接:给定"该连着哪几台"({@link #reconcile}),它让实际连着的和这个一致——新的连上、没了的撤掉
 * (关掉子进程)、改了的重连、关着的不连——并把它们的工具交给 {@link Registry},同伴的大脑下一轮就看得到。
 * 配置不在这里:谁改了库谁调 {@code reconcile}。
 *
 * <p>它跑在 {@code main}(大脑所在的线程)上:登记表只在那里动,工具的结果也回到那里。
 *
 * <p>需要登录的服务器显示为 {@link Status#AUTHORIZING},带着浏览器要打开的 {@link ServerHandle#authUrl()};授权回调的网址经
 * {@link McpOAuth#complete} 回来。谁在看状态(管理员的页面)靠构造时给的 {@code changed} 回调知道每一次变化。
 * 连不上的记日志、标 {@link Status#FAILED},不会让 reconcile 抛出。JVM 关闭时关掉所有连接,免得 stdio 子进程成孤儿。
 */
public final class McpConnections {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");

    /** Connection state of one configured server, surfaced to the panel. */
    public enum Status { DISABLED, CONNECTING, AUTHORIZING, CONNECTED, FAILED }

    /** 工具登记在哪(宿主的事:什么名字、给谁看);这里只管把连着的服务器的工具交出去、撤回来。 */
    public interface Registry {
        /**
         * 这台服务器的工具(全量)到了:登记。
         *
         * @return 真登记上的名字;一个进不去不该带走其余的
         */
        List<String> register(ServerHandle server, McpLink link, List<McpSchema.Tool> tools);

        /** 撤掉这些名字的工具。 */
        void remove(List<String> names);
    }

    /** Live runtime state for one configured server (status + what it registered). */
    public static final class ServerHandle {
        private final String id;
        private final String name;
        private final String type;                 // "http" | "stdio" — for the panel badge
        private volatile Status status;
        private volatile McpLink link;             // set once connected; closed on disable
        private volatile List<String> toolNames = List.of();
        private volatile String error = "";
        private volatile String authUrl;           // set while AUTHORIZING
        private volatile boolean authAttempted;

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
    private static final Set<McpConnections> OPEN = ConcurrentHashMap.newKeySet();
    private static boolean hooked;

    private static final int MAX_CONNECT_ATTEMPTS = 3;

    private final Executor main;
    private final Runnable onChange;
    private final McpOAuth oauth;
    private final Registry registry;
    private final Function<String, String> keys;
    private final String clientVersion;
    private final Map<String, ServerHandle> handles = new ConcurrentHashMap<>();
    /** 现在连着(或该连着)的各台怎么连,按库里的顺序;只在大脑所在的线程上动。 */
    private final Map<String, McpServerSpec> specs = new LinkedHashMap<>();

    /**
     * @param main          大脑所在的线程:登记表只在这里动,工具的结果也回到这里
     * @param changed       某台的状态变了通知(在 {@code main} 上调);可以是 {@code null}
     * @param oauth         登录;令牌存哪由它的 {@code CredentialStore} 定
     * @param keys          钥匙串里这个服务的 key(服务名 → key);没有是 {@code null}
     * @param clientVersion 对服务器自报的版本(Numen API 的模组版本)
     */
    public McpConnections(Executor main, Runnable changed, McpOAuth oauth, Registry registry,
                          Function<String, String> keys, String clientVersion) {
        this.main = main;
        this.onChange = changed;
        this.oauth = oauth;
        this.registry = registry;
        this.keys = keys;
        this.clientVersion = clientVersion;
        synchronized (McpConnections.class) {
            OPEN.add(this);
            if (!hooked) {
                hooked = true;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> List.copyOf(OPEN).forEach(
                        McpConnections::shutdown), "numen-mcp-client-shutdown"));
            }
        }
    }

    // ---- 让连着的和库一致 ----

    /**
     * 让实际连着的和 {@code desired}(库里的名字 → 怎么连)一致。没变的不碰;{@code enabled=false} 的不连。
     * 在大脑所在的线程上调。
     */
    public void reconcile(Map<String, McpServerSpec> desired) {
        for (String id : List.copyOf(handles.keySet())) {
            if (!desired.containsKey(id)) {
                teardown(id);
            }
        }
        specs.keySet().retainAll(desired.keySet());
        int connecting = 0;
        for (Map.Entry<String, McpServerSpec> e : desired.entrySet()) {
            String id = e.getKey();
            McpServerSpec spec = e.getValue();
            McpServerSpec before = specs.put(id, spec);
            ServerHandle handle = handles.get(id);
            if (handle != null && spec.equals(before)) {
                continue;   // 没变
            }
            if (handle != null) {
                teardown(id);   // 改了:撤掉旧的,按新的再来
            }
            boolean on = spec.enabled();
            handles.put(id, new ServerHandle(id, spec.name(), spec.isStdio() ? "stdio" : "http",
                    on ? Status.CONNECTING : Status.DISABLED));
            if (on) {
                spawnConnect(id, spec);
                connecting++;
            }
        }
        if (connecting > 0) {
            LOG.info("[numen-mcp-client] {} server(s) configured, {} connecting", desired.size(), connecting);
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
    public McpServerSpec spec(String id) {
        return specs.get(id);
    }

    // ---- connect / teardown ----

    /** 撤掉一台:工具退出登记,连接关掉(杀子进程),句柄忘掉。 */
    private void teardown(String id) {
        ServerHandle handle = handles.remove(id);
        if (handle == null) return;
        handle.status = Status.DISABLED;   // 在飞的连接发现被撤了,自己放手
        runOnMain(() -> {
            registry.remove(handle.toolNames);
            handle.toolNames = List.of();
            handle.authUrl = null;
            closeLink(handle);
            LOG.info("[numen-mcp-client] '{}' disconnected", handle.name);
        });
    }

    private void spawnConnect(String id, McpServerSpec spec) {
        Thread t = new Thread(() -> connectServer(id, spec), "numen-mcp-connect-" + spec.name());
        t.setDaemon(true);
        t.start();
    }

    /** 连接,临时的失败重试几次;要登录的那一圈由 SDK 的授权钩子在握手里完成,登录失败不重试。 */
    private void connectServer(String id, McpServerSpec spec) {
        ServerHandle handle = handles.get(id);
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_CONNECT_ATTEMPTS; attempt++) {
            if (handle == null || handle.status == Status.DISABLED || handles.get(id) != handle) return;   // 撤了
            try {
                connectOnce(spec, handle);
                return;
            } catch (Exception ex) {
                last = ex;
                LOG.warn("[numen-mcp-client] '{}' connect attempt {}/{} failed: {}",
                        spec.name(), attempt, MAX_CONNECT_ATTEMPTS, ex.toString());
                if (handle.authAttempted || attempt == MAX_CONNECT_ATTEMPTS) break;
                sleepQuiet(1500);
            }
        }
        if (handles.get(id) == handle && handle.status != Status.DISABLED) {
            handle.link = null;
            handle.authUrl = null;
            handle.status = Status.FAILED;
            handle.error = rootMessage(last);
            changed();
        }
    }

    /** One connect attempt: open the link, register its tools. Throws on any failure. */
    private void connectOnce(McpServerSpec spec, ServerHandle handle) {
        McpLink link = McpLink.open(spec, () -> keys.apply(spec.service()), oauth, clientVersion,
                new McpLink.Hooks(url -> {
                    handle.authUrl = url;
                    handle.status = Status.AUTHORIZING;
                    changed();
                }, () -> {
                    handle.authAttempted = true;
                    handle.authUrl = null;
                    if (handle.status == Status.AUTHORIZING) handle.status = Status.CONNECTING;
                }, tools -> onToolsChanged(handle, tools)));
        try {
            List<McpSchema.Tool> tools = link.tools();
            handle.link = link;
            registerOnMain(link, handle, tools);
            LOG.info("[numen-mcp-client] '{}' connected — {} tool(s)", spec.name(), tools.size());
        } catch (RuntimeException ex) {
            link.close();
            throw ex;
        }
    }

    /** 对方说工具表变了(SDK 已经重新列过):换掉登记的。 */
    private void onToolsChanged(ServerHandle handle, List<McpSchema.Tool> tools) {
        McpLink link = handle.link;
        if (link == null) return;   // 还没连完:连完时会取全量
        runOnMain(() -> {
            if (handle.status != Status.CONNECTED) return;   // disabled meanwhile
            registry.remove(handle.toolNames);
            handle.toolNames = List.copyOf(registry.register(handle, link, tools));
            LOG.info("[numen-mcp-client] '{}' now {} tool(s)", handle.name, handle.toolNames.size());
            changed();
        });
    }

    private void registerOnMain(McpLink link, ServerHandle handle, List<McpSchema.Tool> tools) {
        runOnMain(() -> {
            // Disabled while this connect was in flight → tear the fresh connection back down.
            if (handle.status == Status.DISABLED) {
                closeLink(handle);
                return;
            }
            handle.toolNames = List.copyOf(registry.register(handle, link, tools));
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
            closeLink(h);
            registry.remove(h.toolNames);
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

    private static void closeLink(ServerHandle handle) {
        McpLink l = handle.link;
        handle.link = null;
        if (l == null) return;
        try {
            l.close();
        } catch (RuntimeException ex) {
            LOG.debug("[numen-mcp-client] close failed: {}", ex.toString());
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /** Marshal onto the brain's main thread (the executor runs it at once when already there). */
    private void runOnMain(Runnable r) {
        main.execute(r);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }
}
