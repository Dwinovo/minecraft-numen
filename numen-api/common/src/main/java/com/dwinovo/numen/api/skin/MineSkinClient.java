package com.dwinovo.numen.api.skin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * MineSkin 代签:把任意皮肤 png 换成 Mojang 签名的 textures(value+signature)。原版客户端只认 Mojang 签过名的
 * 皮肤数据,MineSkin 用正版账号池"代穿一次抠下签名"。免费匿名档按调用方 IP 限流,所以整台服务器的签名请求在服务端排成一队
 * 一个一个发({@link SkinSigning}),服主可以在服务器钥匙串里填 MineSkin 的 API key 提高额度。
 *
 * <p>同步:调用的一方是签名队的工作线程。走服务器的代理设置(见 {@link ProxiedHttp});失败抛带原因码的 {@link SkinError}
 * (限流带着对方说的等待秒数)。
 */
public final class MineSkinClient {

    /** 代签结果。 */
    public record Signed(String value, String signature) {}

    /** 钥匙串里放 MineSkin API key 的服务名。 */
    public static final String KEY_SERVICE = "mineskin";

    private static final String SERVICE = "MineSkin";
    private static final String GENERATE_URL = "https://api.mineskin.org/v2/generate";
    private static final Duration TIMEOUT = Duration.ofSeconds(40);   // 生成含排队,给足

    private MineSkinClient() {}

    /**
     * 上传 png 换签名。
     *
     * @param variant {@link SkinProfile#VARIANT_CLASSIC} 或 {@link SkinProfile#VARIANT_SLIM}
     * @param apiKey  服主填的 MineSkin API key;没有是 {@code null}(匿名档)
     */
    public static Signed generate(byte[] png, String variant, String apiKey) {
        String boundary = "numenskin" + Long.toHexString(System.nanoTime());
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(GENERATE_URL))
                .timeout(TIMEOUT)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Accept", "application/json")
                .header("User-Agent", "numen-companion-mod (github.com/dwinovo)")
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart(boundary, png, variant)));
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey.strip());
        }
        try {
            return parse(ProxiedHttp.client().send(request.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw SkinError.of(e, SkinError.NETWORK, SERVICE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SkinError.of(e, SkinError.INTERRUPTED);
        }
    }

    /** 图片是玩家的私事,不挂在 MineSkin 的公开图库里。 */
    private static byte[] multipart(String boundary, byte[] png, String variant) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(png.length + 512);
            String head = "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"variant\"\r\n\r\n" + variant + "\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"visibility\"\r\n\r\nunlisted\r\n"
                    + "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"skin.png\"\r\n"
                    + "Content-Type: image/png\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.UTF_8));
            out.write(png);
            out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);   // ByteArrayOutputStream 不会真抛
        }
    }

    /** 容错解析:v2({@code skin.texture.data})与 v1({@code data.texture})两种形状都吃。 */
    static Signed parse(HttpResponse<String> resp) {
        if (resp.statusCode() == 429) {
            throw SkinError.rateLimited(SERVICE, retryAfter(resp));
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(resp.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw SkinError.of(SkinError.REPLY_BAD, SERVICE);
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
        throw why.isEmpty() ? SkinError.of(SkinError.REPLY_MISSING, SERVICE)
                : SkinError.http(SERVICE, resp.statusCode(), why);
    }

    /** 对方在 {@code Retry-After} 里说的秒数;没说或不是秒数是 0。 */
    private static int retryAfter(HttpResponse<String> resp) {
        try {
            return resp.headers().firstValue("Retry-After").map(v -> Integer.parseInt(v.strip())).orElse(0);
        } catch (NumberFormatException notSeconds) {
            return 0;
        }
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
