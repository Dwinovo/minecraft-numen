package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 令牌库读坏时:原样留着、整库不可用,不是当作"没有令牌"然后被下一次写盖掉。 */
class McpTokensTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @TempDir
    Path dir;

    private McpTokens open() {
        return new McpTokens(new Home(dir, Io.direct(e -> { throw e; })), System::currentTimeMillis);
    }

    @Test
    void anUnreadableStoreIsLeftAloneAndRefusesEverything() throws Exception {
        McpTokens.Issued issued = open().issue(OWNER, McpToken.Role.BRAIN, McpToken.Scope.ALL, "", 0);
        Path file = dir.resolve(McpTokens.FILE);
        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);

        McpTokens broken = open();

        assertTrue(broken.unavailable().isPresent());
        assertEquals(file, broken.unavailable().get().file());
        assertTrue(broken.authenticate(issued.secret()).isEmpty(), "读不了的库不认任何令牌");
        assertThrows(IllegalStateException.class,
                () -> broken.issue(OWNER, McpToken.Role.BRAIN, McpToken.Scope.ALL, "", 0));
        assertThrows(IllegalStateException.class, () -> broken.revoke(OWNER, issued.token().id()));
        assertEquals("{ this is not json", Files.readString(file, StandardCharsets.UTF_8), "那份文件原样不动");
    }

    @Test
    void aStoreWithTheWrongShapeIsUnreadableToo() throws Exception {
        Path file = dir.resolve(McpTokens.FILE);
        String wrong = "{\"tokens\":[{\"hash\":\"abcdef0123456789\"}]}";
        Files.writeString(file, wrong, StandardCharsets.UTF_8);

        McpTokens broken = open();

        assertTrue(broken.unavailable().isPresent());
        assertEquals(wrong, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void aGoodStoreIsUsableAfterTheFileIsFixed() throws Exception {
        McpTokens.Issued issued = open().issue(OWNER, McpToken.Role.BRAIN, McpToken.Scope.ALL, "", 0);

        McpTokens again = open();

        assertTrue(again.unavailable().isEmpty());
        assertTrue(again.authenticate(issued.secret()).isPresent());
    }
}
