package com.dwinovo.numen.mcp.client.auth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nimbusds.oauth2.sdk.as.AuthorizationServerMetadata;
import com.nimbusds.oauth2.sdk.id.Issuer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 找到该去哪登录(MCP 授权规范的发现两步):
 * <ol>
 *   <li>受保护资源元数据(RFC 9728):先读 401 的 {@code WWW-Authenticate} 里的 {@code resource_metadata},没有就探
 *       {@code /.well-known/oauth-protected-resource} 加上服务器路径、再不行去掉路径;里面写着授权服务器和能用的权限;</li>
 *   <li>授权服务器元数据(RFC 8414,OpenID Connect 的发现作后备):端点、PKCE、注册方式——这一步 Nimbus 做。</li>
 * </ol>
 */
final class Discovery {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            // 明文 http 下 JDK 默认发 h2c 升级头,自建服务端(Uvicorn 等)刷警告;钉死 1.1。
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20)).build();

    private Discovery() {}

    /**
     * 受保护资源:要拿令牌用的规范资源地址、它信任的授权服务器、它说的最小权限。
     *
     * @param uri            RFC 8707 的规范资源地址:协议、主机(端口)、路径,没有片段、没有末尾斜杠
     * @param scopesSupported 元数据里的 {@code scopes_supported};没写是空
     */
    record Resource(String uri, Issuer issuer, List<String> scopesSupported) {}

    static Resource resource(String serverUrl, String resourceMetadataUrl) throws Exception {
        JsonObject metadata = null;
        Exception last = null;
        for (String candidate : candidates(serverUrl, resourceMetadataUrl)) {
            try {
                metadata = getJson(candidate);
                break;
            } catch (Exception failed) {
                last = failed;
            }
        }
        if (metadata == null) {
            throw new IllegalStateException("cannot find the server's protected resource metadata: " + last);
        }
        JsonArray servers = metadata.has("authorization_servers") && metadata.get("authorization_servers").isJsonArray()
                ? metadata.getAsJsonArray("authorization_servers") : new JsonArray();
        if (servers.isEmpty() || !servers.get(0).isJsonPrimitive()) {
            throw new IllegalStateException("no authorization_servers in the resource metadata");
        }
        List<String> scopes = new ArrayList<>();
        if (metadata.has("scopes_supported") && metadata.get("scopes_supported").isJsonArray()) {
            for (JsonElement s : metadata.getAsJsonArray("scopes_supported")) {
                if (s.isJsonPrimitive()) scopes.add(s.getAsString());
            }
        }
        return new Resource(canonicalResource(serverUrl), new Issuer(servers.get(0).getAsString()), List.copyOf(scopes));
    }

    /**
     * 授权服务器元数据,按 MCP 授权规范的顺序试发现地址(RFC 8414 把 well-known 插在主机与路径之间,OpenID Connect 的两种也试);
     * 解析交给 Nimbus。它自报的 issuer 必须就是我们去找的那个(RFC 8414)。
     */
    static AuthorizationServerMetadata authorizationServer(Issuer issuer) throws Exception {
        URI u = URI.create(issuer.getValue());
        String origin = u.getScheme() + "://" + u.getAuthority();
        String path = u.getPath() == null || u.getPath().equals("/") ? "" : u.getPath();
        List<String> urls = new ArrayList<>();
        urls.add(origin + "/.well-known/oauth-authorization-server" + path);
        urls.add(origin + "/.well-known/openid-configuration" + path);
        if (!path.isEmpty()) {
            urls.add(issuer.getValue() + "/.well-known/openid-configuration");
        }
        Exception last = null;
        for (String url : urls) {
            try {
                AuthorizationServerMetadata metadata = AuthorizationServerMetadata.resolve(issuer, URI.create(url).toURL());
                if (!issuer.equals(metadata.getIssuer())) {
                    throw new IllegalStateException("the authorization server calls itself " + metadata.getIssuer()
                            + ", not " + issuer);
                }
                return metadata;
            } catch (Exception failed) {
                last = failed;
            }
        }
        throw new IllegalStateException("cannot find the authorization server's metadata: " + last, last);
    }

    private static List<String> candidates(String serverUrl, String resourceMetadataUrl) {
        if (resourceMetadataUrl != null && !resourceMetadataUrl.isBlank()) {
            return List.of(resourceMetadataUrl);
        }
        URI u = URI.create(serverUrl);
        String root = u.getScheme() + "://" + u.getAuthority() + "/.well-known/oauth-protected-resource";
        String path = u.getPath();
        return path == null || path.isEmpty() || path.equals("/") ? List.of(root) : List.of(root + path, root);
    }

    /** RFC 8707 的规范资源地址:协议与主机小写,路径照旧,没有片段、没有末尾斜杠。 */
    static String canonicalResource(String url) {
        URI u = URI.create(url);
        String s = u.getScheme().toLowerCase() + "://" + u.getAuthority().toLowerCase()
                + (u.getPath() == null ? "" : u.getPath());
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static JsonObject getJson(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("GET " + url + " → HTTP " + resp.statusCode());
        }
        return JsonParser.parseString(resp.body()).getAsJsonObject();
    }
}
