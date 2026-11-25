package com.dwinovo.numen.api.seat;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 她的模块的正文,按内容哈希存着:程序起跑时客户端报来的清单里,哈希在这里的不必再要。哈希就是内容,所以同一份正文谁来要都是同一个答案,
 * 不会过期;只是有上限,最久没用的先丢。
 *
 * <p>线程:任何线程。
 */
final class ModuleCache {

    private final Map<String, String> byHash;

    ModuleCache(int capacity) {
        this.byHash = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > capacity;
            }
        };
    }

    /** 这个哈希的正文;没有是 null。 */
    synchronized String get(String hash) {
        return byHash.get(hash);
    }

    synchronized void put(String hash, String code) {
        byHash.put(hash, code);
    }
}
