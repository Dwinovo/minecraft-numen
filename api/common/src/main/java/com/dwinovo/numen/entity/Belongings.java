package com.dwinovo.numen.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
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
public final class Belongings {

    private final Item[] items;
    private final int[] counts;
    private int level;
    private int points;

    public Belongings(int slots) {
        items = new Item[slots];
        counts = new int[slots];
    }

    /** 此刻的她。 */
    public static Belongings of(Player body) {
        Belongings now = new Belongings(body.getInventory().getContainerSize());
        now.copyFrom(body);
        return now;
    }

    public void copyFrom(Player body) {
        copyItems(body.getInventory());
        level = body.experienceLevel;
        points = body.totalExperience;
    }

    /** 只记物品那一半(经验不在容器里)。 */
    void copyItems(Container inv) {
        for (int i = 0; i < items.length; i++) {
            items[i] = inv.getItem(i).getItem();
            counts[i] = inv.getItem(i).getCount();
        }
    }

    /** 从记下的那一刻到现在的变化,形如 {@code +3 minecraft:diamond, -1 minecraft:apple; experience +100 points};没变是空串。 */
    public String changeTo(Player body) {
        String items = itemChange(body);
        String xp = experienceChange(body);
        return xp.isEmpty() ? items : items.isEmpty() ? xp : items + "; " + xp;
    }

    /** 背包(含穿戴与副手)里物品的增减,形如 {@code +3 minecraft:diamond, -1 minecraft:apple};没变是空串。 */
    public String itemChange(Player body) {
        return itemChange(body.getInventory());
    }

    String itemChange(Container now) {
        Map<Item, Integer> delta = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i++) {
            delta.merge(items[i], -counts[i], Integer::sum);
        }
        for (int i = 0; i < items.length; i++) {
            delta.merge(now.getItem(i).getItem(), now.getItem(i).getCount(), Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        delta.forEach((item, n) -> {
            if (n != 0) {
                parts.add(signed(n) + " " + BuiltInRegistries.ITEM.getKey(item));
            }
        });
        return String.join(", ", parts);
    }

    /** 经验的变化,形如 {@code experience +100 points, level 3 -> 4};没变是空串。 */
    public String experienceChange(Player body) {
        List<String> xp = new ArrayList<>();
        if (body.totalExperience != points) {
            xp.add(signed(body.totalExperience - points) + " points");
        }
        if (body.experienceLevel != level) {
            xp.add("level " + level + " -> " + body.experienceLevel);
        }
        return xp.isEmpty() ? "" : "experience " + String.join(", ", xp);
    }

    private static String signed(int n) {
        return (n > 0 ? "+" : "") + n;
    }
}
