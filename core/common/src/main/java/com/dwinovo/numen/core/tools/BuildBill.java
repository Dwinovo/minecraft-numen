package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 一份施工图要花的料:按物品数件数,报价与实扣用同一个件数函数({@link BuildTaskRecord.Target#materialCount})——清单与实扣
 * 一旦不同源,玩家按清单备齐了照样建到一半停下(双层砖一格两件就是这么漏掉的)。{@code build show} 给设计与蓝图文件报价
 * 都经这里。
 *
 * <p>报价不动世界一格。这件事曾经只能作为<b>失败的副产品</b>出现:得先选好位置、发起施工、被拒绝,才知道要多少料。而生存
 * 模式的价值恰恰在那条链上:她设计 → 报料 → 一起去采 → 施工。报料是第二环,不该靠撞墙触发。
 */
final class BuildBill {

    /** 一句话概览里点名几种,其余只报个数——完整清单在 data 里,那才是拿去采集的。 */
    private static final int NAMED_IN_HEADLINE = 5;
    /** 按层分布最多报几层,再多就分桶。 */
    private static final int MAX_LAYERS = 24;

    private BuildBill() {}

    /** 这些格要花的料,每种几件。{@code skip} 里的格另有料单,不进普通清单,否则同一面旗帜会被索要两次。 */
    static Map<Item, Integer> cost(Collection<BuildTaskRecord.Target> targets, java.util.Set<Long> skip) {
        Map<Item, Integer> cost = new LinkedHashMap<>();
        for (BuildTaskRecord.Target t : targets) {
            if (!t.costsMaterial() || skip.contains(t.pos().asLong())) {
                continue;
            }
            cost.merge(t.item(), t.materialCount(), Integer::sum);
        }
        return cost;
    }

    static int sum(Map<Item, Integer> m) {
        int n = 0;
        for (int v : m.values()) {
            n += v;
        }
        return n;
    }

    /**
     * 按数量降序<b>列全</b>——这张单子是拿去采集的,截断了就没法用。全量列出来几 KB,而这是她<b>建之前主动调、只调一次</b>的
     * 查询,正是该花这点 token 的地方;真正该截断的是施工中途不请自来、反复出现的缺料回执。
     */
    static Map<String, Object> summarize(Map<Item, Integer> counts) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> all = new LinkedHashMap<>();
        for (var e : sorted) {
            all.put(label(e.getKey()), e.getValue());
        }
        out.put("items", all);
        out.put("total_items", sum(counts));
        out.put("total_kinds", counts.size());
        return out;
    }

    /** 数量最多的几种,一行。 */
    static String topLine(Map<Item, Integer> counts) {
        if (counts.isEmpty()) {
            return "no materials";
        }
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        List<String> parts = new ArrayList<>();
        int listed = Math.min(NAMED_IN_HEADLINE, sorted.size());
        for (int i = 0; i < listed; i++) {
            parts.add(label(sorted.get(i).getKey()) + " x" + sorted.get(i).getValue());
        }
        String head = String.join(", ", parts);
        return sorted.size() > listed ? head + " and " + (sorted.size() - listed) + " more kinds" : head;
    }

    /**
     * 她手上还缺多少:和逐格闸门、实扣同源的 36 格口径({@link PlayerInv#buildableCount})。用 41 格的那个会让报价说"料够了"
     * 而施工每格都判缺料——副手上那叠木板正是这么骗过报价的。
     */
    static Map<Item, Integer> shortOf(NumenPlayer companion, Map<Item, Integer> need) {
        Map<Item, Integer> shortOf = new LinkedHashMap<>();
        for (var e : need.entrySet()) {
            int have = PlayerInv.buildableCount(companion.getInventory(), e.getKey());
            if (have < e.getValue()) {
                shortOf.put(e.getKey(), e.getValue() - have);
            }
        }
        return shortOf;
    }

    /**
     * 每一层有多少格。这是让她能推断"二楼大概在哪一层"的原料——只报总尺寸的话,"去掉二楼"这种要求无从下手。
     * 层数太多就分桶,免得刷屏。
     */
    static Map<String, Integer> layerProfile(Collection<BuildTaskRecord.Target> targets) {
        Map<Integer, Integer> byLayer = new TreeMap<>();
        int baseY = targets.stream().mapToInt(t -> t.pos().getY()).min().orElse(0);
        for (BuildTaskRecord.Target t : targets) {
            byLayer.merge(t.pos().getY() - baseY, 1, Integer::sum);
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        if (byLayer.isEmpty()) {
            return out;
        }
        int levels = byLayer.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        int bucket = Math.max(1, (levels + MAX_LAYERS - 1) / MAX_LAYERS);
        for (var e : byLayer.entrySet()) {
            int lo = (e.getKey() / bucket) * bucket;
            String key = bucket == 1 ? ("y+" + lo) : ("y+" + lo + ".." + (lo + bucket - 1));
            out.merge(key, e.getValue(), Integer::sum);
        }
        return out;
    }

    static String label(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
