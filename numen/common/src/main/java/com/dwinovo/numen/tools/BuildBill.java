package com.dwinovo.numen.tools;

import com.dwinovo.numen.PlayerInv;
import com.dwinovo.numen.task.build.BuildTaskRecord;
import com.dwinovo.numen.api.entity.NumenPlayer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一串格要花的料:按物品数件数,报价与实扣用同一个件数函数({@link BuildTaskRecord.Target#materialCount})——清单与实扣
 * 一旦不同源,玩家按清单备齐了照样建到一半停下(双层砖一格两件就是这么漏掉的)。{@code numen.build.diff} 给还剩的格报价
 * 经这里:她自己设计的一串格和别处交来的一串格,报的是同一个价。
 *
 * <p>报价不动世界一格,也不靠撞墙触发:她设计 → 报料 → 一起去采 → 施工,报料是第二环。
 */
final class BuildBill {

    private BuildBill() {}

    /** 这些格要花的料,每种几件。 */
    static Map<Item, Integer> cost(Collection<BuildTaskRecord.Target> targets) {
        Map<Item, Integer> cost = new LinkedHashMap<>();
        for (BuildTaskRecord.Target t : targets) {
            if (!t.costsMaterial()) {
                continue;
            }
            cost.merge(t.item(), t.materialCount(), Integer::sum);
        }
        return cost;
    }

    /**
     * 每种几件,按数量降序<b>列全</b>——这张单子是拿去采集的,截断了就没法用。全量列出来几 KB,而这是她<b>建之前主动调、只调一次</b>
     * 的查询,正是该花这点 token 的地方;真正该截断的是施工中途不请自来、反复出现的缺料回执。
     */
    static Map<String, Integer> items(Map<Item, Integer> counts) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        Map<String, Integer> all = new LinkedHashMap<>();
        for (var e : sorted) {
            all.put(label(e.getKey()), e.getValue());
        }
        return all;
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

    static String label(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
