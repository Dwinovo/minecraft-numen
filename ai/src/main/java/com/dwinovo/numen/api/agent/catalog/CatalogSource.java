package com.dwinovo.numen.api.agent.catalog;

import java.util.Map;

/**
 * 型号目录的一个数据来源。来源只负责读出自己手里的那份,不知道别的来源存在,
 * 也不做优先级——谁盖过谁由 {@link Catalog} 一处决定。
 */
public interface CatalogSource {

    /** 这个来源知道的全部厂家(厂家 id → 厂家),保持来源里的顺序;读不到就是空表。 */
    Map<String, ProviderInfo> providers();
}
