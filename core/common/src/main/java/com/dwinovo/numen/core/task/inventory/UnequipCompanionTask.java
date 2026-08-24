package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code equip_item action=unequip} on the player body:把槽位上的东西收回背包。
 *
 * <p><b>主手是切换,不是搬运</b>:腾手优先换到空的快捷栏格(真实玩家按数字键的动作,
 * 快捷栏布局一格不动),快捷栏全满才把手持挪进背包。甲/副手直接收进背包。
 *
 * <p><b>满包在动手之前拒绝</b>:装备掉在地上比脱不下来严重得多,所以先查空位、
 * 后动槽位——绝不出现"脱下来发现没地方放,只好扔了"。批量(armor)按件推进,
 * 空间不够就停,已脱的算数、还穿着的如实报。One-tick (all work in {@link #onStart()}).
 */
public final class UnequipCompanionTask extends AbstractCompanionTask<UnequipTaskRecord> {

    private String message = "";
    private TaskState result;
    private final List<String> removed = new ArrayList<>();
    private final List<String> kept = new ArrayList<>();

    public UnequipCompanionTask(NumenPlayer player, UnequipTaskRecord record) {
        super(player, record);
    }

    /** 无前置:槽位本来就空不算错,进来如实说。 */
    @Override
    protected List<Precondition> preconditions() {
        return List.of();
    }

    @Override
    protected void onStart() {
        Inventory inv = player.getInventory();
        boolean anyWorn = false;

        for (EquipmentSlot slot : r.slots) {
            if (slot == EquipmentSlot.MAINHAND) {
                anyWorn |= freeMainHand(inv);
                continue;
            }
            ItemStack piece = player.getItemBySlot(slot);
            if (piece.isEmpty()) {
                continue;
            }
            anyWorn = true;
            if (inv.getFreeSlot() < 0) {
                kept.add(itemName(piece) + " (" + slot.getName() + ")");
                continue;
            }
            player.setItemSlot(slot, ItemStack.EMPTY);
            ItemStack stow = piece.copy();
            if (!inv.add(stow)) {
                player.setItemSlot(slot, piece);   // 放不进就原样穿回,绝不落地
                kept.add(itemName(piece) + " (" + slot.getName() + ")");
                continue;
            }
            removed.add(itemName(piece) + " (" + slot.getName() + ")");
        }
        inv.setChanged();

        if (!anyWorn) {
            succeed("nothing to take off — " + r.label + " already empty");
            return;
        }
        if (removed.isEmpty()) {
            fail("inventory is full — no room to stow " + r.label, FailureType.NO_SPACE);
            return;
        }
        succeed("took off " + String.join(", ", removed)
                + (kept.isEmpty() ? ""
                        : "; inventory full, still wearing " + String.join(", ", kept)));
    }

    /**
     * 腾主手。@return 手上原本有没有东西(空手不算"脱了什么")。
     */
    private boolean freeMainHand(Inventory inv) {
        ItemStack held = inv.getItem(inv.selected);
        if (held.isEmpty()) {
            return false;
        }
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.getItem(i).isEmpty()) {
                inv.selected = i;
                removed.add("main hand freed (switched to an empty hotbar slot, still carrying "
                        + itemName(held) + ")");
                return true;
            }
        }
        int free = inv.getFreeSlot();   // 快捷栏全满:空位只可能在主背包区
        if (free < 0) {
            kept.add(itemName(held) + " (mainhand)");
            return true;
        }
        inv.setItem(free, held.copy());
        inv.setItem(inv.selected, ItemStack.EMPTY);
        removed.add("main hand freed (stowed " + itemName(held) + ")");
        return true;
    }

    private static String itemName(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    @Override
    protected TaskState onTick() {
        return result != null ? result : TaskState.FAILED;   // fail() short-circuits before here
    }

    /** No nav / overlay to release. */
    @Override
    protected void cleanup() {}

    private void succeed(String msg) {
        message = msg;
        result = TaskState.SUCCESS;
        succeed();   // base: park + stamp SUCCESS so the unequip finalizes this same tick
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        if (!removed.isEmpty()) data.put("removed", List.copyOf(removed));
        if (!kept.isEmpty()) data.put("still_worn", List.copyOf(kept));
        return data;
    }

    @Override
    protected String successMessage() {
        return message;
    }

    @Override
    protected String cancelledMessage() {
        return "unequip interrupted";
    }
}
