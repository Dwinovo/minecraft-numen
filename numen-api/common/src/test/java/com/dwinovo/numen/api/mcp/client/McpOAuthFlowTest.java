package com.dwinovo.numen.api.mcp.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 授权码 + PKCE 的整条流程对一台进程内的假授权服务器:服务端没有浏览器,授权网址交给 {@code urlSink},
 * code 只靠"回调网址"这一个机制回来——浏览器跳回本机的回调地址,和管理员把地址栏网址贴回去,走的是同一个 {@code complete}。
 */
class McpOAuthFlowTest {

    private HttpServer authServer;
    private String base;

    @BeforeEach
    void startAuthServer() throws Exception {
        authServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + authServer.getAddress().getPort();
        authServer.createContext("/.well-known/oauth-protected-resource",
                ex -> reply(ex, "{\"authorization_servers\":[\"" + base + "\"]}"));
        authServer.createContext("/.well-known/oauth-authorization-server", ex -> reply(ex, "{\"authorization_endpoint\":\""
                + base + "/authorize\",\"token_endpoint\":\"" + base + "/token\",\"registration_endpoint\":\"" + base
                + "/register\"}"));
        authServer.createContext("/register", ex -> reply(ex, "{\"client_id\":\"cid\"}"));
        authServer.createContext("/token", ex -> reply(ex,
                "{\"access_token\":\"tok\",\"refresh_token\":\"r\",\"expires_in\":3600}"));
        authServer.start();
    }

    @AfterEach
    void stopAuthServer() {
        authServer.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String json) throws java.io.IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private McpClientConfig.ServerSpec spec() {
        return new McpClientConfig.ServerSpec("files", "http", base + "/mcp", Map.of(), "", List.of(), Map.of(),
                "", true, 20, 120);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> out = new HashMap<>();
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            int i = pair.indexOf('=');
            out.put(pair.substring(0, i), URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
        }
        return out;
    }

    /** 起一次授权,等到授权网址交出来;返回(授权网址,授权任务)。 */
    private Started start(Path dir) throws Exception {
        CompletableFuture<String> url = new CompletableFuture<>();
        CompletableFuture<Void> done = CompletableFuture.runAsync(() -> {
            try {
                McpOAuth.authorize(spec(), null, dir, url::complete);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        return new Started(url.get(10, TimeUnit.SECONDS), done);
    }

    private record Started(String url, CompletableFuture<Void> done) {}

    @Test
    void theBrowsersRedirectToTheLocalCallbackFinishesTheAuthorization(@TempDir Path dir) throws Exception {
        Started auth = start(dir);
        Map<String, String> q = query(auth.url());
        assertEquals(base + "/authorize", auth.url().substring(0, auth.url().indexOf('?')));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals("cid", q.get("client_id"));

        HttpResponse<String> page = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(
                q.get("redirect_uri") + "?code=abc&state=" + q.get("state"))).build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, page.statusCode());
        auth.done().get(10, TimeUnit.SECONDS);
        assertEquals("tok", McpOAuth.tokenFor("files", dir));
    }

    @Test
    void theAddressBarUrlPastedBackFinishesTheSameAuthorization(@TempDir Path dir) throws Exception {
        Started auth = start(dir);
        Map<String, String> q = query(auth.url());

        // 专用服务器上浏览器跳回的是管理员自己机器的回环,页面打不开,地址栏里的网址贴回来
        assertNull(McpOAuth.complete(q.get("redirect_uri") + "?code=abc&state=" + q.get("state")));

        auth.done().get(10, TimeUnit.SECONDS);
        assertEquals("tok", McpOAuth.tokenFor("files", dir));
    }

    @Test
    void anAddressThatBelongsToNoPendingAuthorizationChangesNothing(@TempDir Path dir) throws Exception {
        Started auth = start(dir);
        Map<String, String> q = query(auth.url());

        assertNotNull(McpOAuth.complete("http://127.0.0.1:1/callback?code=abc&state=someone-elses"));
        assertTrue(!auth.done().isDone(), "别人的回调网址不该让这次授权收场");
        assertNull(McpOAuth.tokenFor("files", dir));

        assertNull(McpOAuth.complete("?code=abc&state=" + q.get("state")));   // 只贴查询串也行
        auth.done().get(10, TimeUnit.SECONDS);
    }
}
