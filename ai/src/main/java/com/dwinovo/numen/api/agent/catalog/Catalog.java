package com.dwinovo.numen.api.agent.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 型号目录:把几个数据来源按优先级叠成一个读口。优先级是构造时传入的顺序(高在前),
 * 只在这里起作用——来源之间互不知道。当前的顺序是 运行时缓存 &gt; 随包快照;
 * 厂家自己的 {@code /models} 接口以后作为一个 {@link CatalogSource} 插在缓存之前即可。
 *
 * <p>取值按"记录"取最高的那个来源:某个型号在高优先级来源里有,就整条用它的,
 * 不拿低优先级来源的字段去补,也不猜。玩家在档案里手填的值不在这里,
 * 它们盖在目录的结果之上({@code EndpointResolver})。
 */
public final class Catalog {

    private final List<CatalogSource> sources;

    public Catalog(CatalogSource... sources) {
        this.sources = List.of(sources);
    }

    /** 只有随包快照的目录:离线工具、评测用。 */
    public static Catalog bundled() {
        return new Catalog(BundledSnapshot.INSTANCE);
    }

    /** 全部厂家:每个厂家用优先级最高的来源里的记录,顺序先按高优先级来源,再补低优先级来源独有的。 */
    public List<ProviderInfo> providers() {
        Map<String, ProviderInfo> out = new LinkedHashMap<>();
        for (CatalogSource s : sources) {
            s.providers().forEach(out::putIfAbsent);
        }
        return List.copyOf(out.values());
    }

    /** 某个厂家;目录里没有给 {@code null}。 */
    public ProviderInfo provider(String id) {
        for (CatalogSource s : sources) {
            ProviderInfo p = s.providers().get(id);
            if (p != null) return p;
        }
        return null;
    }

    /** 某个厂家的某个型号;查不到给 {@code null}(自定义型号就是这样)。 */
    public ModelInfo model(String providerId, String modelId) {
        for (CatalogSource s : sources) {
            ProviderInfo p = s.providers().get(providerId);
            ModelInfo m = p == null ? null : p.model(modelId);
            if (m != null) return m;
        }
        return null;
    }

    /** 界面先列出的常用厂家,按 {@link VendorDefaults#POPULAR} 的顺序;目录里没有的略过。 */
    public List<ProviderInfo> popular() {
        List<ProviderInfo> out = new ArrayList<>();
        for (String id : VendorDefaults.POPULAR) {
            ProviderInfo p = provider(id);
            if (p != null) out.add(p);
        }
        return out;
    }
}
