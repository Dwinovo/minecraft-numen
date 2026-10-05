package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.ScriptCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一段在服务端跑的程序能用的模块:开跑时的清单({@link ModuleSet#manifest})加每个指纹对应的正文,程序里按名字用到时从这里读。
 * 正文在开跑时就握在手里,所以缓存({@link ModuleCache})之后丢了谁也不碍事。
 *
 * <p>模块的真源是主人客户端的文件。程序里调的 {@code numen.module.save} 这类客户端函数改了文件之后,答复里带着新的清单与新正文
 * ({@link #learn}),这里换上——同一段程序往后用到的就是新的;已经装进虚拟机的那一份不变,和以前直接读文件一样。
 *
 * <p>线程:{@link ScriptCatalog.ModuleSource} 的两个方法在脚本的线程上调,{@link #learn} 在服务端主线程上调。
 */
public final class RunModules implements ScriptCatalog.ModuleSource {

    /** 开跑时的清单里有的正文,缓存里和这次带来的都没有的指纹;空表示齐了。 */
    public record Opened(RunModules modules, List<String> missing) {}

    private final UUID owner;
    private final ModuleCache cache;
    /** 模块名 → 指纹,按名字排;换新清单时整张换。 */
    private volatile Map<String, String> manifest;
    /** 指纹 → 正文,只增不减:这段程序往后任何一份清单要用的旧正文都还在。 */
    private final Map<String, String> bodies = new ConcurrentHashMap<>();

    private RunModules(UUID owner, ModuleCache cache, Map<String, String> manifest) {
        this.owner = owner;
        this.cache = cache;
        this.manifest = manifest;
    }

    /**
     * 打开这位主人的这份清单:带来的正文先进缓存,清单里的每个指纹从带来的或缓存里取;取不到的列在 {@code missing},客户端再送。
     *
     * @throws IllegalArgumentException 带来的某份正文和它的指纹对不上
     */
    public static Opened open(UUID owner, ModuleSet set, ModuleCache cache) {
        set.bodies().forEach((hash, code) -> cache.put(owner, hash, code));
        RunModules modules = new RunModules(owner, cache, new TreeMap<>(set.manifest()));
        List<String> missing = new ArrayList<>();
        for (String hash : new java.util.LinkedHashSet<>(set.manifest().values())) {
            String code = set.bodies().containsKey(hash) ? set.bodies().get(hash) : cache.get(owner, hash);
            if (code == null) {
                missing.add(hash);
            } else {
                modules.bodies.put(hash, code);
            }
        }
        return new Opened(modules, List.copyOf(missing));
    }

    /**
     * 客户端的模块变了:换上它交来的清单和新正文(新正文同时进缓存,下一段程序直接用)。
     *
     * @throws IllegalArgumentException 带来的某份正文和它的指纹对不上
     */
    public void learn(ModuleSet now) {
        now.bodies().forEach((hash, code) -> {
            cache.put(owner, hash, code);
            bodies.put(hash, code);
        });
        for (String hash : now.manifest().values()) {
            if (!bodies.containsKey(hash)) {
                String cached = cache.get(owner, hash);
                if (cached != null) {
                    bodies.put(hash, cached);
                }
            }
        }
        manifest = new TreeMap<>(now.manifest());
    }

    @Override
    public String code(String name) {
        String hash = manifest.get(name);
        return hash == null ? null : bodies.get(hash);
    }

    @Override
    public List<String> names() {
        return List.copyOf(manifest.keySet());
    }
}
