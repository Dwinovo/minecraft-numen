package com.dwinovo.numen.api.mcp.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 关服时接进来的 stdio MCP 服务器要收干净:它起的整棵进程树都不能活过 {@code close()}。
 * 活下来的进程在游戏退出后还占着东西,启动器会把"进程没退干净"当成退出时崩溃。
 */
class TransportCloseTest {

    /** 孙进程:一直睡着。 */
    public static final class Sleeper {
        public static void main(String[] args) throws Exception {
            Thread.sleep(600_000);
        }
    }

    /** 子进程:起一个孙进程,把它的 pid 写进文件,然后睡着——像 npx 包着 node。 */
    public static final class Parent {
        public static void main(String[] args) throws Exception {
            Process grandchild = new ProcessBuilder(javaBin(), "-cp", System.getProperty("java.class.path"),
                    Sleeper.class.getName()).start();
            Files.writeString(Path.of(args[0]), Long.toString(grandchild.pid()));
            Thread.sleep(600_000);
        }
    }

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Test
    void closingAStdioTransportKillsTheWholeProcessTreeNotJustTheOutermost(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("grandchild.pid");
        StdioMcpTransport transport = new StdioMcpTransport("tree", List.of(javaBin(), "-cp",
                System.getProperty("java.class.path"), Parent.class.getName(), pidFile.toString()), Map.of());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(pidFile) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        Thread.sleep(100);   // 文件写完
        ProcessHandle grandchild = ProcessHandle.of(Long.parseLong(Files.readString(pidFile).trim())).orElseThrow();
        assertTrue(grandchild.isAlive(), "孙进程该先活着");

        transport.close();

        assertTrue(waitDead(grandchild), "关掉传输后孙进程还活着");
    }

    private static boolean waitDead(ProcessHandle handle) throws InterruptedException {
        for (int i = 0; i < 100 && handle.isAlive(); i++) {
            Thread.sleep(50);
        }
        return !handle.isAlive();
    }
}
