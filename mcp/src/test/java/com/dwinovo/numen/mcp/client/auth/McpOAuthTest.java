package com.dwinovo.numen.mcp.client.auth;

import com.dwinovo.numen.mcp.client.McpServerSpec;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 登录 MCP 服务器的整条路,对着一个假的授权服务器(JDK 的 HttpServer):资源元数据发现、按规范顺序选客户端 ID(配置的 → 客户端 ID
 * 元数据文档 → 动态注册 → 明说去配置)、PKCE 与 resource 随授权请求和令牌请求、回调的 iss 校验、权限的取法与升级、刷新、
 * 授权服务器换了重新来。每条在对应的东西坏掉时变红。
 */
class McpOAuthTest {

    private HttpServer server;
    private String base;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Map<String, String> vault = new HashMap<>();
    private final CredentialStore store = new CredentialStore() {
        @Override
        public String get(String key) {
            return vault.get(key);
        }

        @Override
        public void put(String key, String value) {
            vault.put(key, value);
        }

        @Override
        public void remove(String key) {
            vault.remove(key);
        }
    };
    private final McpOAuth oauth = new McpOAuth(store, now::get);

    // 假授权服务器的开关与见闻
    private boolean cimd;
    private boolean dynamicRegistration = true;
    private boolean promisesIss;
    private String advertisedIssuer;
    private List<String> scopesSupported = List.of("read", "write");
    private final List<String> registrations = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> tokenRequests = new CopyOnWriteArrayList<>();
    private volatile String challengeOfLastAuthorization;
    private volatile int refreshCount;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        advertisedIssuer = base + "/as";
        server.createContext("/.well-known/oauth-protected-resource", ex -> json(ex, resourceMetadata()));
        server.createContext("/.well-known/oauth-authorization-server", ex -> json(ex, asMetadata()));
        server.createContext("/as/register", ex -> {
            registrations.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            JsonObject out = new JsonObject();
            out.addProperty("client_id", "registered-client");
            json(ex, out, 201);
        });
        server.createContext("/as/token", this::token);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        McpOAuth.cancelAll();
    }

    private McpServerSpec spec(String clientId) {
        return new McpServerSpec("files", "http", base + "/mcp", Map.of(), "", List.of(), Map.of(), "", clientId, true, 5, 5);
    }

    // ---- 假服务器 ----

    private JsonObject resourceMetadata() {
        JsonObject o = new JsonObject();
        o.addProperty("resource", base + "/mcp");
        JsonArray servers = new JsonArray();
        servers.add(advertisedIssuer);
        o.add("authorization_servers", servers);
        JsonArray scopes = new JsonArray();
        scopesSupported.forEach(scopes::add);
        o.add("scopes_supported", scopes);
        return o;
    }

    private JsonObject asMetadata() {
        JsonObject o = new JsonObject();
        o.addProperty("issuer", advertisedIssuer);
        o.addProperty("authorization_endpoint", advertisedIssuer + "/authorize");
        o.addProperty("token_endpoint", advertisedIssuer + "/token");
        JsonArray methods = new JsonArray();
        methods.add("S256");
        o.add("code_challenge_methods_supported", methods);
        JsonArray responses = new JsonArray();
        responses.add("code");
        o.add("response_types_supported", responses);
        if (dynamicRegistration) {
            o.addProperty("registration_endpoint", advertisedIssuer + "/register");
        }
        if (cimd) {
            o.addProperty("client_id_metadata_document_supported", true);
        }
        if (promisesIss) {
            o.addProperty("authorization_response_iss_parameter_supported", true);
        }
        return o;
    }

    private void token(HttpExchange ex) throws IOException {
        Map<String, String> form = query(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        tokenRequests.add(form);
        JsonObject out = new JsonObject();
        if ("refresh_token".equals(form.get("grant_type"))) {
            refreshCount++;
            out.addProperty("access_token", "refreshed-" + refreshCount);
        } else {
            String expected = challengeOfLastAuthorization;
            if (expected == null || !expected.equals(s256(form.get("code_verifier")))) {
                json(ex, error("invalid_grant"), 400);
                return;
            }
            out.addProperty("access_token", "first-token");
            out.addProperty("refresh_token", "the-refresh");
        }
        out.addProperty("token_type", "Bearer");
        out.addProperty("expires_in", 3600);
        json(ex, out);
    }

    private static JsonObject error(String code) {
        JsonObject o = new JsonObject();
        o.addProperty("error", code);
        return o;
    }

    private static void json(HttpExchange ex, JsonObject body) throws IOException {
        json(ex, body, 200);
    }

    private static void json(HttpExchange ex, JsonObject body, int status) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                out.put(URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    /** 授权网址到了:记下 PKCE 挑战,然后扮浏览器——带着授权码回到回调地址。 */
    private Consumer<String> browser(String iss, List<Map<String, String>> seen) {
        return url -> {
            Map<String, String> q = query(URI.create(url).getRawQuery());
            seen.add(q);
            challengeOfLastAuthorization = q.get("code_challenge");
            String back = q.get("redirect_uri") + "?code=abc&state=" + URLEncoder.encode(q.get("state"), StandardCharsets.UTF_8)
                    + (iss == null ? "" : "&iss=" + URLEncoder.encode(iss, StandardCharsets.UTF_8));
            assertNull(McpOAuth.complete(back), "回调网址该被认作一次等着的授权的回答");
        };
    }

    // ---- 客户端 ID 的优先顺序 ----

    @Test
    void aClientIdMetadataDocumentIsUsedWhenTheServerSupportsItAndNothingIsRegistered() throws Exception {
        cimd = true;
        List<Map<String, String>> seen = new ArrayList<>();

        oauth.authorize(spec(""), Challenge.NONE, browser(advertisedIssuer, seen));

        assertEquals(ClientRegistration.CLIENT_METADATA_URL, seen.get(0).get("client_id"));
        assertTrue(registrations.isEmpty(), "有元数据文档就不注册");
        assertEquals("first-token", oauth.bearer(spec("")));
    }

    @Test
    void dynamicRegistrationIsTheFallbackAndSendsTheLoopbackRedirect() throws Exception {
        List<Map<String, String>> seen = new ArrayList<>();

        oauth.authorize(spec(""), Challenge.NONE, browser(advertisedIssuer, seen));

        assertEquals("registered-client", seen.get(0).get("client_id"));
        assertEquals(1, registrations.size());
        JsonObject sent = JsonParser.parseString(registrations.get(0)).getAsJsonObject();
        assertEquals(seen.get(0).get("redirect_uri"), sent.getAsJsonArray("redirect_uris").get(0).getAsString());
        assertTrue(seen.get(0).get("redirect_uri").startsWith("http://127.0.0.1:"), "回环地址,端口临时");
        assertEquals("none", sent.get("token_endpoint_auth_method").getAsString(), "公开客户端,靠 PKCE");
    }

    @Test
    void aConfiguredClientIdBeatsEverythingElse() throws Exception {
        cimd = true;
        List<Map<String, String>> seen = new ArrayList<>();

        oauth.authorize(spec("mine"), Challenge.NONE, browser(advertisedIssuer, seen));

        assertEquals("mine", seen.get(0).get("client_id"));
        assertTrue(registrations.isEmpty());
    }

    @Test
    void withNoWayToGetAClientIdTheOwnerIsToldToConfigureOne() {
        dynamicRegistration = false;

        Exception refused = assertThrows(IllegalStateException.class,
                () -> oauth.authorize(spec(""), Challenge.NONE, url -> { }));

        assertTrue(refused.getMessage().contains("client_id"), refused.getMessage());
    }

    // ---- 授权请求与令牌请求 ----

    @Test
    void pkceAndTheResourceTravelWithBothRequestsAndTheTokenIsStored() throws Exception {
        List<Map<String, String>> seen = new ArrayList<>();

        oauth.authorize(spec(""), Challenge.NONE, browser(advertisedIssuer, seen));

        Map<String, String> authorization = seen.get(0);
        assertEquals("S256", authorization.get("code_challenge_method"));
        assertEquals(base + "/mcp", authorization.get("resource"), "规范资源地址:没有末尾斜杠");
        assertEquals("code", authorization.get("response_type"));
        Map<String, String> exchange = tokenRequests.get(0);
        assertEquals("authorization_code", exchange.get("grant_type"));
        assertEquals(base + "/mcp", exchange.get("resource"));
        assertEquals(authorization.get("redirect_uri"), exchange.get("redirect_uri"));
        assertEquals("first-token", oauth.bearer(spec("")));
    }

    @Test
    void theScopeComesFromTheChallengeThenFromWhatTheResourceSupports() throws Exception {
        List<Map<String, String>> seen = new ArrayList<>();
        oauth.authorize(spec(""), Challenge.parse(List.of("Bearer scope=\"write\"")), browser(advertisedIssuer, seen));
        assertEquals("write", seen.get(0).get("scope"));

        seen.clear();
        oauth.authorize(spec(""), Challenge.NONE, browser(advertisedIssuer, seen));
        assertEquals("read write", seen.get(0).get("scope"));
    }

    @Test
    void anInsufficientScopeReauthorizesWithWhatItHadPlusWhatIsMissing() throws Exception {
        List<Map<String, String>> seen = new ArrayList<>();
        oauth.authorize(spec(""), Challenge.parse(List.of("Bearer scope=\"read\"")), browser(advertisedIssuer, seen));

        boolean retry = oauth.onAuthorizationError(spec(""), 403,
                List.of("Bearer error=\"insufficient_scope\", scope=\"files:write\""), browser(advertisedIssuer, seen));

        assertTrue(retry);
        assertEquals("read files:write", seen.get(1).get("scope"));
        boolean again = oauth.onAuthorizationError(spec(""), 403,
                List.of("Bearer error=\"insufficient_scope\", scope=\"files:write\""), browser(advertisedIssuer, seen));
        assertFalse(again, "同样的权限已经要过一次:服务器仍说不够就是真不够");
    }

    // ---- 回调的校验 ----

    @Test
    void aCallbackFromAnotherIssuerIsRefused() {
        Exception refused = assertThrows(Exception.class,
                () -> oauth.authorize(spec(""), Challenge.NONE, browser("http://evil.example/as", new ArrayList<>())));

        assertTrue(rootMessage(refused).contains("evil.example"), rootMessage(refused));
        assertNull(oauth.bearer(spec("")), "拒绝了就不换令牌");
    }

    @Test
    void aCallbackWithoutIssIsRefusedWhenTheServerPromisesOne() {
        promisesIss = true;

        Exception refused = assertThrows(Exception.class,
                () -> oauth.authorize(spec(""), Challenge.NONE, browser(null, new ArrayList<>())));

        assertTrue(rootMessage(refused).contains("issuer"), rootMessage(refused));
        assertTrue(tokenRequests.isEmpty());
    }

    @Test
    void anAddressThatMatchesNoPendingAuthorizationIsRefusedWithAReason() {
        assertNotNull(McpOAuth.complete("http://127.0.0.1:1/callback?code=abc&state=someone-elses"));
        assertNotNull(McpOAuth.complete("not an authorization address at all"));
    }

    // ---- 刷新与换授权服务器 ----

    @Test
    void aTokenNearExpiryIsRefreshedAndTheRefreshTokenIsKept() throws Exception {
        oauth.authorize(spec(""), Challenge.NONE, browser(advertisedIssuer, new ArrayList<>()));
        assertTrue(oauth.refreshIfDue(spec("")), "还新着,不用刷新");
        assertEquals(0, refreshCount);

        now.addAndGet(3_600_000L - 30_000L);   // 离过期不到一分钟
        assertTrue(oauth.refreshIfDue(spec("")));

        assertEquals(1, refreshCount);
        assertEquals("refreshed-1", oauth.bearer(spec("")));
        now.addAndGet(3_600_000L - 30_000L);
        assertTrue(oauth.refreshIfDue(spec("")), "刷新令牌留着,下次还能刷");
        assertEquals("refreshed-2", oauth.bearer(spec("")));
    }

    @Test
    void whenTheAuthorizationServerChangesTheOldTokenIsDroppedAndTheNewOneIsRegisteredAnew() throws Exception {
        oauth.authorize(spec(""), Challenge.NONE, browser(advertisedIssuer, new ArrayList<>()));
        assertEquals(1, registrations.size());

        advertisedIssuer = base + "/as2";
        server.createContext("/.well-known/oauth-authorization-server/as2", ex -> json(ex, asMetadata()));
        server.createContext("/as2/register", ex -> {
            registrations.add("second");
            JsonObject out = new JsonObject();
            out.addProperty("client_id", "registered-with-the-second");
            json(ex, out, 201);
        });
        server.createContext("/as2/token", this::token);
        List<Map<String, String>> seen = new ArrayList<>();
        oauth.authorize(spec(""), Challenge.NONE, browser(advertisedIssuer, seen));

        assertEquals("registered-with-the-second", seen.get(0).get("client_id"));
        assertEquals(2, registrations.size());
    }

    @Test
    void forgettingRemovesTheStoredToken() throws Exception {
        oauth.authorize(spec(""), Challenge.NONE, browser(advertisedIssuer, new ArrayList<>()));

        oauth.forget(spec(""));

        assertNull(oauth.bearer(spec("")));
        assertTrue(vault.isEmpty(), "钥匙串里不留");
    }

    // ---- WWW-Authenticate ----

    @Test
    void theChallengeIsReadFromTheBearerHeader() {
        Challenge c = Challenge.parse(List.of("Basic realm=\"x\"",
                "Bearer realm=\"numen\", resource_metadata=\"https://h/.well-known/oauth-protected-resource/mcp\", "
                        + "error=\"insufficient_scope\", scope=\"a b, c\""));

        assertEquals("https://h/.well-known/oauth-protected-resource/mcp", c.resourceMetadata());
        assertEquals(List.of("a", "b,", "c"), c.scope(), "scope 按空白切");
        assertTrue(c.insufficientScope());
        assertEquals(Challenge.NONE, Challenge.parse(List.of("Basic realm=\"x\"")));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return String.valueOf(t.getMessage()) + " / " + cur.getMessage();
    }
}
