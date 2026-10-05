package com.dwinovo.numen.program;

import com.dwinovo.numen.script.Modules;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端按内容缓存主人送来的模块正文:指纹 → 正文({@link Modules#fingerprint}),按主人分开,只在内存里,主人断线就清掉。
 * 真源在主人客户端的文件;这里只是让同一份正文不必每段程序都送一遍。每位主人总字节有上限
 * ({@link ProgramLimits#MODULE_CACHE_BYTES}),满了丢最久没用的——丢了也不碍事,客户端被告知缺哪些再送一次。
 *
 * <p>存进去的正文必须和它的指纹对得上:指纹就是内容的名字,对不上的不收。线程:任何线程。
 */
public final class ModuleCache {

    private static final class Store {
        /** 按最近使用排,最久没用的在前。 */
        final Map<String, String> bodies = new LinkedHashMap<>(16, 0.75f, true);
        long bytes;
    }

    private final Map<UUID, Store> owners = new ConcurrentHashMap<>();
    private final long capacity;

    public ModuleCache(long capacity) {
        this.capacity = capacity;
    }

    /** 这位主人缓存里这个指纹的正文;没有是 null。 */
    public String get(UUID owner, String hash) {
        Store store = owners.get(owner);
        if (store == null) {
            return null;
        }
        synchronized (store) {
            return store.bodies.get(hash);
        }
    }

    /**
     * 存一份。
     *
     * @throws IllegalArgumentException 正文的指纹不是 {@code hash}
     */
    public void put(UUID owner, String hash, String code) {
        if (!Modules.fingerprint(code).equals(hash)) {
            throw new IllegalArgumentException("a module body does not match its fingerprint " + hash);
        }
        Store store = owners.computeIfAbsent(owner, id -> new Store());
        synchronized (store) {
            if (store.bodies.put(hash, code) == null) {
                store.bytes += size(code);
            }
            Iterator<Map.Entry<String, String>> oldest = store.bodies.entrySet().iterator();
            while (store.bytes > capacity && oldest.hasNext()) {
                Map.Entry<String, String> entry = oldest.next();
                if (entry.getKey().equals(hash)) {
                    continue;   // 刚存的这份留着:单份比上限大也要让正在用它的这段程序用上
                }
                store.bytes -= size(entry.getValue());
                oldest.remove();
            }
        }
    }

    /** 这位主人缓存了多少字节。 */
    public long bytes(UUID owner) {
        Store store = owners.get(owner);
        if (store == null) {
            return 0;
        }
        synchronized (store) {
            return store.bytes;
        }
    }

    /** 主人断线:他的缓存清掉。 */
    public void drop(UUID owner) {
        owners.remove(owner);
    }

    /** 关服:全清。 */
    public void clear() {
        owners.clear();
    }

    private static long size(String code) {
        return code.getBytes(StandardCharsets.UTF_8).length;
    }
}
