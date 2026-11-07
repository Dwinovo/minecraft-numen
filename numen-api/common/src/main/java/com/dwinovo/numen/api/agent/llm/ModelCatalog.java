package com.dwinovo.numen.api.agent.llm;

import com.dwinovo.numen.api.agent.catalog.BundledSnapshot;
import com.dwinovo.numen.api.agent.catalog.Catalog;
import com.dwinovo.numen.api.agent.catalog.DownloadCache;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 服务器上的型号目录:随包快照,加服务器每天从 models.dev 拉一次的缓存({@code server/cache/models-dev.json}),
 * 和把一份档案解析成能发请求的端点({@link EndpointResolver})所需的全局代理。服务器一份,所有玩家的档案共用。
 * 能力(上下文、思考档位、协议、缺省地址)一律经这里查,档案、界面、压缩阈值、请求都从同一个解析结果取。
 */
public final class ModelCatalog {

    private final DownloadCache cache;
    private final Catalog catalog;
    private final Supplier<String> proxy;

    /**
     * @param cacheFile models.dev 缓存文件
     * @param proxy     全局代理 {@code host:port},每次现问:游戏里是服务器配置里此刻的那一个
     */
    public ModelCatalog(Path cacheFile, Supplier<String> proxy) {
        this.cache = new DownloadCache(cacheFile);
        this.catalog = new Catalog(cache, BundledSnapshot.INSTANCE);
        this.proxy = proxy;
    }

    public Catalog catalog() {
        return catalog;
    }

    /** 服务器起来时调一次:后台刷新缓存(一天最多拉一次,拉不到静默用缓存或快照)。 */
    public void refresh() {
        cache.refresh(proxy.get());
    }

    /** 全局代理;档案自己填了代理的以它为准,留空跟随这个。 */
    public String proxy() {
        return proxy.get();
    }

    /** 一份档案(连同它这次用的 key)解析成端点;草稿(还没保存的表单)同样走这一条。 */
    public LlmEndpoint resolve(ConnectionProfile profile) {
        return EndpointResolver.resolve(profile, catalog, proxy.get());
    }

    /** 没有这份档案时的端点:缺地址缺 key,发请求时给出和没填完的档案一样诚实的错误。 */
    public LlmEndpoint unresolved() {
        return LlmEndpoint.unresolved(proxy.get());
    }
}
