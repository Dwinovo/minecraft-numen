package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.store.Io;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 远程端点的服务器配置:端点绑在哪(够不够得着决定要不要警告明文 HTTP)、限流的值能读能写、默认关着。
 */
class McpConfigTest {

    private static final Io DIRECT = Io.direct(e -> { throw e; });

    private static McpConfig on(String host) {
        return new McpConfig(true, host, 8765, 300, List.of(), McpConfig.Limits.DEFAULT);
    }

    @Test
    void loopbackIsNotExposed() {
        assertFalse(on("127.0.0.1").lanExposed());
        assertFalse(on("localhost").lanExposed());
        assertFalse(on("LOCALHOST").lanExposed(), "大小写不该改变判断");
    }

    @Test
    void anythingElseIsExposed() {
        assertTrue(on("0.0.0.0").lanExposed());
        assertTrue(on("192.168.1.7").lanExposed(), "绑到具体网卡同样是对外的");
        assertTrue(on("::").lanExposed());
    }

    @Test
    void aMissingFileGivesADisabledDefaultAndWritesIt(@TempDir Path dir) {
        Path file = dir.resolve("mcp_server.json");

        McpConfig loaded = McpConfig.load(file, DIRECT);

        assertFalse(loaded.enabled(), "端点默认关,服主自己开");
        assertEquals(McpConfig.LOOPBACK, loaded.host());
        assertTrue(java.nio.file.Files.isRegularFile(file));
    }

    @Test
    void whatIsSavedIsWhatIsLoaded(@TempDir Path dir) {
        Path file = dir.resolve("mcp_server.json");
        McpConfig custom = new McpConfig(true, "0.0.0.0", 9100, 60, List.of("todo"),
                new McpConfig.Limits(3, 50, 20, 400));

        custom.save(file, DIRECT);

        assertEquals(custom, McpConfig.load(file, DIRECT));
    }

    @Test
    void eachWitherLeavesTheRestAlone() {
        McpConfig base = new McpConfig(true, "127.0.0.1", 8765, 300, List.of("todo"), McpConfig.Limits.DEFAULT);

        McpConfig moved = base.withEndpoint("0.0.0.0", 9000, 60);
        assertEquals(List.of("todo"), moved.hiddenTools());
        assertEquals(McpConfig.Limits.DEFAULT, moved.limits());
        assertTrue(moved.enabled());

        McpConfig rehidden = base.withHiddenTools(List.of("a", "b"));
        assertEquals(8765, rehidden.port());
        assertEquals(300, rehidden.callTimeoutSeconds());
    }

    @Test
    void aZeroLimitIsRefusedRatherThanLockingEveryoneOut() {
        assertThrows(IllegalArgumentException.class, () -> new McpConfig.Limits(0, 10, 10, 10));
    }
}
