package com.dwinovo.numen.task.build;

import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 材料账本:此刻还需要什么、背包还差什么、缺口怎么向玩家交代。
 * 开工前置、中途报缺与收工回执共用同一个统计口径,所以玩家听到的永远
 * 是同一个问题的答案——"从现在起还要凑什么"。
 */
final class BuildLedger {

    /** 报缺料时最多点名几种。 */
    private static final int MISSING_ITEMS_LISTED = 8;

    private final BuildTaskRecord r;
    private final BuildCellRules rules;
    private final BuildInventory inv;

    BuildLedger(BuildTaskRecord r, BuildCellRules rules, BuildInventory inv) {
        this.r = r;
        this.rules = rules;
        this.inv = inv;
    }

    /**
     * 此刻还需要的材料:按物品汇总所有<b>尚未达标且要花料</b>的格。
     *
     * <p>报的是<b>存量</b>不是"本遍缺了什么":后者是过程量,玩家拿它没法决定去采多少。
     */
    Map<Item, Integer> remainingNeed() {
        Map<Item, Integer> need = new LinkedHashMap<>();
        for (BuildTaskRecord.Target target : r.targets) {
            if (!rules.costsMaterial(target)) continue;
            if (target.matches(rules.peek(target.pos()))) continue;
            // 我们不会去动的格子不该进清单。此前这里的过滤比完工统计那处少两条,
            // 于是同一张回执上能同时出现"built 812/812"和"还差 chest x2":报价
            // 索要的是永远不会被放置的格子的材料,开工前还会让玩家先去凑一堆
            // 她压根不打算放的方块。
            if (rules.blockedByMode(target) || rules.hopeless(target)) continue;
            need.merge(target.item(), target.materialCount(), Integer::sum);
        }
        return need;
    }

    /** 需求减去背包现货 = 还差多少。存量与逐格闸门、实扣共用同一个 36 格口径。 */
    Map<Item, Integer> shortfallAgainstInventory(Map<Item, Integer> need) {
        Map<Item, Integer> shortfall = new LinkedHashMap<>();
        for (var e : need.entrySet()) {
            int have = inv.mainInventoryCount(e.getKey());
            if (have < e.getValue()) {
                shortfall.put(e.getKey(), e.getValue() - have);
            }
        }
        return shortfall;
    }

    /**
     * 缺料清单:按缺口从大到小点名前几种,其余只报种类数与总件数。
     *
     * <p>一栋社区图纸能缺一百五十多种材料,全列出来是几千字符——玩家读不完,
     * 模型的上下文也白烧掉一大块,而真正决定"先去干什么"的永远是排头那几样。
     */
    static String summarizeShortfall(Map<Item, Integer> shortfall) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(shortfall.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        StringBuilder out = new StringBuilder();
        int listed = Math.min(MISSING_ITEMS_LISTED, sorted.size());
        for (int i = 0; i < listed; i++) {
            if (i > 0) out.append(", ");
            out.append(label(sorted.get(i).getKey())).append(" x").append(sorted.get(i).getValue());
        }
        if (sorted.size() > listed) {
            // 中途回执是不请自来、而且会反复出现的,所以这里保持截断。但只给一个数字
            // 等于给了个死胡同——指出哪里能拿到全量单子,才叫交代完。
            int restKinds = sorted.size() - listed;
            int restCount = 0;
            for (int i = listed; i < sorted.size(); i++) {
                restCount += sorted.get(i).getValue();
            }
            out.append(", and ").append(restKinds).append(" more kinds (")
                    .append(restCount).append(" items) — the short_of of `numen.build.diff` lists every one");
        }
        int total = 0;
        for (int v : shortfall.values()) {
            total += v;
        }
        out.append("; ").append(total).append(" items short in total");
        return out.toString();
    }

    private static String label(Item item) {
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
