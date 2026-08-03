package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.Services;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * MineSkin 代签客户端:把任意皮肤 png 换成 Mojang 签名的 textures
 * (value+signature)。原版客户端只认 Mojang 签过名的皮肤数据,MineSkin 用
 * 正版账号池"代穿一次抠下签名"。免费匿名档按调用方 IP 限流(约 10 张/分),
 * 本客户端跑在每个玩家自己的机器上,额度天然按人头分摊,分发无瓶颈。
 *
 * <p>跟随模组的代理设置(Services.CONFIG.getProxy);失败返回带人话的
 * 异常消息(限流/网络/服务端拒绝),绝不同步抛。
 */
public final class MineSkinClient {

    /** 代签结果。 */
    public record Signed(String value, String signature) {}

    private static final String GENERATE_URL = "https://api.mineskin.org/v2/generate";
    private static final Duration TIMEOUT = Duration.ofSeconds(40);   // 生成含排队,给足
    /** name 字段的保守长度上限(服务端有校验,宁短勿长)。 */
    private static final int MAX_NAME = 20;

    private MineSkinClient() {}

    /**
     * 上传 png 换签名。{@code variant} 取 {@link SkinLibrary#VARIANT_CLASSIC}/
     * {@link SkinLibrary#VARIANT_SLIM}。失败的 future 携带人话消息。
     */
    public static CompletableFuture<Signed> generate(byte[] png, String variant, String name) {
        HttpRequest request;
        try {
            String boundary = "numenskin" + Long.toHexString(System.nanoTime());
            request = HttpRequest.newBuilder()
                    .uri(URI.create(GENERATE_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("Accept", "application/json")
                    .header("User-Agent", "numen-companion-mod (github.com/dwinovo)")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, png, variant, name)))
                    .build();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
        return client().sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(MineSkinClient::parse);
    }

    /** 每次按当前代理设置新建(设置可随时改,不缓存过期的 ProxySelector)。 */
    private static HttpClient client() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL);
        String proxy = Services.CONFIG.getProxy();
        if (proxy != null && !proxy.isBlank()) {
            int colon = proxy.lastIndexOf(':');
            if (colon > 0) {
                try {
                    b.proxy(ProxySelector.of(new InetSocketAddress(proxy.substring(0, colon),
                            Integer.parseInt(proxy.substring(colon + 1).trim()))));
                } catch (RuntimeException ignored) {
                    // 端口不是数字之类——按直连走
                }
            }
        }
        return b.build();
    }

    private static byte[] multipart(String boundary, byte[] png, String variant, String name) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 512);
            StringBuilder head = new StringBuilder()
                    .append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"variant\"\r\n\r\n")
                    .append(variant).append("\r\n");
            // name 是可选字段:净化后为空就整条不发,而不是硬塞一个会被拒的值。
            String safe = asciiName(name);
            if (!safe.isEmpty()) {
                head.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"name\"\r\n\r\n")
                        .append(safe).append("\r\n");
            }
            head.append("--").append(boundary).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n")
                    .append("Content-Type: image/png\r\n\r\n");
            out.write(head.toString().getBytes(StandardCharsets.UTF_8));
            out.write(png);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);   // ByteArrayOutputStream 不会真抛
        }
    }

    /**
     * MineSkin 的 name 有服务端字符白名单,中文名直接 400 Validation error
     * (真机日志实证)。这里净化成保守的 ASCII 子集——但**不在表单层面限制**:
     * 皮肤条目名是玩家自己看的库标签,中文完全合理,上游的技术约束不该
     * 泄漏成用户的输入限制。净化后为空(纯中文名)就不发这个可选字段。
     */
    private static String asciiName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(MAX_NAME);
        for (int i = 0; i < raw.length() && sb.length() < MAX_NAME; i++) {
            char c = raw.charAt(i);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9' || c == '_' || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 容错解析:v2({@code skin.texture.data})与 v1({@code data.texture})两种形状都吃。 */
    private static Signed parse(HttpResponse<String> resp) {
        if (resp.statusCode() == 429) {
            throw new IllegalStateException("MineSkin 限流(免费档 20 张/分),稍等再试");
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IllegalStateException("MineSkin HTTP " + resp.statusCode() + ": 响应不是 JSON");
        }
        // v2: { skin: { texture: { data: { value, signature } } } }
        if (root.has("skin") && root.get("skin").isJsonObject()) {
            JsonObject tex = root.getAsJsonObject("skin");
            if (tex.has("texture") && tex.get("texture").isJsonObject()) {
                JsonObject data = tex.getAsJsonObject("texture");
                if (data.has("data") && data.get("data").isJsonObject()) {
                    JsonObject d = data.getAsJsonObject("data");
                    if (d.has("value")) {
                        return new Signed(d.get("value").getAsString(),
                                d.has("signature") ? d.get("signature").getAsString() : "");
                    }
                }
            }
        }
        // v1: { data: { texture: { value, signature } } }
        if (root.has("data") && root.get("data").isJsonObject()) {
            JsonObject data = root.getAsJsonObject("data");
            if (data.has("texture") && data.get("texture").isJsonObject()) {
                JsonObject t = data.getAsJsonObject("texture");
                if (t.has("value")) {
                    return new Signed(t.get("value").getAsString(),
                            t.has("signature") ? t.get("signature").getAsString() : "");
                }
            }
        }
        String why = firstError(root);
        throw new IllegalStateException("MineSkin HTTP " + resp.statusCode()
                + (why.isEmpty() ? ": 响应缺少 texture 数据" : ": " + why));
    }

    private static String firstError(JsonObject root) {
        if (root.has("errors") && root.get("errors").isJsonArray()
                && !root.getAsJsonArray("errors").isEmpty()) {
            var e = root.getAsJsonArray("errors").get(0);
            if (e.isJsonObject() && e.getAsJsonObject().has("message")) {
                return e.getAsJsonObject().get("message").getAsString();
            }
        }
        if (root.has("error") && root.get("error").isJsonPrimitive()) {
            return root.get("error").getAsString();
        }
        if (root.has("message") && root.get("message").isJsonPrimitive()) {
            return root.get("message").getAsString();
        }
        return "";
    }
}
