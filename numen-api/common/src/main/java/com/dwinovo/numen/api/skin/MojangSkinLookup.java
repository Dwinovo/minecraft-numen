package com.dwinovo.numen.api.skin;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.entity.MojangSkins;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 借正版玩家皮肤——在服务端查,走服务器的代理设置({@link ProxiedHttp}),失败原因能当场告诉玩家。
 * 拿到的是 Mojang 签名的 textures(自验证,伪造不了),与 MineSkin 代签出来的是同一种东西,存进皮肤库同一种条目。
 *
 * <p>同步:调用的一方是签名队的工作线程({@link SkinSigning})。结果按名字缓存(含"查无此人"),同一个名字不重复联网。
 */
public final class MojangSkinLookup {

    private static final String UUID_URL = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String PROFILE_URL =
            "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);

    /** 查询结果。{@code skin} 为 null = 没有可用皮肤;{@code problem} 非 null = 出了错。 */
    public record Result(MojangSkins.Skin skin, SkinError problem) {
        public static final Result NONE = new Result(null, null);
    }

    /** 名字 → 结果的进程内缓存(含查无此人的否定结果;网络问题不进缓存)。 */
    private static final Map<String, Result> CACHE = new ConcurrentHashMap<>();

    private MojangSkinLookup() {}

    /** 查 {@code playerName} 的签名皮肤;任何失败都归结为带原因的 Result,不抛。 */
    public static Result lookup(String playerName) {
        if (!MojangSkins.validName(playerName)) {
            return Result.NONE;
        }
        Result cached = CACHE.get(playerName);
        if (cached != null) {
            return cached;
        }
        try {
            String uuid = fetchUuid(playerName);
            if (uuid == null) {
                return remember(playerName, Result.NONE);   // 不是正版玩家名:正常情况,不报错
            }
            MojangSkins.Skin skin = fetchTextures(uuid);
            if (skin == null) {
                return remember(playerName, new Result(null, SkinError.of(SkinError.NO_SKIN)));
            }
            Constants.LOG.info("[numen-skin] 借到 {} 的皮肤", playerName);
            return remember(playerName, new Result(skin, null));
        } catch (RuntimeException e) {
            // 网络问题不进缓存:换个网络/开了代理就该能成
            SkinError why = SkinError.fromError(e);
            Constants.LOG.warn("[numen-skin] 查 {} 的皮肤失败: {}", playerName, why.getMessage());
            return new Result(null, why);
        }
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
            throw SkinError.http("api.mojang.com", resp.statusCode(), "");
        }
        JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
        return o.has("id") ? o.get("id").getAsString() : null;
    }

    /** UUID → 签名 textures;没有该属性返回 null。 */
    private static MojangSkins.Skin fetchTextures(String uuid) {
        HttpResponse<String> resp = send(PROFILE_URL + uuid + "?unsigned=false");
        if (resp.statusCode() != 200) {
            throw SkinError.http("sessionserver.mojang.com", resp.statusCode(), "");
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
            throw SkinError.of(e, SkinError.NETWORK, "Mojang");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SkinError.of(e, SkinError.INTERRUPTED);
        }
    }
}
