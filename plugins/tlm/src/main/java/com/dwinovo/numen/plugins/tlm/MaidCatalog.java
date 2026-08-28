package com.dwinovo.numen.plugins.tlm;

import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.CustomModelPack;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.MaidModelInfo;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把车万女仆的模型目录整理成<b>人能读的样子</b>再交给大模型。
 *
 * <h2>为什么不能把 id 直接倒出去</h2>
 * 这台机器上有两百多个模型,id 长这样:
 * {@code geckolib:winefox_magical_a12dc19190c076d8d24aff8714515c8e}。全量倒进
 * 工具结果里,一次就吃掉两万多 token,而且模型拿到的是一堆看不懂的哈希——
 * 它没法跟主人讲"哪个是哪个",只能泛泛复述工具本身。
 *
 * <p>所以:不带关键词就只给<b>包级摘要</b>(每个包几个模型、举几个名字),
 * 带关键词才展开具体条目并封顶。跟引擎的 {@code find_tools} 一个路子。
 *
 * <h2>名字从哪来</h2>
 * 每个模型都有正经名字,只是以 {@code {model.<命名空间>.<路径>.name}} 的形式存着
 * ——{@code MaidModelInfo.getName()} 在包里没写 name 时会自动拼出这个键。车万女仆
 * 用 {@code LanguageMixin} 把各模型包的 lang 灌进了香草语言表,所以 {@link I18n}
 * 直接查得到。不用自己维护映射表。
 */
public final class MaidCatalog {

    /** 带关键词时最多展开多少条——再多模型也读不完,只会把上下文撑爆。 */
    private static final int MAX_HITS = 40;

    /** 包级摘要里每个包举几个例子。 */
    private static final int SAMPLES = 3;

    private MaidCatalog() {}

    /** 一条目录项。 */
    public record Entry(String id, String name, String pack) {}

    /**
     * {@code {some.lang.key}} → 翻好的名字。翻不出来就原样返回:吞掉信息比
     * 显示一个键更糟,至少键还看得出是哪个模型。
     */
    public static String display(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String t = raw.trim();
        if (t.length() > 2 && t.charAt(0) == '{' && t.charAt(t.length() - 1) == '}') {
            String key = t.substring(1, t.length() - 1);
            String v = I18n.get(key);
            return v.equals(key) ? t : v;
        }
        return t;
    }

    /** 这个模型的显示名;查不到就退回 id。 */
    public static String nameOf(String modelId) {
        return Tlm.info(modelId).map(i -> display(i.getName())).orElse(modelId);
    }

    /**
     * 这个模型的介绍(包作者写的那几行),已翻好并去掉 §颜色码——它进的是提示词,
     * 颜色码只会占 token。查不到返回空串。
     */
    public static String descOf(String modelId) {
        return Tlm.info(modelId).map(i -> {
            List<String> raw = i.getDescription();
            if (raw == null || raw.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String line : raw) {
                String t = display(line);
                if (t == null || t.isBlank()) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(t.replaceAll("§.", ""));
            }
            return sb.toString().trim();
        }).orElse("");
    }

    /** 全部条目,按包的顺序。 */
    public static List<Entry> all() {
        List<Entry> out = new ArrayList<>();
        for (CustomModelPack<MaidModelInfo> pack : Tlm.packs()) {
            String packName = display(pack.getPackName());
            for (MaidModelInfo info : pack.getModelList()) {
                String id = info.getModelId().toString();
                out.add(new Entry(id, display(info.getName()), packName));
            }
        }
        return out;
    }

    /** 包级摘要:{包名: {count, samples}}。不带关键词时给这个。 */
    public static Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (CustomModelPack<MaidModelInfo> pack : Tlm.packs()) {
            List<MaidModelInfo> list = pack.getModelList();
            if (list.isEmpty()) continue;
            List<String> samples = new ArrayList<>();
            for (int i = 0; i < Math.min(SAMPLES, list.size()); i++) {
                samples.add(display(list.get(i).getName()));
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("count", list.size());
            one.put("examples", samples);
            out.put(display(pack.getPackName()), one);
        }
        return out;
    }

    /** 按关键词找,名字和 id 都匹配。返回值封顶 {@link #MAX_HITS}。 */
    public static List<Entry> search(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        List<Entry> hits = new ArrayList<>();
        for (Entry e : all()) {
            if (hits.size() >= MAX_HITS) break;
            boolean m = e.id().toLowerCase(Locale.ROOT).contains(q)
                    || (e.name() != null && e.name().toLowerCase(Locale.ROOT).contains(q))
                    || (e.pack() != null && e.pack().toLowerCase(Locale.ROOT).contains(q));
            if (m) hits.add(e);
        }
        return hits;
    }
}
