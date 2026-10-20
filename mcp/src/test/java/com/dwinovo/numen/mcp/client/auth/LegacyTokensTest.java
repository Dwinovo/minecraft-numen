package com.dwinovo.numen.mcp.client.auth;

import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧版家里的 mcp_oauth.json 搬进钥匙串:两种版本的写法都读得了,搬完旧文件删掉,读不通的原样留着。 */
class LegacyTokensTest {

    @TempDir
    Path dir;
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
    private final Io io = Io.direct(e -> { throw e; });

    private static String entry(String token) {
        return "{\"access_token\":\"" + token + "\",\"refresh_token\":\"r\",\"expires_at\":5,"
                + "\"token_endpoint\":\"https://as/token\",\"client_id\":\"c\",\"resource\":\"https://s/mcp\"}";
    }

    @Test
    void theFlatFirstVersionAndTheNestedSecondVersionBothMoveIntoTheKeychainAndTheFileIsDeleted() throws Exception {
        Path flat = dir.resolve("flat.json");
        Files.writeString(flat, "{\"files\":" + entry("tok-1") + "}", StandardCharsets.UTF_8);
        Path nested = dir.resolve("nested.json");
        Files.writeString(nested, "{\"v\":2,\"servers\":{\"docs\":" + entry("tok-2") + "}}", StandardCharsets.UTF_8);

        assertEquals(1, LegacyTokens.migrate(flat, io, store));
        assertEquals(1, LegacyTokens.migrate(nested, io, store));

        JsonObject files = JsonParser.parseString(vault.get(McpOAuth.keyOf("files"))).getAsJsonObject();
        assertEquals("tok-1", files.get("access_token").getAsString());
        assertEquals("r", files.get("refresh_token").getAsString());
        assertEquals("", files.get("issuer").getAsString(), "旧令牌不记得是哪个授权服务器发的");
        assertTrue(vault.containsKey(McpOAuth.keyOf("docs")));
        assertFalse(Files.exists(flat) || Files.exists(nested), "搬完旧文件就删");
        assertEquals(0, LegacyTokens.migrate(flat, io, store), "只做一次");
    }

    @Test
    void anUnreadableFileIsLeftAlone() throws Exception {
        Path broken = dir.resolve("broken.json");
        Files.writeString(broken, "{ not json", StandardCharsets.UTF_8);

        assertEquals(0, LegacyTokens.migrate(broken, io, store));

        assertTrue(Files.exists(broken));
        assertTrue(vault.isEmpty());
    }
}
