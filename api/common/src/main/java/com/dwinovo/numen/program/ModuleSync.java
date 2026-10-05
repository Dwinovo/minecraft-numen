package com.dwinovo.numen.program;

import com.dwinovo.numen.script.Modules;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端这一侧:这个连接上已经送过服务端哪些模块正文。程序出发时清单({@link ModuleSet#manifest})总是全的,正文只带没送过的——
 * 和 Redis 的 EVALSHA、Bazel 的 FindMissingBlobs 是同一个办法。服务端说它没有某些正文({@link #lost})、或者连接断了
 * ({@link #reset}),这些就当没送过。
 */
public final class ModuleSync {

    private final Set<String> sent = ConcurrentHashMap.newKeySet();

    /**
     * 她此刻的模块({@link Modules#sources}:名字 → 正文)打成一份快照,带上的正文记作已经送过。
     */
    public ModuleSet pack(Map<String, String> sources) {
        Map<String, String> manifest = new TreeMap<>();
        Map<String, String> bodies = new LinkedHashMap<>();
        sources.forEach((name, code) -> {
            String hash = Modules.fingerprint(code);
            manifest.put(name, hash);
            if (sent.add(hash)) {
                bodies.put(hash, code);
            }
        });
        return new ModuleSet(manifest, bodies);
    }

    /** 这些正文服务端其实没有(缓存丢了、包没送出去):下次重新带上。 */
    public void lost(Collection<String> hashes) {
        sent.removeAll(hashes);
    }

    /** 连接断了:服务端按主人断线清掉了缓存。 */
    public void reset() {
        sent.clear();
    }
}
