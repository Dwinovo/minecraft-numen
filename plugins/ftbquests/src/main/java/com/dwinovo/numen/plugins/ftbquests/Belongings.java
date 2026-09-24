package com.dwinovo.numen.plugins.ftbquests;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 某一刻她身上有什么:背包每格的物品与数量,加上经验。拿来和之后的她比,说出这中间实际多了、少了什么。
 *
 * <p>只认"什么物品、几个"——耐久、附魔等组件不比。每刻都要记一次,所以就地覆盖两个数组,不分配。
 */
final class Belongings {

    private final Item[] items;
    private final int[] counts;
    private int level;
    private int points;

    Belongings(int slots) {
        items = new Item[slots];
        counts = new int[slots];
    }

    void copyFrom(Player body) {
        Inventory inv = body.getInventory();
        for (int i = 0; i < items.length; i++) {
            items[i] = inv.getItem(i).getItem();
            counts[i] = inv.getItem(i).getCount();
        }
        level = body.experienceLevel;
        points = body.totalExperience;
    }

    /** 从记下的那一刻到现在的变化,形如 {@code +3 minecraft:diamond, -1 minecraft:apple; experience +100 points};没变是空串。 */
    String changeTo(Player body) {
        Map<Item, Integer> delta = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i++) {
            delta.merge(items[i], -counts[i], Integer::sum);
        }
        Inventory inv = body.getInventory();
        for (int i = 0; i < items.length; i++) {
            delta.merge(inv.getItem(i).getItem(), inv.getItem(i).getCount(), Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        delta.forEach((item, n) -> {
            if (n != 0) {
                parts.add(signed(n) + " " + BuiltInRegistries.ITEM.getKey(item));
            }
        });
        String text = String.join(", ", parts);
        List<String> xp = new ArrayList<>();
        if (body.totalExperience != points) {
            xp.add(signed(body.totalExperience - points) + " points");
        }
        if (body.experienceLevel != level) {
            xp.add("level " + level + " -> " + body.experienceLevel);
        }
        if (!xp.isEmpty()) {
            text += (text.isEmpty() ? "" : "; ") + "experience " + String.join(", ", xp);
        }
        return text;
    }

    private static String signed(int n) {
        return (n > 0 ? "+" : "") + n;
    }
}
