package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.PlayerInv;
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
 * {@code numen.inv.eat} / {@code numen.inv.drop} ({@code GearCommands}, {@code InvCommands}) and picking up (the library {@code numen.work.collect} walks onto drops).
 * Each returns a {@link TaskRecord} the body's task queue runs, which takes its name, call id and deadline basis
 * from the call's {@link ServerSource}.
 */
public final class InventoryOps {

    private static final int DROP_MAX_COUNT = 999;

    /**
     * {@code gear wear}:只做参数翻译。槽名随身体而定(模组会加槽),是不是真有这个槽、穿不穿得上,都由
     * {@link Wardrobe} 在身上答;{@code armor} 是卸下专用的别名,穿戴没有这个目标——四件甲各回各槽。
     */
    public TaskRecord wear(ServerSource source, String item_id, String slot) {
        String slotName = slotName(slot);
        if (Wardrobe.ARMOR.equals(slotName)) {
            throw new IllegalArgumentException(
                    "slot = \"armor\" is only for numen.gear.remove (it means all four armor pieces)");
        }
        Item item = ToolArgs.parseItem(item_id);
        return new EquipTaskRecord(source, item, slotName, BuiltInRegistries.ITEM.getKey(item).getPath());
    }

    /** {@code gear remove}:按槽名摘,或按物品从戴着它的格子摘;两个都不写时摘什么由命令定(盔甲)。 */
    public TaskRecord remove(ServerSource source, String slot, String item_id) {
        String slotName = slotName(slot);
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

    /** {@code inv drop}:{@code count} 没给就是她带着的全部(至少一件,一件都没有由任务的前置条件如实说)。 */
    public TaskRecord dropItems(ServerSource source, String itemId, Integer count) {
        Item item = ToolArgs.parseItem(itemId);
        int carried = PlayerInv.count(source.companion().getInventory(), item);
        int n = count == null ? Math.max(1, carried) : count;
        return new DropItemsTaskRecord(source, item, Math.clamp(n, 1, DROP_MAX_COUNT),
                BuiltInRegistries.ITEM.getKey(item).getPath());
    }

}
