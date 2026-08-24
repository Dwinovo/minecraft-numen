package com.dwinovo.numen.client.skin;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.MojangSkins;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 借正版玩家皮肤——<b>在主人的客户端上查</b>,而不是服务端。
 *
 * <p>为什么搬过来:服务端那条路走的是 Minecraft 内置的会话服务栈,吃 JVM
 * 默认网络,完全不理会模组的代理设置——国内直连 Mojang 经常超时,于是
 * "有时候皮肤就没了"。客户端这边有玩家自己配的代理,而且失败原因能当场
 * 告诉他,不必埋进服务端日志。
 *
 * <p>拿到的是 Mojang 签名的 textures(自验证,伪造不了),随召唤包发给服务端
 * 直接挂上去——与自定义皮肤走同一条路,服务端不再需要任何皮肤查询。
 *
 * <p>结果按名字缓存(含"查无此人"),同一个名字反复召唤不重复联网。
 */
public final class MojangSkinLookup {

    private static final String UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String PROFILE_URL =
            "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** 查询结果。{@code skin} 为 null = 没有可用皮肤;{@code problem} 非 null = 出了错(给主人看)。 */
    public record Result(MojangSkins.Skin skin, String problem) {
        public static final Result NONE = new Result(null, null);
    }

    /** 名字 → 结果的进程内缓存(含查无此人的否定结果)。 */
    private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

    private MojangSkinLookup() {}

    /** 异步查 {@code playerName} 的签名皮肤;任何失败都归结为带原因的 Result,不抛。 */
    public static CompletableFuture<Result> fetch(String playerName) {
        if (!MojangSkins.validName(playerName)) {
            return CompletableFuture.completedFuture(Result.NONE);
        }
        Result cached = CACHE.get(playerName);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String uuid = fetchUuid(playerName);
                if (uuid == null) {
                    return remember(playerName, Result.NONE);   // 不是正版玩家名:正常情况,不报错
                }
                MojangSkins.Skin skin = fetchTextures(uuid);
                if (skin == null) {
                    return remember(playerName, new Result(null, "档案里没有皮肤数据"));
                }
                Constants.LOG.info("[numen-skin] 借到 {} 的皮肤", playerName);
                return remember(playerName, new Result(skin, null));
            } catch (RuntimeException e) {
                // 网络问题不进缓存:换个网络/开了代理就该能成
                String why = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Constants.LOG.warn("[numen-skin] 查 {} 的皮肤失败: {}", playerName, why);
                return new Result(null, why);
            }
        });
    }

    private static Result remember(String name, Result r) {
        CACHE.put(name, r);
        return r;
    }

    /** 名字 → UUID;查无此人返回 null(404 是正常应答,不是错误)。 */
    private static String fetchUuid(String name) {
        HttpResponse<String> resp = send(UUID_URL + name);
        if (resp.statusCode() == 404 || resp.body() == null || resp.body().isBlank()) {
            return null;
        }
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Mojang 档案接口 HTTP " + resp.statusCode());
        }
        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
        return o.has("id") ? o.get("id").getAsString() : null;
    }

    /** UUID → 签名 textures;没有该属性返回 null。 */
    private static MojangSkins.Skin fetchTextures(String uuid) {
        HttpResponse<String> resp = send(PROFILE_URL + uuid + "?unsigned=false");
        if (resp.statusCode() != 200) {
            throw new IllegalStateException("Mojang 会话接口 HTTP " + resp.statusCode());
        }
        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonArray props = o.getAsJsonArray("properties");
        if (props == null) {
            return null;
        }
        for (var el : props) {
            JsonObject p = el.getAsJsonObject();
            if (p.has("name") && "textures".equals(p.get("name").getAsString()) && p.has("value")) {
                return new MojangSkins.Skin(p.get("value").getAsString(),
                        p.has("signature") ? p.get("signature").getAsString() : "");
            }
        }
        return null;
    }

    private static HttpResponse<String> send(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            return ProxiedHttp.client().send(req,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("连不上 Mojang(检查网络或代理)", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("查询被中断", e);
        }
    }
}
