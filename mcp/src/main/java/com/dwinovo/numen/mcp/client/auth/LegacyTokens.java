package com.dwinovo.numen.mcp.client.auth;

import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 旧版把每台服务器的登录令牌存在家里的 {@code mcp_oauth.json};现在令牌住在钥匙串({@link CredentialStore})。读旧文件,
 * 每台服务器一条搬进钥匙串,搬完删掉旧文件——只做一次(之后文件已经没了)。
 *
 * <p>旧令牌不记得是哪个授权服务器发的:搬过去的 {@code issuer} 是空的,能用就用(刷新照常),下一次重新授权时按"换了授权服务器"处理,
 * 重新登录一遍。读不通的文件原样留着不动。
 */
public final class LegacyTokens {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");

    private LegacyTokens() {}

    /**
     * @return 搬了几台服务器的令牌
     */
    public static int migrate(Path file, Io io, CredentialStore store) {
        if (!Files.isRegularFile(file)) {
            return 0;
        }
        try {
            // 第 2 版是 {"servers":{服务器名:令牌}};第 1 版顶层直接就是服务器名到令牌的表
            JsonDoc doc = new JsonDoc(file, JsonDoc.Format.of(2).upgrade(1, flat -> {
                JsonObject nested = new JsonObject();
                nested.add("servers", flat);
                return nested;
            }), io);
            JsonObject servers = doc.read().map(o -> o.getAsJsonObject("servers")).orElseGet(JsonObject::new);
            int moved = 0;
            for (Map.Entry<String, JsonElement> e : servers.entrySet()) {
                if (e.getValue().isJsonObject() && e.getValue().getAsJsonObject().has("access_token")) {
                    store.put(McpOAuth.keyOf(e.getKey()), converted(e.getValue().getAsJsonObject()).toString());
                    moved++;
                }
            }
            Files.delete(file);
            LOG.info("[numen-mcp-oauth] 旧的 {} 搬进了钥匙串({} 台服务器),旧文件已删", file.getFileName(), moved);
            return moved;
        } catch (Exception unreadable) {
            LOG.warn("[numen-mcp-oauth] 旧的 {} 读不通,原样留着不动: {}", file, unreadable.toString());
            return 0;
        }
    }

    private static JsonObject converted(JsonObject old) {
        JsonObject o = new JsonObject();
        copy(old, o, "access_token");
        copy(old, o, "refresh_token");
        copy(old, o, "expires_at");
        copy(old, o, "token_endpoint");
        copy(old, o, "client_id");
        copy(old, o, "resource");
        o.addProperty("issuer", "");
        o.add("scope", new JsonArray());
        return o;
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from.has(key) && !from.get(key).isJsonNull()) {
            to.add(key, from.get(key));
        }
    }
}
