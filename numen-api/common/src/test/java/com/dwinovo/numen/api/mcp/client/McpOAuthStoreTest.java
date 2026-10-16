package com.dwinovo.numen.api.mcp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 令牌库:第 1 版顶层直接是"服务器名 → 令牌",读时升到第 2 版;服务器名叫什么都不会和版本号撞名。 */
class McpOAuthStoreTest {

    private static String entry(String access) {
        return "{\"access_token\":\"" + access + "\",\"expires_at\":" + (System.currentTimeMillis() + 3_600_000L) + "}";
    }

    @Test
    void aVersionOneStoreIsStillReadable(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mcp_oauth.json"), "{\"files\":" + entry("tok-1") + "}", StandardCharsets.UTF_8);

        assertEquals("tok-1", McpOAuth.tokenFor("files", dir));
        assertNull(McpOAuth.tokenFor("unknown", dir));
    }

    @Test
    void aServerNamedLikeTheVersionKeyIsNotSwallowed(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("mcp_oauth.json"), "{\"v\":" + entry("tok-v") + "}", StandardCharsets.UTF_8);

        assertEquals("tok-v", McpOAuth.tokenFor("v", dir), "第 1 版里叫 v 的服务器,升级后还在");
    }
}
