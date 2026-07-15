package com.dwinovo.numen.entity;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 借正版玩家皮肤:玩家名 → Mojang API 查 UUID → 会话服务器取<b>带签名</b>的
 * {@code textures} 属性(原版客户端只认 Mojang 签过名的皮肤数据,拿到后塞进
 * 同伴的 GameProfile 即可被任何客户端渲染,无需装任何客户端 mod——
 * SkinsRestorer 的同款原理)。全程异步,任何一步失败都归结为 {@code null}
 * (调用方回落原版默认皮肤),绝不抛到调用线程。
 */
public final class MojangSkins {

    /** Mojang 签名的 textures 属性对。{@code signature} 可为空串(理论上不该发生)。 */
    public record Skin(String value, String signature) {}

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** 成功结果按名缓存(进程生命周期)——皮肤数据签名长期有效,重复召唤不再打 API。 */
    private static final Map<String, Skin> CACHE = new ConcurrentHashMap<>();

    private MojangSkins() {}

    /** 合法的正版玩家名:3~16 位字母/数字/下划线。 */
    public static boolean validName(String s) {
        return s != null && s.matches("[A-Za-z0-9_]{3,16}");
    }

    /** 异步取 {@code playerName} 的签名皮肤;查无此人/网络失败 → {@code null}。 */
    public static CompletableFuture<Skin> fetch(String playerName) {
        String key = playerName.toLowerCase(Locale.ROOT);
        Skin cached = CACHE.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        HttpRequest byName = HttpRequest.newBuilder()
                .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName))
                .timeout(TIMEOUT).GET().build();
        return CLIENT.sendAsync(byName, HttpResponse.BodyHandlers.ofString())
                .thenCompose(resp -> {
                    if (resp.statusCode() != 200) {
                        return CompletableFuture.completedFuture(null);   // 查无此正版玩家
                    }
                    String id = JsonParser.parseString(resp.body())
                            .getAsJsonObject().get("id").getAsString();
                    HttpRequest profile = HttpRequest.newBuilder()
                            .uri(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"
                                    + id + "?unsigned=false"))
                            .timeout(TIMEOUT).GET().build();
                    return CLIENT.sendAsync(profile, HttpResponse.BodyHandlers.ofString())
                            .thenApply(r2 -> r2.statusCode() == 200 ? parseTextures(r2.body()) : null);
                })
                .whenComplete((skin, err) -> {
                    if (skin != null) {
                        CACHE.put(key, skin);
                        Constants.LOG.info("[numen-skin] 借到 {} 的皮肤", playerName);
                    } else {
                        Constants.LOG.warn("[numen-skin] 皮肤获取失败 {}: {}", playerName,
                                err == null ? "查无此正版玩家或响应异常" : String.valueOf(err));
                    }
                })
                .exceptionally(e -> null);
    }

    private static Skin parseTextures(String profileJson) {
        JsonObject root = JsonParser.parseString(profileJson).getAsJsonObject();
        if (!root.has("properties")) return null;
        for (JsonElement el : root.getAsJsonArray("properties")) {
            JsonObject p = el.getAsJsonObject();
            if ("textures".equals(p.get("name").getAsString())) {
                return new Skin(p.get("value").getAsString(),
                        p.has("signature") ? p.get("signature").getAsString() : "");
            }
        }
        return null;
    }
}
