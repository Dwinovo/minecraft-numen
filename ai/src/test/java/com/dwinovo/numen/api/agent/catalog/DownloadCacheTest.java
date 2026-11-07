package com.dwinovo.numen.api.agent.catalog;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 运行时缓存:拉下来落盘并换上、一天内不重拉、拉不到静默保持原样、坏文件不挡路。 */
class DownloadCacheTest {

    @TempDir
    Path dir;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile String body = ModelsDevTest.SAMPLE;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api.json", ex -> {
            hits.incrementAndGet();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private DownloadCache cache(Path file) {
        return new DownloadCache(file, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api.json"));
    }

    @Test
    void refreshWritesTheTrimmedFileAndSwapsTheDataIn() {
        Path file = dir.resolve("cache").resolve("models-dev.json");
        DownloadCache cache = cache(file);
        assertTrue(cache.providers().isEmpty());

        assertTrue(cache.refresh("").join());

        assertTrue(Files.exists(file));
        assertEquals(ModelsDev.parse(ModelsDevTest.SAMPLE), cache.providers());
        assertFalse(Files.exists(file.resolveSibling("models-dev.json.tmp")));
        assertEquals(ModelsDev.parse(ModelsDev.slim(ModelsDevTest.SAMPLE)), new DownloadCache(file).providers(),
                "下次启动直接读回来");
    }

    @Test
    void aFreshCacheIsNotFetchedAgain() throws IOException {
        Path file = dir.resolve("models-dev.json");
        Files.writeString(file, ModelsDev.slim(ModelsDevTest.SAMPLE));
        DownloadCache cache = cache(file);

        assertFalse(cache.refresh("").join());
        assertEquals(0, hits.get());
    }

    @Test
    void aStaleCacheIsFetchedAgain() throws IOException {
        Path file = dir.resolve("models-dev.json");
        Files.writeString(file, "{}");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(DownloadCache.MAX_AGE).minusSeconds(60)));
        DownloadCache cache = cache(file);

        assertTrue(cache.refresh("").join());
        assertEquals(1, hits.get());
        assertFalse(cache.providers().isEmpty());
    }

    @Test
    void whenTheFetchFailsTheExistingDataStaysAndNothingIsRaised() throws IOException {
        Path file = dir.resolve("models-dev.json");
        Files.writeString(file, "{\"keep\":{\"npm\":\"x\",\"models\":{}}}");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(DownloadCache.MAX_AGE).minusSeconds(60)));
        DownloadCache cache = cache(file);
        status = 500;

        assertFalse(cache.refresh("").join());
        assertEquals(java.util.Set.of("keep"), cache.providers().keySet());
        assertTrue(Files.readString(file).contains("keep"), "旧缓存文件不动");

        status = 200;
        body = "not json at all";
        assertFalse(cache.refresh("").join(), "对端给了读不懂的东西也一样");
        assertTrue(Files.readString(file).contains("keep"));
    }

    @Test
    void anUnreadableCacheFileIsJustEmpty() throws IOException {
        Path file = dir.resolve("models-dev.json");
        Files.writeString(file, "{{{ broken");
        assertTrue(cache(file).providers().isEmpty());
    }
}
