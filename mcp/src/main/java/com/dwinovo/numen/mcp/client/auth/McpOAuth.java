package com.dwinovo.numen.mcp.client.auth;

import com.dwinovo.numen.mcp.client.McpServerSpec;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nimbusds.oauth2.sdk.AccessTokenResponse;
import com.nimbusds.oauth2.sdk.AuthorizationCode;
import com.nimbusds.oauth2.sdk.AuthorizationCodeGrant;
import com.nimbusds.oauth2.sdk.AuthorizationGrant;
import com.nimbusds.oauth2.sdk.AuthorizationRequest;
import com.nimbusds.oauth2.sdk.AuthorizationResponse;
import com.nimbusds.oauth2.sdk.AuthorizationSuccessResponse;
import com.nimbusds.oauth2.sdk.RefreshTokenGrant;
import com.nimbusds.oauth2.sdk.ResponseType;
import com.nimbusds.oauth2.sdk.Scope;
import com.nimbusds.oauth2.sdk.TokenRequest;
import com.nimbusds.oauth2.sdk.TokenResponse;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import com.nimbusds.oauth2.sdk.id.ClientID;
import com.nimbusds.oauth2.sdk.id.State;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import com.nimbusds.oauth2.sdk.pkce.CodeVerifier;
import com.nimbusds.oauth2.sdk.token.RefreshToken;
import com.nimbusds.oauth2.sdk.token.Tokens;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 接入要登录的 MCP 服务器(OAuth 2.1,MCP 2025-11-25 授权规范)。通用部分(授权请求与 PKCE、令牌端点、授权服务器元数据、动态注册、
 * 回调参数的解析)是 Nimbus 的;MCP 特有的几步在这里与同包的几个类里:
 * <ol>
 *   <li>资源元数据发现({@link Discovery}):401 的 {@code resource_metadata},没有就按规范探 well-known;</li>
 *   <li>客户端注册按规范的优先顺序({@link ClientRegistration});</li>
 *   <li>授权请求带 PKCE(S256)、{@code state} 与 {@code resource}(RFC 8707);回调带 {@code iss} 的与授权服务器的 issuer 对一遍
 *       (RFC 9207),对不上就拒绝;</li>
 *   <li>运行中 403 {@code insufficient_scope}:把已有的和缺的权限并在一起重新授权;</li>
 *   <li>令牌记着是哪个授权服务器(issuer)发的、给哪个资源的:服务器换了授权服务器,旧令牌作废,重新注册重新登录。</li>
 * </ol>
 *
 * <p>回调方式:一个临时的 {@code 127.0.0.1} 回调端点把收到的每个请求交给 {@link #complete};浏览器和游戏在同一台机器时这就够了。
 * 专用服务器上主人的浏览器落在<i>他自己的</i>回环地址,重定向打不开,主人把地址栏里的网址贴回来({@link #complete})。同一个机制,
 * 两种送网址的办法;进程自己从不需要浏览器。
 *
 * <p>令牌存在宿主给的 {@link CredentialStore}(游戏里是钥匙串),永不进日志。线程:阻塞的方法(授权、刷新)只在后台连接线程上调。
 */
public final class McpOAuth {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");
    /** 授权时等人最久多久。 */
    public static final long AUTH_TIMEOUT_MS = 5 * 60 * 1000;
    /** 离过期不到这么久就先刷新。 */
    private static final long REFRESH_MARGIN_MS = 60_000;
    /** 钥匙串里存这台服务器令牌的"服务名":文件名安全,和模型服务的 key 同住一本。 */
    private static final String KEY_PREFIX = "mcp-oauth.";

    /** 等着回调网址的授权,按 {@code state}。 */
    private static final Map<String, CompletableFuture<URI>> WAITING = new ConcurrentHashMap<>();

    /** 一个服务器存下的令牌。 */
    private record Stored(String accessToken, String refreshToken, long expiresAt, String tokenEndpoint, String issuer,
                          String clientId, String resource, List<String> scope) {}

    private final CredentialStore store;
    private final LongSupplier clock;
    private final Gson gson = new Gson();
    /** 已经为哪些(服务器、权限)升级过授权,同样的不再试:服务器照旧说权限不够就是真不够。 */
    private final Set<String> steppedUp = ConcurrentHashMap.newKeySet();

    public McpOAuth(CredentialStore store, LongSupplier clock) {
        this.store = store;
        this.clock = clock;
    }

    /** 这台服务器的令牌在钥匙串里叫什么。 */
    public static String keyOf(String serverName) {
        return KEY_PREFIX + serverName;
    }

    // ---- 给请求用的令牌 ----

    /** 这台服务器现在该带的 Bearer 令牌(不联网);没存过是 null。 */
    public String bearer(McpServerSpec spec) {
        Stored s = load(spec);
        return s == null ? null : s.accessToken();
    }

    /**
     * 令牌快过期就刷新(联网,阻塞)。
     *
     * @return 刷新后手里有可用的令牌:没存过令牌、或刷新不了(没有刷新令牌、被拒)是 false
     */
    public boolean refreshIfDue(McpServerSpec spec) {
        Stored s = load(spec);
        if (s == null) {
            return false;
        }
        if (clock.getAsLong() < s.expiresAt() - REFRESH_MARGIN_MS) {
            return true;
        }
        return refresh(spec, s);
    }

    private boolean refresh(McpServerSpec spec, Stored s) {
        if (s.refreshToken() == null) {
            return false;
        }
        try {
            Tokens tokens = tokenRequest(URI.create(s.tokenEndpoint()), new ClientID(s.clientId()),
                    new RefreshTokenGrant(new RefreshToken(s.refreshToken())), s.resource());
            save(spec, tokens, s.tokenEndpoint(), s.issuer(), s.clientId(), s.resource(), s.scope(), s.refreshToken());
            return true;
        } catch (Exception failed) {
            LOG.warn("[numen-mcp-oauth] '{}' token refresh failed: {}", spec.name(), failed.toString());
            return false;
        }
    }

    /** 忘掉这台服务器的登录(条目删了、或主人要重新登录)。 */
    public void forget(McpServerSpec spec) {
        store.remove(keyOf(spec.name()));
    }

    // ---- SDK 的授权错误钩子 ----

    /**
     * 请求被 401/403 拒了:能补救就补救,返回要不要重发。
     * 401:有刷新令牌先刷新;不行就走完整的授权。403 {@code insufficient_scope}:并上缺的权限重新授权(同样的只试一次);
     * 别的 403 补救不了。
     *
     * @param urlSink 授权要打开的网址交给谁
     */
    public boolean onAuthorizationError(McpServerSpec spec, int status, List<String> wwwAuthenticate,
                                        Consumer<String> urlSink) throws Exception {
        Challenge challenge = Challenge.parse(wwwAuthenticate);
        if (status == 403) {
            if (!challenge.insufficientScope()
                    || !steppedUp.add(spec.name() + " " + String.join(" ", challenge.scope()))) {
                return false;
            }
        } else if (status == 401) {
            Stored s = load(spec);
            if (s != null && refresh(spec, s)) {
                return true;
            }
        } else {
            return false;
        }
        authorize(spec, challenge, urlSink);
        return true;
    }

    // ---- 授权 ----

    /**
     * 走完整的授权并存下令牌。阻塞到回调网址送到({@link #complete})或 {@value #AUTH_TIMEOUT_MS} 毫秒。
     *
     * @param urlSink 拿到浏览器要打开的授权网址
     */
    public void authorize(McpServerSpec spec, Challenge challenge, Consumer<String> urlSink) throws Exception {
        Discovery.Resource resource = Discovery.resource(spec.url(), challenge.resourceMetadata());
        AuthorizationServerMetadata as = Discovery.authorizationServer(resource.issuer());
        if (as.getCodeChallengeMethods() == null || !as.getCodeChallengeMethods().contains(CodeChallengeMethod.S256)) {
            throw new IllegalStateException("the authorization server does not support PKCE (S256): MCP requires it");
        }
        Stored previous = load(spec);
        boolean sameServer = previous != null && previous.issuer().equals(as.getIssuer().getValue());
        if (previous != null && !sameServer) {
            forget(spec);   // 换了授权服务器:旧令牌不是它发的,作废
        }
        List<String> scopes = !challenge.scope().isEmpty() ? challenge.scope() : resource.scopesSupported();
        if (challenge.insufficientScope() && sameServer) {
            scopes = Challenge.merge(previous.scope(), challenge.scope());   // 已有的加缺的,不丢原来的
        }

        Callback callback = startCallbackServer();
        try {
            URI redirect = callback.uri();
            ClientID clientId = ClientRegistration.choose(spec, as, redirect);
            CodeVerifier verifier = new CodeVerifier();
            State state = new State();
            AuthorizationRequest.Builder request = new AuthorizationRequest.Builder(ResponseType.CODE, clientId)
                    .endpointURI(as.getAuthorizationEndpointURI())
                    .redirectionURI(redirect)
                    .state(state)
                    .codeChallenge(verifier, CodeChallengeMethod.S256)
                    .resources(URI.create(resource.uri()));
            if (!scopes.isEmpty()) {
                request.scope(new Scope(scopes.toArray(String[]::new)));
            }
            CompletableFuture<URI> arrival = new CompletableFuture<>();
            WAITING.put(state.getValue(), arrival);
            LOG.info("[numen-mcp-oauth] '{}' waits for authorization", spec.name());
            urlSink.accept(request.build().toURI().toString());

            URI answer;
            try {
                answer = arrival.get(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } finally {
                WAITING.remove(state.getValue());
            }
            AuthorizationResponse response = AuthorizationResponse.parse(answer);
            if (!response.indicatesSuccess()) {
                throw new IllegalStateException("authorization denied: " + response.toErrorResponse().getErrorObject());
            }
            checkIssuer(response, as);
            AuthorizationSuccessResponse success = response.toSuccessResponse();
            AuthorizationCode code = success.getAuthorizationCode();
            Tokens tokens = tokenRequest(as.getTokenEndpointURI(), clientId,
                    new AuthorizationCodeGrant(code, redirect, verifier), resource.uri());
            save(spec, tokens, as.getTokenEndpointURI().toString(), as.getIssuer().getValue(), clientId.getValue(),
                    resource.uri(), scopes, null);
            LOG.info("[numen-mcp-oauth] '{}' authorized", spec.name());
        } finally {
            callback.server().stop(0);
        }
    }

    /**
     * 回调里的 {@code iss}(RFC 9207)必须就是这台授权服务器的 issuer;授权服务器声明了会带 {@code iss} 的,没带就拒绝——
     * 不然混淆攻击者能把别家的授权码塞进来。
     */
    private static void checkIssuer(AuthorizationResponse response, AuthorizationServerMetadata as) {
        if (response.getIssuer() != null) {
            if (!response.getIssuer().equals(as.getIssuer())) {
                throw new IllegalStateException("the authorization response comes from " + response.getIssuer()
                        + ", not from " + as.getIssuer());
            }
        } else if (as.supportsAuthorizationResponseIssuerParam()) {
            throw new IllegalStateException("the authorization server promises an issuer on its responses but this one "
                    + "carries none");
        }
    }

    private static Tokens tokenRequest(URI endpoint, ClientID clientId, AuthorizationGrant grant, String resource)
            throws Exception {
        TokenRequest.Builder request = new TokenRequest.Builder(endpoint, clientId, grant);
        if (resource != null) {
            request.resources(URI.create(resource));
        }
        TokenResponse response = TokenResponse.parse(request.build().toHTTPRequest().send());
        if (!response.indicatesSuccess()) {
            throw new IllegalStateException("token endpoint refused: " + response.toErrorResponse().getErrorObject());
        }
        AccessTokenResponse ok = response.toSuccessResponse();
        return ok.getTokens();
    }

    /**
     * 关停时放弃所有还在等回调网址的授权:等的线程立刻结束,它起的回调端点随之停掉(不然要等满授权期限)。
     */
    public static void cancelAll() {
        WAITING.values().forEach(waiting -> waiting.completeExceptionally(new IllegalStateException("shutting down")));
    }

    /**
     * 一次授权的回调网址到了——重定向本身,或管理员贴回来的地址栏文字(整条网址,或只有问号后面那段)。{@code state} 认它属于哪次授权。
     *
     * @return 这个网址不是对哪次等着的授权的回答的理由;是回答就是 {@code null}
     */
    public static String complete(String callbackUrl) {
        try {
            URI uri = URI.create("http://127.0.0.1/callback?" + queryOf(callbackUrl));
            Map<String, List<String>> params = AuthorizationResponse.parseResponseParameters(uri);
            List<String> state = params.get("state");
            CompletableFuture<URI> waiting = state == null || state.isEmpty() ? null : WAITING.get(state.get(0));
            if (waiting == null) {
                return "no pending authorization matches this address";
            }
            waiting.complete(uri);
            return null;
        } catch (Exception unreadable) {
            return "this is not an authorization address";
        }
    }

    private static String queryOf(String url) {
        if (url == null) return "";
        String s = url.strip();
        int hash = s.indexOf('#');
        if (hash >= 0) s = s.substring(0, hash);
        int q = s.indexOf('?');
        return q >= 0 ? s.substring(q + 1) : s;
    }

    // ---- 回调端点 ----

    private record Callback(HttpServer server, URI uri) {}

    /** 回调页:浏览器里给人看的一句话(服务端没有语言环境,两种语言并列);转义了才放进 HTML。 */
    private static String page(String words) {
        String escaped = words.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><body style='font-family:sans-serif'>" + escaped + "</body></html>";
    }

    /** 回调地址:每个请求原样交给 {@link #complete},和管理员贴回来的网址是同一条路。 */
    private static Callback startCallbackServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/callback", (HttpExchange ex) -> {
            String problem = complete(ex.getRequestURI().toString());
            String html = page(problem == null
                    ? "Numen is authorized; you can close this page. / Numen 授权成功,可以关闭此页面。"
                    : "Authorization failed: " + problem + " / 授权失败:" + problem);
            byte[] out = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return new Callback(server, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/callback"));
    }

    // ---- 令牌存取 ----

    private void save(McpServerSpec spec, Tokens tokens, String tokenEndpoint, String issuer, String clientId,
                      String resource, List<String> scope, String previousRefresh) {
        long lifetime = tokens.getAccessToken().getLifetime();
        String refresh = tokens.getRefreshToken() != null ? tokens.getRefreshToken().getValue() : previousRefresh;
        Stored stored = new Stored(tokens.getAccessToken().getValue(), refresh,
                clock.getAsLong() + (lifetime > 0 ? lifetime : 3600) * 1000, tokenEndpoint, issuer, clientId, resource,
                scope);
        JsonObject o = new JsonObject();
        o.addProperty("access_token", stored.accessToken());
        if (stored.refreshToken() != null) o.addProperty("refresh_token", stored.refreshToken());
        o.addProperty("expires_at", stored.expiresAt());
        o.addProperty("token_endpoint", stored.tokenEndpoint());
        o.addProperty("issuer", stored.issuer());
        o.addProperty("client_id", stored.clientId());
        o.addProperty("resource", stored.resource());
        JsonArray scopes = new JsonArray();
        stored.scope().forEach(scopes::add);
        o.add("scope", scopes);
        store.put(keyOf(spec.name()), o.toString());
    }

    private Stored load(McpServerSpec spec) {
        String text = store.get(keyOf(spec.name()));
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            JsonObject o = gson.fromJson(text, JsonObject.class);
            List<String> scope = new ArrayList<>();
            if (o.has("scope") && o.get("scope").isJsonArray()) {
                o.getAsJsonArray("scope").forEach(e -> scope.add(e.getAsString()));
            }
            return new Stored(o.get("access_token").getAsString(),
                    o.has("refresh_token") ? o.get("refresh_token").getAsString() : null,
                    o.get("expires_at").getAsLong(), o.get("token_endpoint").getAsString(),
                    o.get("issuer").getAsString(), o.get("client_id").getAsString(), o.get("resource").getAsString(),
                    List.copyOf(scope));
        } catch (RuntimeException unreadable) {
            LOG.warn("[numen-mcp-oauth] unreadable credentials for '{}': {}", spec.name(), unreadable.toString());
            return null;
        }
    }
}
