package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.task.collect.CollectItemsTaskRecord;
import com.dwinovo.numen.core.task.inventory.DropItemsTaskRecord;
import com.dwinovo.numen.core.task.inventory.EatItemTaskRecord;
import com.dwinovo.numen.core.task.inventory.EquipTaskRecord;
import com.dwinovo.numen.core.task.inventory.UnequipTaskRecord;
import com.dwinovo.numen.core.gear.Wardrobe;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Inventory-management implementations — the business half of {@code gear wear} / {@code gear remove} /
 * {@code inv eat} / {@code inv drop} ({@code GearCommands}, {@code InvCommands}) and {@code CollectItemsTool}.
 * Each returns a {@link TaskRecord} the body's task queue runs; a command's records take their name, call id and
 * deadline basis from its {@link ServerSource}, a tool's from its {@link ToolContext}.
 */
public final class InventoryOps {

    private static final int DROP_MAX_COUNT = 999;

    private static final int COLLECT_DEFAULT_RADIUS = 16;
    private static final int COLLECT_MAX_RADIUS = 48;
    private static final long COLLECT_TIMEOUT_TICKS = 60 * 20;   // 1 min

    /**
     * {@code gear wear}:只做参数翻译。槽名随身体而定(模组会加槽),是不是真有这个槽、穿不穿得上,都由
     * {@link Wardrobe} 在身上答;{@code armor} 是卸下专用的别名,穿戴没有这个目标——四件甲各回各槽。
     */
    public TaskRecord wear(ServerSource source, String item_id, String slot) {
        String slotName = slotName(slot);
        if (Wardrobe.ARMOR.equals(slotName)) {
            throw new IllegalArgumentException(
                    "--slot armor is only for gear remove (it means all four armor pieces)");
        }
        Item item = ToolArgs.parseItem(item_id);
        return new EquipTaskRecord(source, item, slotName, BuiltInRegistries.ITEM.getKey(item).getPath());
    }

    /** {@code gear remove}:按槽名摘,或按物品从戴着它的格子摘;两个都没给就不知道摘什么。 */
    public TaskRecord remove(ServerSource source, String slot, String item_id) {
        String slotName = slotName(slot);
        if (slotName == null && item_id == null) {
            throw new IllegalArgumentException("slot is required for gear remove — --slot with a slot name from "
                    + "<worn>, mainhand, offhand or armor (all four armor pieces) — unless you name "
                    + "the worn item with --item");
        }
        Item item = item_id == null ? null : ToolArgs.parseItem(item_id);
        String label = slotName != null ? slotName : BuiltInRegistries.ITEM.getKey(item).getPath();
        return new UnequipTaskRecord(source, slotName, item, label);
    }

    private static String slotName(String slot) {
        return slot == null || slot.isBlank() ? null : slot.toLowerCase(Locale.ROOT);
    }

    public TaskRecord eatItem(ServerSource source, String item_id) {
        Item item = ToolArgs.parseItem(item_id);
        return new EatItemTaskRecord(source, item, BuiltInRegistries.ITEM.getKey(item).getPath());
    }

    public TaskRecord dropItems(ServerSource source, String item_id, int count) {
        Item item = ToolArgs.parseItem(item_id);
        return new DropItemsTaskRecord(source, item, Math.clamp(count, 1, DROP_MAX_COUNT),
                BuiltInRegistries.ITEM.getKey(item).getPath());
    }

    public TaskRecord collectItems(
List<String> item_ids,
Integer radius,
            ToolContext ctx) {
        // Lenient set from the id list: unparseable / unknown ids are skipped, and
        // an absent list yields an empty set — the "match everything" filter.
        Set<Item> filter = new LinkedHashSet<>();
        if (item_ids != null) {
            for (String el : item_ids) {
                if (el == null) continue;
                ResourceLocation id = ResourceLocation.tryParse(el);
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    filter.add(BuiltInRegistries.ITEM.get(id));
                }
            }
        }

        int searchRadius = COLLECT_DEFAULT_RADIUS;
        if (radius != null) {
            searchRadius = radius;
            if (searchRadius < 1) searchRadius = 1;
            if (searchRadius > COLLECT_MAX_RADIUS) searchRadius = COLLECT_MAX_RADIUS;
        }

        String label = filter.isEmpty() ? "all items" : labelFor(filter);
        return new CollectItemsTaskRecord(ctx.toolCallId(), ctx.deadline(COLLECT_TIMEOUT_TICKS),
                filter, searchRadius, label);
    }

    private static String labelFor(Set<Item> filter) {
        Item first = filter.iterator().next();
        String path = BuiltInRegistries.ITEM.getKey(first).getPath();
        return filter.size() == 1 ? path : path + "+" + (filter.size() - 1);
    }

}
