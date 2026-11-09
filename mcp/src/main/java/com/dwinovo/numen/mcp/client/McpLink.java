package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.McpThreads;
import com.dwinovo.numen.mcp.client.auth.McpOAuth;
import com.dwinovo.numen.mcp.protocol.CallToolResult;
import com.dwinovo.numen.mcp.protocol.Content;
import com.dwinovo.numen.mcp.protocol.Implementation;
import com.dwinovo.numen.mcp.protocol.Tool;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 到一台接进来的 MCP 服务器的一条连接:按条目({@link McpServerSpec})配好传输(Streamable HTTP 或 stdio 子进程),在上面开一个
 * {@link McpClient}。这里补的是"接进来的服务器"这一层的事:
 * <ul>
 *   <li>请求头:条目里写的盖过一切;其次是钥匙串里它用的服务的 key(作为 Bearer);再其次是 OAuth 存下的令牌;</li>
 *   <li>登录:401/403 经 {@link McpOAuth} 补救再重发(传输的 {@link HttpClientTransport.Authorization});调用前令牌快过期先刷新;</li>
 *   <li>对方说工具表变了,重列一次全量交给宿主;</li>
 *   <li>结果的各种内容块变成给模型的一段文字;</li>
 *   <li>Windows 上 npx、uvx 这类 .cmd 垫片起不来,套一层 {@code cmd /c}。</li>
 * </ul>
 * 不声明 elicitation 等客户端能力:服务器发来的这类请求答"没有这个方法"。
 */
public final class McpLink implements McpCaller, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");

    private final McpServerSpec spec;
    private final McpOAuth oauth;
    private volatile McpClient client;

    private McpLink(McpServerSpec spec, McpOAuth oauth) {
        this.spec = spec;
        this.oauth = oauth;
    }

    /** 连接过程中宿主要听的。{@code toolsChanged} 收到的是重列之后的全量。 */
    public record Hooks(Consumer<String> authUrl, Runnable authFinished, Consumer<List<Tool>> toolsChanged) {}

    /**
     * 连上并握手(阻塞)。
     *
     * @param serviceKey 钥匙串里这台服务器用的服务的 key;没有是 {@code null}(现取,换了 key 下一个请求就用新的)
     * @param oauth      登录;stdio 用不上
     */
    public static McpLink open(McpServerSpec spec, Supplier<String> serviceKey, McpOAuth oauth, String clientVersion,
                               Hooks hooks) throws Exception {
        Duration connect = Duration.ofSeconds(spec.connectTimeoutSeconds());
        ClientTransport transport = spec.isStdio()
                ? new StdioClientTransport(stdioCommand(spec), spec.env())
                : new HttpClientTransport(URI.create(spec.url()), connect, authorization(spec, serviceKey, oauth, hooks));
        // 要登录的服务器握手时会被 401 拦下,等人授权的时间也算在握手里
        Duration initialization = spec.isStdio() ? connect : connect.plusMillis(McpOAuth.AUTH_TIMEOUT_MS);
        McpLink link = new McpLink(spec, oauth);
        link.client = McpClient.connect(transport, new Implementation("numen", "Numen", clientVersion, null, List.of()), null,
                () -> link.relist(hooks.toolsChanged()), Duration.ofSeconds(spec.callTimeoutSeconds()), initialization);
        return link;
    }

    private static HttpClientTransport.Authorization authorization(McpServerSpec spec, Supplier<String> serviceKey,
                                                                   McpOAuth oauth, Hooks hooks) {
        return new HttpClientTransport.Authorization() {
            @Override
            public Map<String, String> headers() {
                return McpLink.headers(spec, serviceKey, oauth);
            }

            @Override
            public boolean remedy(int status, List<String> wwwAuthenticate) throws Exception {
                try {
                    return oauth.onAuthorizationError(spec, status, wwwAuthenticate, hooks.authUrl());
                } finally {
                    hooks.authFinished().run();
                }
            }
        };
    }

    /** 请求头:条目自己写的盖过一切;其次是钥匙串里的 key;再其次是 OAuth 存下的令牌。key 与令牌不进日志。 */
    private static Map<String, String> headers(McpServerSpec spec, Supplier<String> serviceKey, McpOAuth oauth) {
        Map<String, String> merged = new LinkedHashMap<>(spec.headers());
        String key = spec.service().isBlank() ? null : serviceKey.get();
        if (key != null && !key.isBlank()) {
            merged.putIfAbsent("Authorization", "Bearer " + key);
        }
        String token = oauth.bearer(spec);
        if (token != null && !token.isBlank()) {
            merged.putIfAbsent("Authorization", "Bearer " + token);
        }
        return merged;
    }

    /** 对方说工具表变了:另起一条线程重列(收到通知的线程不许阻塞),全量交给宿主。 */
    private void relist(Consumer<List<Tool>> changed) {
        if (client == null) {
            return;   // 还在握手:连上后宿主会取全量
        }
        McpThreads.start("numen-mcp-relist-" + spec.name(), () -> {
            try {
                changed.accept(tools());
            } catch (Exception failed) {
                LOG.warn("[numen-mcp-client] '{}' said its tools changed but could not list them: {}", spec.name(),
                        failed.toString());
            }
        });
    }

    public McpServerSpec spec() {
        return spec;
    }

    /** 这台服务器现在有的全部工具(翻完所有页)。 */
    public List<Tool> tools() throws Exception {
        return client.listTools();
    }

    /** 调一次工具(阻塞,最久 {@code call_timeout_seconds})。令牌快过期先刷新。 */
    @Override
    public Reply call(String tool, JsonObject arguments) throws Exception {
        if (spec.isHttp()) {
            oauth.refreshIfDue(spec);
        }
        return render(client.callTool(tool, arguments));
    }

    /** {@code tools/call} 的结果 → 给模型的一段文字(每个内容块一行)。 */
    static Reply render(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (Content block : result.content()) {
            // Java 17 没有 switch 的类型模式:按密封的五种逐个判断,剩下的只能是音频
            if (block instanceof Content.Text text) {
                sb.append(text.text());
            } else if (block instanceof Content.Resource resource) {
                // 内嵌的资源常带着文本(一个文件的内容):交给模型的是文本本身,不是一个占位;二进制的退回网址与类型
                sb.append(resource.text() != null ? resource.text()
                        : "[resource " + resource.uri() + (resource.mimeType() == null ? "" : " " + resource.mimeType()) + "]");
            } else if (block instanceof Content.ResourceLink link) {
                sb.append("[resource ").append(link.uri()).append(']');
            } else if (block instanceof Content.Image image) {
                sb.append("[image ").append(image.mimeType()).append(']');
            } else {
                Content.Audio audio = (Content.Audio) block;
                sb.append("[audio ").append(audio.mimeType()).append(']');
            }
            sb.append('\n');
        }
        String joined = sb.toString().strip();
        if (joined.isEmpty()) {
            // 没有内容块(有的服务器只给 structuredContent):原样交回
            joined = result.structuredContent() != null ? result.structuredContent().toString() : "(no content)";
        }
        return new Reply(joined, result.isError());
    }

    @Override
    public void close() {
        client.close();
    }

    /** 起 stdio 子进程的命令行;Windows 上 npx、uvx 这类 .cmd 垫片要套 {@code cmd /c}。 */
    static List<String> stdioCommand(McpServerSpec spec) {
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
}
