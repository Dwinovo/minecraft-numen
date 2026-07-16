package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import com.dwinovo.numen.core.task.pin.Fingerprints;
import com.dwinovo.numen.core.task.pin.IntentPinsData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code equip_item} on the player body. Equips the way a real player does — by holding the item and
 * RIGHT-CLICKING it: the item's own use behaviour does the equip, with the proper sound + events and
 * routing to the correct slot. That one native path covers vanilla armor (the {@code Equippable}
 * component), shields, AND modded accessories like Curios / Trinkets (their right-click handler slots
 * the item into a curio slot) — no per-mod integration. Explicit main/off-hand requests are a direct
 * item-conserving placement instead; a vanilla-slot direct set is the fallback if the right-click
 * didn't take. One-tick (all work in {@link #onStart()}).
 *
 * <p>Intent pins (constitution §5): a successful EXPLICIT equip is an intent
 * declaration — the resolved vanilla slot is pinned with the item's fingerprint,
 * so the reflex layer (armor upkeep, automatic tool swap) won't change that slot
 * until the pin's natural end. Explicit means informed consent: a fast-breaking
 * tool is equipped without objection and the hand pin keeps the guard off it for
 * the task's duration. {@code item_id="auto"} is the return path: no equip, just
 * release the given slot's pin.
 */
public final class EquipCompanionTask extends AbstractCompanionTask<EquipTaskRecord> {

    private String message = "";
    private boolean equipped = false;
    private String slotName = "";
    private TaskState result;

    public EquipCompanionTask(NumenPlayer player, EquipTaskRecord record) {
        super(player, record);
    }

    @Override
    protected List<Precondition> preconditions() {
        if (r.autoRelease) return List.of();   // a pin release needs no item in the bag
        return List.of(() -> findItem(player.getInventory()) >= 0 ? null
                : new Precondition.Failure("no " + r.label + " in inventory to equip",
                        FailureType.NO_MATERIAL));
    }

    @Override
    protected void onStart() {
        // item_id="auto": release the slot's intent pin, equip nothing (§5 归还).
        if (r.autoRelease) {
            String slot = r.slot.getName();
            boolean released = IntentPinsData.pinsFor(player).unpin(slot);
            succeed(released
                    ? "released your keep-as-is pin on " + slot + "; my gear reflexes manage that slot again"
                    : "no pin was set on " + slot + "; my gear reflexes already manage it", slot);
            return;
        }

        Inventory inv = player.getInventory();
        int invSlot = findItem(inv);   // precondition guarantees >= 0

        // Explicit hand placement: just select / set it, no right-click (we don't want to "use" a
        // shield or tool, only hold it).
        if (r.slot == EquipmentSlot.MAINHAND) {
            player.holdInHand(invSlot);
            succeed("holding " + r.label + " in main hand", "mainhand");
            return;
        }
        if (r.slot == EquipmentSlot.OFFHAND) {
            directSet(EquipmentSlot.OFFHAND, invSlot, inv.getItem(invSlot).copyWithCount(1));
            return;
        }

        // Default: equip via a native right-click. The held item's use behaviour equips it — vanilla
        // armor (Equippable), or a Curios/Trinkets accessory (its mod's right-click handler) — to the
        // right slot, with sound + events. Confirmed by the item leaving the inventory.
        Item want = inv.getItem(invSlot).getItem();
        int before = PlayerInv.count(inv, want);
        player.holdInHand(invSlot);
        player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (PlayerInv.count(inv, want) < before) {
            succeed("equipped " + r.label + slotSuffix(want), foundVanillaSlot(want));
            return;
        }

        // Right-click didn't equip it (e.g. an item with no equip-on-use behaviour). Fall back to a
        // direct vanilla-slot set. The item is currently held in the selected hotbar slot.
        ItemStack one = inv.getItem(invSlot).copyWithCount(1);
        EquipmentSlot target = resolveSlot(one);
        if (target == EquipmentSlot.MAINHAND) {
            succeed("holding " + r.label + " in main hand", "mainhand");   // already in hand
            return;
        }
        if (target != null) {
            directSet(target, inv.selected, one);
            return;
        }
        fail(r.label + " can't be equipped" + (r.slot != null ? " in " + r.slot.getName() : ""),
                FailureType.UNKNOWN);
    }

    @Override
    protected TaskState onTick() {
        return result != null ? result : TaskState.FAILED;   // fail() short-circuits before here
    }

    /** No nav / overlay to release. */
    @Override
    protected void cleanup() {}

    /** Direct, item-conserving set of one item into {@code slot}, stowing whatever was there (and
     *  dropping it only if the inventory is full). Used for off-hand and as the right-click fallback. */
    private void directSet(EquipmentSlot slot, int fromSlot, ItemStack one) {
        Inventory inv = player.getInventory();
        ItemStack previous = player.getItemBySlot(slot).copy();
        if (!previous.isEmpty() && previous.getItem() == one.getItem()) {
            succeed(r.label + " already equipped in " + slot.getName(), slot.getName());
            return;
        }
        inv.removeItem(fromSlot, 1);
        player.setItemSlot(slot, one);
        if (!previous.isEmpty()) {
            inv.add(previous);                              // mutates `previous` down by what fit
            if (!previous.isEmpty() && player.level() instanceof ServerLevel sl) {
                player.spawnAtLocation(previous);       // overflow → drop
            }
        }
        inv.setChanged();
        succeed("equipped " + r.label + " in " + slot.getName(), slot.getName());
    }

    private int findItem(Inventory inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && st.getItem() == r.item) return i;
        }
        return -1;
    }

    /** The vanilla equipment slot this item routes to, honouring an explicit non-hand request. */
    private EquipmentSlot resolveSlot(ItemStack stack) {
        if (r.slot == null) return player.getEquipmentSlotForItem(stack);
        if (r.slot == EquipmentSlot.MAINHAND || r.slot == EquipmentSlot.OFFHAND) return r.slot;
        return player.getEquipmentSlotForItem(stack) == r.slot ? r.slot : null;
    }

    /** After a right-click equip, find which vanilla armor/off-hand slot now holds the item (null = a
     *  modded accessory slot we can't name). */
    private String foundVanillaSlot(Item want) {
        for (EquipmentSlot s : EquipmentSlot.values()) {
            if (s == EquipmentSlot.MAINHAND) continue;
            if (player.getItemBySlot(s).getItem() == want) return s.getName();
        }
        return null;
    }

    private String slotSuffix(Item want) {
        String s = foundVanillaSlot(want);
        return s != null ? " in " + s : " (accessory slot)";
    }

    private void succeed(String msg, String slot) {
        message = msg;
        slotName = slot == null ? "" : slot;
        equipped = true;
        result = TaskState.SUCCESS;
        // Fall the pin (constitution §5 落钉): an explicit equip that landed in a
        // nameable vanilla slot pins that slot with the item's fingerprint. Modded
        // accessory slots (slot == null) can't be named, so they can't be pinned.
        if (!r.autoRelease && !slotName.isEmpty()) {
            IntentPinsData.pinsFor(player).pin(slotName, Fingerprints.of(r.item));
        }
        succeed();   // base: park + stamp SUCCESS so the equip finalizes this same tick
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        if (equipped && !slotName.isEmpty()) data.put("slot", slotName);
        return data;
    }

    @Override
    protected String successMessage() {
        return message;
    }

    @Override
    protected String cancelledMessage() {
        return "equip interrupted";
    }
}
