package com.dwinovo.numen.api.agent.catalog;

import com.dwinovo.numen.api.ai.AiLog;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.api.store.JsonDoc;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 运行时从 models.dev 拉下来的那份目录,缓存在一个小文档里(服务器放 {@code config/numen/server/cache/models-dev.json}):
 * 第 2 版是 {@code {"v":2,"providers":{裁过的目录}}},第 1 版是没有版本号、顶层直接就是目录的那份(读时包一层)。
 * 比随包快照新,所以优先级更高;文件不在或读不动就是空表,目录回头用快照。
 *
 * <p>配置目录和代理由客户端传进来,本类不知道 Minecraft。
 */
public final class DownloadCache implements CatalogSource {

    /** 缓存不到这么久不重拉。 */
    static final Duration MAX_AGE = Duration.ofDays(1);

    private static final String PROVIDERS = "providers";

    private final JsonDoc doc;
    private final URI source;
    private volatile Map<String, ProviderInfo> providers;

    public DownloadCache(Path file) {
        this(file, ModelsDevDownload.API);
    }

    DownloadCache(Path file, URI source) {
        this.doc = new JsonDoc(file,
                JsonDoc.Format.of(2).upgrade(1, catalog -> {
                    JsonObject wrapped = new JsonObject();
                    wrapped.add(PROVIDERS, catalog);
                    return wrapped;
                }),
                Io.direct(e -> AiLog.LOG.warn("[numen-catalog] {}", e.getMessage())));
        this.source = source;
        this.providers = read();
    }

    @Override
    public Map<String, ProviderInfo> providers() {
        return providers;
    }

    /**
     * 后台刷新:缓存不到一天就什么都不做;否则拉一次、落盘、换上新数据。拉不到(离线、被墙、对端出错)
     * 就保持现状,不报错——有缓存用缓存,没有用快照。future 里是"这次有没有换上新数据"。
     *
     * @param proxy {@code host:port},空 = 直连
     */
    public CompletableFuture<Boolean> refresh(String proxy) {
        if (isFresh()) {
            return CompletableFuture.completedFuture(false);
        }
        return ModelsDevDownload.slimmed(source, proxy).thenApply(slim -> {
            JsonObject wrapped = new JsonObject();
            wrapped.add(PROVIDERS, JsonParser.parseString(slim).getAsJsonObject());
            doc.write(wrapped);   // 落盘失败只告警:这一局照样用刚拉到的
            providers = java.util.Collections.unmodifiableMap(ModelsDev.parse(slim));
            return true;
        }).exceptionally(e -> {
            AiLog.LOG.debug("[numen-catalog] models.dev refresh skipped: {}", e.toString());
            return false;
        });
    }

    private boolean isFresh() {
        try {
            return Files.exists(doc.file()) && Duration.between(
                    Files.getLastModifiedTime(doc.file()).toInstant(), Instant.now()).compareTo(MAX_AGE) < 0;
        } catch (IOException e) {
            return false;
        }
    }

    /** 文件不在或读不懂都是空表:缓存只是加速,坏了不该挡路。 */
    private Map<String, ProviderInfo> read() {
        try {
            return doc.read()
                    .map(root -> java.util.Collections.unmodifiableMap(
                            ModelsDev.parse(root.getAsJsonObject(PROVIDERS).toString())))
                    .orElse(Map.of());
        } catch (RuntimeException e) {   // 读不了(StoreException)或目录格式读不懂
            AiLog.LOG.warn("[numen-catalog] unreadable cache {} — using the bundled snapshot ({})",
                    doc.file(), e.toString());
            return Map.of();
        }
    }
}
