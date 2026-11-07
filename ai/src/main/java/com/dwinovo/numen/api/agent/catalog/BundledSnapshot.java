package com.dwinovo.numen.api.agent.catalog;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 随包带的那份 models.dev 快照({@value #RESOURCE}),离线、第一次启动时也有数据。
 * 由 {@code :ai:updateModelsSnapshot} 手动更新并提交进仓库;普通构建不联网。
 * 资源坏了是构建事故,直接抛出来,不悄悄当空表。
 */
public final class BundledSnapshot implements CatalogSource {

    public static final String RESOURCE = "/numen_models_snapshot.json";

    public static final BundledSnapshot INSTANCE = new BundledSnapshot();

    private volatile Map<String, ProviderInfo> providers;

    private BundledSnapshot() {}

    @Override
    public Map<String, ProviderInfo> providers() {
        Map<String, ProviderInfo> p = providers;
        if (p == null) {
            synchronized (this) {
                if (providers == null) {
                    providers = java.util.Collections.unmodifiableMap(load());
                }
                p = providers;
            }
        }
        return p;
    }

    private static Map<String, ProviderInfo> load() {
        try (var in = BundledSnapshot.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            return ModelsDev.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
