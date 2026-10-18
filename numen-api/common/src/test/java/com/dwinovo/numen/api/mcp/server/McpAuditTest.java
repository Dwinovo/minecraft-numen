package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAuditTest {

    @TempDir
    Path dir;

    @Test
    void theLogRotatesBySizeAndKeepsOnlyTheLatestFewOldOnes() throws Exception {
        Home home = new Home(dir, Io.direct(e -> { throw e; }));
        McpAudit audit = new McpAudit(home, 600, 2);
        McpToken token = new McpToken("tok00001", "h".repeat(64), UUID.randomUUID(), McpToken.Role.BRAIN,
                McpToken.Scope.ALL, "", 0, 0);

        for (int i = 0; i < 60; i++) {
            audit.called("s", token, "tool" + i, null, true, 10, 10);
        }

        Path current = dir.resolve(McpAudit.FILE);
        assertTrue(Files.exists(dir.resolve(McpAudit.FILE + ".1")));
        assertTrue(Files.exists(dir.resolve(McpAudit.FILE + ".2")));
        assertFalse(Files.exists(dir.resolve(McpAudit.FILE + ".3")), "只留最近 2 份旧的");
        assertTrue(Files.size(current) < 600 + 400, "当前这份不会无限长");
        assertTrue(Files.readString(current, StandardCharsets.UTF_8).contains("\"tool\":\"tool59\""), "最新的在当前这份里");
        assertFalse(Files.readString(dir.resolve(McpAudit.FILE + ".2"), StandardCharsets.UTF_8).contains("\"tool\":\"tool0\""),
                "最老的随轮转丢掉");
    }
}
