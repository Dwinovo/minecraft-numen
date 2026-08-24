package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.Constants;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * OAuth 2.1 authorization for OAuth-protected MCP HTTP servers, implementing the flow from the
 * MCP 2025-06-18 authorization spec with JDK built-ins only (zero new deps):
 *
 * <ol>
 *   <li>Protected Resource Metadata discovery (RFC 9728) — from the {@code WWW-Authenticate}
 *       header's {@code resource_metadata} URL, or {@code /.well-known/oauth-protected-resource}.</li>
 *   <li>Authorization Server Metadata discovery (RFC 8414 / OpenID Connect discovery).</li>
 *   <li>Dynamic Client Registration (RFC 7591) to obtain a {@code client_id}.</li>
 *   <li>PKCE (S256) + the {@code resource} indicator (RFC 8707).</li>
 *   <li>Browser authorization ({@code Util.openUri}) with a loopback redirect caught by a
 *       throwaway {@link HttpServer} on {@code 127.0.0.1}.</li>
 *   <li>Authorization-code → token exchange, and refresh-token renewal.</li>
 * </ol>
 *
 * <p>Tokens are persisted per server in {@code config/numen/mcp_oauth.json}. {@link #tokenFor}
 * returns a valid bearer token (refreshing near expiry); {@link #authorize} runs the interactive
 * flow when there is none. Blocking — always called from a background connect thread.
 *
 * <p>Limitation: if the authorization server offers no dynamic registration and no client id is
 * pre-configured, the flow fails with a clear message (a pre-registered client id would be needed).
 */
final class McpOAuth {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();
    private static final long AUTH_TIMEOUT_MS = 5 * 60 * 1000;   // wait up to 5 min for the user
    private static final String STORE_FILE = "mcp_oauth.json";

    private McpOAuth() {}

    // ---- public API ----

    /** Run the full flow for a server (opens the browser) and persist the token. Blocking. */
    static void authorize(McpClientConfig.ServerSpec spec, String resourceMetadataUrl, Path configDir)
            throws Exception {
        String serverUrl = spec.url();
        String canonicalResource = canonicalResource(serverUrl);

        // 1. Protected Resource Metadata → authorization server URL.
        String prmUrl = resourceMetadataUrl != null && !resourceMetadataUrl.isBlank()
                ? resourceMetadataUrl : wellKnown(serverUrl, "oauth-protected-resource");
        JsonObject prm = getJson(prmUrl);
        String asUrl = firstString(prm, "authorization_servers");
        if (asUrl == null) throw new IllegalStateException("no authorization_servers in resource metadata");

        // 2. Authorization Server Metadata.
        JsonObject asMeta = fetchAsMetadata(asUrl);
        String authEndpoint = str(asMeta, "authorization_endpoint");
        String tokenEndpoint = str(asMeta, "token_endpoint");
        String regEndpoint = str(asMeta, "registration_endpoint");
        if (authEndpoint == null || tokenEndpoint == null) {
            throw new IllegalStateException("authorization/token endpoint missing in AS metadata");
        }

        Callback cb = startCallbackServer();
        try {
            String redirectUri = "http://127.0.0.1:" + cb.port + "/callback";

            // 3. Dynamic client registration.
            String clientId = registerClient(regEndpoint, redirectUri);
            if (clientId == null) {
                throw new IllegalStateException("authorization server offers no dynamic client "
                        + "registration and no client id is configured");
            }

            // 4. PKCE + state.
            String verifier = randomUrlSafe(32);
            String challenge = s256(verifier);
            String state = randomUrlSafe(16);

            // 5. Authorization request → browser.
            StringBuilder url = new StringBuilder(authEndpoint)
                    .append(authEndpoint.contains("?") ? '&' : '?')
                    .append("response_type=code")
                    .append("&client_id=").append(enc(clientId))
                    .append("&redirect_uri=").append(enc(redirectUri))
                    .append("&code_challenge=").append(enc(challenge))
                    .append("&code_challenge_method=S256")
                    .append("&state=").append(enc(state))
                    .append("&resource=").append(enc(canonicalResource));
            String scope = joinStrings(prm, "scopes_supported");
            if (scope != null) url.append("&scope=").append(enc(scope));
            Constants.LOG.info("[numen-mcp-oauth] opening browser to authorize '{}'", spec.name());
            net.minecraft.util.Util.getPlatform().openUri(URI.create(url.toString()));

            // 6. Wait for the loopback redirect.
            String[] result = cb.future.get(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS);   // [code, state]
            if (!state.equals(result[1])) throw new IllegalStateException("OAuth state mismatch");

            // 7. Token exchange, then persist.
            JsonObject token = exchangeCode(tokenEndpoint, clientId, redirectUri, result[0], verifier, canonicalResource);
            store(configDir, spec.name(), token, tokenEndpoint, clientId, canonicalResource);
            Constants.LOG.info("[numen-mcp-oauth] '{}' authorized", spec.name());
        } finally {
            cb.server.stop(0);
        }
    }

    /** A valid bearer access token for a server (refreshing near expiry), or null if none stored. */
    static String tokenFor(String serverName, Path configDir) {
        JsonObject store = loadStore(configDir);
        if (!store.has(serverName) || !store.get(serverName).isJsonObject()) return null;
        JsonObject e = store.getAsJsonObject(serverName);
        long expiresAt = e.has("expires_at") ? e.get("expires_at").getAsLong() : 0;
        String access = str(e, "access_token");
        if (access != null && System.currentTimeMillis() < expiresAt - 60_000) return access;   // still fresh
        String refresh = str(e, "refresh_token");
        if (refresh == null) return access;   // no refresh token — hand back what we have (may 401 → re-auth)
        try {
            JsonObject token = refresh(str(e, "token_endpoint"), str(e, "client_id"), refresh, str(e, "resource"));
            store(configDir, serverName, token, str(e, "token_endpoint"), str(e, "client_id"), str(e, "resource"));
            return str(token, "access_token");
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-mcp-oauth] '{}' token refresh failed: {}", serverName, ex.toString());
            return access;
        }
    }

    // ---- discovery ----

    private static JsonObject fetchAsMetadata(String asUrl) throws Exception {
        String base = stripSlash(asUrl);
        try {
            return getJson(base + "/.well-known/oauth-authorization-server");
        } catch (Exception ex) {
            return getJson(base + "/.well-known/openid-configuration");   // OIDC fallback
        }
    }

    // ---- dynamic client registration ----

    private static String registerClient(String regEndpoint, String redirectUri) throws Exception {
        if (regEndpoint == null || regEndpoint.isBlank()) return null;
        JsonObject body = new JsonObject();
        body.addProperty("client_name", "Numen");
        JsonArray uris = new JsonArray();
        uris.add(redirectUri);
        body.add("redirect_uris", uris);
        body.add("grant_types", arr("authorization_code", "refresh_token"));
        body.add("response_types", arr("code"));
        body.addProperty("token_endpoint_auth_method", "none");   // public client (PKCE)
        body.addProperty("application_type", "native");
        HttpRequest req = HttpRequest.newBuilder(URI.create(regEndpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("client registration failed: HTTP " + resp.statusCode());
        }
        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
        return str(o, "client_id");
    }

    // ---- token endpoints ----

    private static JsonObject exchangeCode(String tokenEndpoint, String clientId, String redirectUri,
                                           String code, String verifier, String resource) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", clientId);
        form.put("code_verifier", verifier);
        form.put("resource", resource);
        return postForm(tokenEndpoint, form);
    }

    private static JsonObject refresh(String tokenEndpoint, String clientId, String refreshToken,
                                      String resource) throws Exception {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        form.put("client_id", clientId);
        if (resource != null) form.put("resource", resource);
        return postForm(tokenEndpoint, form);
    }

    private static JsonObject postForm(String endpoint, Map<String, String> form) throws Exception {
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> {
            if (body.length() > 0) body.append('&');
            body.append(enc(k)).append('=').append(enc(v));
        });
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("token endpoint HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    // ---- loopback callback server ----

    private record Callback(HttpServer server, int port, CompletableFuture<String[]> future) {}

    private static Callback startCallbackServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CompletableFuture<String[]> future = new CompletableFuture<>();
        server.createContext("/callback", (HttpExchange ex) -> {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String html;
            if (q.containsKey("code")) {
                future.complete(new String[]{q.get("code"), q.getOrDefault("state", "")});
                html = "<html><body style='font-family:sans-serif'>Numen 授权成功，可以关闭此页面。</body></html>";
            } else {
                future.completeExceptionally(new IllegalStateException("authorization denied: "
                        + q.getOrDefault("error", "no code")));
                html = "<html><body style='font-family:sans-serif'>授权失败：" + q.getOrDefault("error", "") + "</body></html>";
            }
            byte[] out = html.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        int port = server.getAddress().getPort();
        return new Callback(server, port, future);
    }

    // ---- token store ----

    private static void store(Path configDir, String serverName, JsonObject token,
                              String tokenEndpoint, String clientId, String resource) {
        JsonObject all = loadStore(configDir);
        JsonObject e = new JsonObject();
        e.addProperty("access_token", str(token, "access_token"));
        if (token.has("refresh_token")) e.addProperty("refresh_token", str(token, "refresh_token"));
        else if (all.has(serverName)) {   // refresh responses may omit it — keep the prior one
            String prev = str(all.getAsJsonObject(serverName), "refresh_token");
            if (prev != null) e.addProperty("refresh_token", prev);
        }
        long ttl = token.has("expires_in") ? token.get("expires_in").getAsLong() : 3600;
        e.addProperty("expires_at", System.currentTimeMillis() + ttl * 1000);
        e.addProperty("token_endpoint", tokenEndpoint);
        e.addProperty("client_id", clientId);
        if (resource != null) e.addProperty("resource", resource);
        all.add(serverName, e);
        Path file = configDir.resolve(STORE_FILE);
        try {
            Files.createDirectories(configDir);
            Files.writeString(file, GSON.toJson(all), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-mcp-oauth] failed to write {}: {}", file, ex.toString());
        }
    }

    private static JsonObject loadStore(Path configDir) {
        Path file = configDir.resolve(STORE_FILE);
        if (!Files.isRegularFile(file)) return new JsonObject();
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ex) {
            Constants.LOG.warn("[numen-mcp-oauth] unreadable {}: {}", file, ex.toString());
            return new JsonObject();
        }
    }

    // ---- small utilities ----

    private static JsonObject getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) throw new IllegalStateException("GET " + url + " → HTTP " + resp.statusCode());
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }

    /** RFC 8707 canonical resource: scheme+host(+port)(+path), no fragment, no trailing slash. */
    private static String canonicalResource(String url) {
        URI u = URI.create(url);
        String s = u.getScheme() + "://" + u.getAuthority() + (u.getPath() == null ? "" : u.getPath());
        return stripSlash(s);
    }

    private static String wellKnown(String serverUrl, String doc) {
        URI u = URI.create(serverUrl);
        return u.getScheme() + "://" + u.getAuthority() + "/.well-known/" + doc;
    }

    private static String stripSlash(String s) {
        return s != null && s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String str(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
    }

    private static String firstString(JsonObject o, String arrayKey) {
        if (o.has(arrayKey) && o.get(arrayKey).isJsonArray()) {
            JsonArray a = o.getAsJsonArray(arrayKey);
            if (!a.isEmpty() && a.get(0).isJsonPrimitive()) return a.get(0).getAsString();
        }
        return null;
    }

    private static String joinStrings(JsonObject o, String arrayKey) {
        if (!o.has(arrayKey) || !o.get(arrayKey).isJsonArray()) return null;
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : o.getAsJsonArray(arrayKey)) {
            if (!el.isJsonPrimitive()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(el.getAsString());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static JsonArray arr(String... values) {
        JsonArray a = new JsonArray();
        for (String v : values) a.add(v);
        return a;
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String s256(String verifier) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            if (i <= 0) continue;
            String k = java.net.URLDecoder.decode(pair.substring(0, i), StandardCharsets.UTF_8);
            String v = java.net.URLDecoder.decode(pair.substring(i + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }
}
