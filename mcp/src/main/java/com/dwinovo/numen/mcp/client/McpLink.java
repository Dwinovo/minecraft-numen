package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.client.auth.McpOAuth;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpHttpClientTransportAuthorizationErrorHandler;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 到一台 MCP 服务器的一条连接:官方 SDK 的同步客户端,传输是 Streamable HTTP 或 stdio 子进程(协议版本、会话、流的接续、
 * 进度都是 SDK 的)。这里补 SDK 不管的:
 * <ul>
 *   <li>请求头:条目里写的盖过一切;其次是钥匙串里它用的服务的 key(作为 Bearer);再其次是 OAuth 存下的令牌;</li>
 *   <li>登录:401/403 经 {@link McpOAuth} 补救再重发(见 {@code auth} 包);调用前令牌快过期先刷新;</li>
 *   <li>Windows 上 npx、uvx 这类 .cmd 垫片起不来,套一层 {@code cmd /c}。</li>
 * </ul>
 */
public final class McpLink implements McpCaller, AutoCloseable {

    private final McpServerSpec spec;
    private final McpSyncClient client;
    private final McpOAuth oauth;

    private McpLink(McpServerSpec spec, McpSyncClient client, McpOAuth oauth) {
        this.spec = spec;
        this.client = client;
        this.oauth = oauth;
    }

    /** 连接过程中宿主要听的。 */
    public record Hooks(Consumer<String> authUrl, Runnable authFinished, Consumer<List<McpSchema.Tool>> toolsChanged) {}

    /**
     * 连上并握手(阻塞)。
     *
     * @param serviceKey 钥匙串里这台服务器用的服务的 key;没有是 {@code null}(现取,换了 key 下一个请求就用新的)
     * @param oauth      登录;stdio 用不上
     */
    public static McpLink open(McpServerSpec spec, Supplier<String> serviceKey, McpOAuth oauth, String clientVersion,
                               Hooks hooks) {
        Duration connect = Duration.ofSeconds(spec.connectTimeoutSeconds());
        McpClientTransport transport = spec.isStdio() ? stdio(spec) : http(spec, serviceKey, oauth, hooks);
        // 要登录的服务器握手时会被 401 拦下,等人授权的时间也算在握手里
        Duration initialization = spec.isStdio() ? connect : connect.plusMillis(McpOAuth.AUTH_TIMEOUT_MS);
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(spec.callTimeoutSeconds()))
                .initializationTimeout(initialization)
                .clientInfo(new McpSchema.Implementation("numen", "Numen", clientVersion, null, null, null))
                .toolsChangeConsumer(hooks.toolsChanged())
                .build();
        try {
            client.initialize();
        } catch (RuntimeException failed) {
            client.close();
            throw failed;
        }
        return new McpLink(spec, client, oauth);
    }

    private static McpClientTransport stdio(McpServerSpec spec) {
        List<String> command = stdioCommand(spec);
        return new StdioClientTransport(ServerParameters.builder(command.get(0))
                .args(command.subList(1, command.size())).env(spec.env()).build(), McpJsonDefaults.getMapper());
    }

    private static McpClientTransport http(McpServerSpec spec, Supplier<String> serviceKey, McpOAuth oauth, Hooks hooks) {
        URI url = URI.create(spec.url());
        String endpoint = (url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath())
                + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
        return HttpClientStreamableHttpTransport.builder(url.getScheme() + "://" + url.getRawAuthority())
                .endpoint(endpoint)
                .connectTimeout(Duration.ofSeconds(spec.connectTimeoutSeconds()))
                .openConnectionOnStartup(true)
                .addHttpRequestCustomizer((builder, method, uri, body, context) ->
                        headers(spec, serviceKey, oauth).forEach(builder::setHeader))
                .authorizationErrorHandler(McpHttpClientTransportAuthorizationErrorHandler.fromSync(
                        (request, response, context) -> {
                            try {
                                return oauth.onAuthorizationError(spec, response.statusCode(),
                                        response.headers().allValues("WWW-Authenticate"), hooks.authUrl());
                            } catch (Exception failed) {
                                throw new IllegalStateException(failed.getMessage(), failed);
                            } finally {
                                hooks.authFinished().run();
                            }
                        }))
                .build();
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

    public McpServerSpec spec() {
        return spec;
    }

    /** 这台服务器现在有的全部工具(翻完所有页)。 */
    public List<McpSchema.Tool> tools() {
        List<McpSchema.Tool> all = new ArrayList<>();
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = cursor == null ? client.listTools() : client.listTools(cursor);
            all.addAll(page.tools());
            cursor = page.nextCursor();
        } while (cursor != null);
        return all;
    }

    /** 调一次工具(阻塞,最久 {@code call_timeout_seconds})。令牌快过期先刷新。 */
    @Override
    public Reply call(String tool, Map<String, Object> arguments) {
        if (spec.isHttp()) {
            oauth.refreshIfDue(spec);
        }
        return render(client.callTool(McpSchema.CallToolRequest.builder().name(tool).arguments(arguments).build()));
    }

    /** {@code tools/call} 的结果 → 给模型的一段文字(每个内容块一行)。 */
    static Reply render(McpSchema.CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        for (McpSchema.Content block : result.content()) {
            switch (block) {
                case McpSchema.TextContent text -> sb.append(text.text());
                // Embedded resources often carry text (a file's contents) — surface it to the model
                // rather than a bare placeholder; binary blobs fall back to a uri/mime note.
                case McpSchema.EmbeddedResource embedded -> sb.append(describe(embedded.resource()));
                case McpSchema.ResourceLink link -> sb.append("[resource ").append(link.uri()).append(']');
                case McpSchema.ImageContent image -> sb.append("[image ").append(image.mimeType()).append(']');
                case McpSchema.AudioContent audio -> sb.append("[audio ").append(audio.mimeType()).append(']');
                default -> sb.append(block);
            }
            sb.append('\n');
        }
        String joined = sb.toString().strip();
        if (joined.isEmpty()) {
            // No content (some servers only return structuredContent) — hand that back verbatim.
            joined = result.structuredContent() != null ? String.valueOf(result.structuredContent()) : "(no content)";
        }
        return new Reply(joined, Boolean.TRUE.equals(result.isError()));
    }

    private static String describe(McpSchema.ResourceContents resource) {
        if (resource instanceof McpSchema.TextResourceContents text) {
            return text.text();
        }
        String mime = resource.mimeType() == null ? "" : " " + resource.mimeType();
        return "[resource " + resource.uri() + mime + "]";
    }

    @Override
    public void close() {
        client.closeGracefully();
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
