package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.ToolContext;
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
import java.util.Set;

/**
 * Inventory-management tool implementations — the business half of
 * {@code EquipItemTool} / {@code EatItemTool} / {@code DropItemsTool} /
 * {@code CollectItemsTool}. Each returns a {@link TaskRecord} the body's task
 * queue runs; the {@link ToolContext} carries the call id and deadline basis.
 */
public final class InventoryOps {

    private static final long EQUIP_TIMEOUT_TICKS = 5 * 20;   // instant; generous floor

    /** Generous — covers any food's eat duration (most ~1.6s) plus buffer. */
    private static final long EAT_TIMEOUT_TICKS = 15 * 20;

    private static final int DROP_MAX_COUNT = 999;
    private static final long DROP_TIMEOUT_TICKS = 10 * 20;

    private static final int COLLECT_DEFAULT_RADIUS = 16;
    private static final int COLLECT_MAX_RADIUS = 48;
    private static final long COLLECT_TIMEOUT_TICKS = 60 * 20;   // 1 min

    public TaskRecord equipItem(
String action,
String item_id,
String slot,
            ToolContext ctx) {
        // 只做参数翻译:槽名随身体而定(模组会加槽),是不是真有这个槽、穿不穿得上,都由 Wardrobe 在身上答
        String slotName = slot == null || slot.isBlank() ? null : slot.toLowerCase();
        boolean hasItem = item_id != null && !item_id.isBlank();
        if ("unequip".equalsIgnoreCase(action)) {
            if (slotName == null && !hasItem) {
                throw new IllegalArgumentException("slot is required for unequip — a slot name from "
                        + "<worn>, mainhand, offhand or 'armor' (all four armor pieces) — unless you name "
                        + "the worn item with item_id");
            }
            Item item = hasItem ? ToolArgs.parseItem(item_id) : null;
            String label = slotName != null ? slotName : BuiltInRegistries.ITEM.getKey(item).getPath();
            return new UnequipTaskRecord(ctx.toolCallId(), ctx.deadline(EQUIP_TIMEOUT_TICKS),
                    slotName, item, label);
        }
        if (!hasItem) {
            throw new IllegalArgumentException(
                    "item_id is required to equip (to take gear off, use action=unequip with a slot)");
        }
        if (Wardrobe.ARMOR.equals(slotName)) {
            throw new IllegalArgumentException(
                    "slot=armor is only for action=unequip (it means all four armor pieces)");
        }

        Item item = ToolArgs.parseItem(item_id);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new EquipTaskRecord(ctx.toolCallId(), ctx.deadline(EQUIP_TIMEOUT_TICKS), item, slotName, label);
    }

    public TaskRecord eatItem(
String item_id,
            ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new EatItemTaskRecord(ctx.toolCallId(), ctx.deadline(EAT_TIMEOUT_TICKS), item, label);
    }

    public TaskRecord dropItems(
String item_id,
int count,
            ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        count = Math.clamp(count, 1, DROP_MAX_COUNT);
        String label = BuiltInRegistries.ITEM.getKey(item).getPath();
        return new DropItemsTaskRecord(ctx.toolCallId(), ctx.deadline(DROP_TIMEOUT_TICKS),
                item, count, label);
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
