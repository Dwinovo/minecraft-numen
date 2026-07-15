package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.task.SurvivalConfig;
import com.dwinovo.numen.core.task.TaskChain;
import com.dwinovo.numen.core.task.base.ToolSelect;
import com.dwinovo.numen.core.task.survival.ArmorScore;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;

/**
 * Autonomous armor-upkeep survival chain. Scans the backpack on a slow cadence;
 * when a carried piece is strictly better ({@link ArmorScore}) than what a slot
 * wears — or a worn piece is about to break and can be stowed — it takes the body
 * for single-tick wardrobe actions, one slot per tick, biggest upgrade first, then
 * goes dormant again.
 *
 * <p>Equips the way {@code EquipCompanionTask} does: hold the candidate and
 * right-click it, letting the item's native use behaviour route it to the correct
 * slot (with sound + events) and hand back whatever was worn; a direct
 * item-conserving swap is the fallback if the right-click didn't take. A
 * nearly-broken worn piece with no replacement is moved to an empty backpack slot
 * instead — armor that shatters is gone, armor taken off can still be repaired.
 *
 * <p>Never touches: slots wearing Curse of Binding (the swap is impossible), a worn
 * elytra (deliberate flight gear, not a defense downgrade to "fix"), or candidates
 * that are cursed or nearly broken themselves. Yields whenever the body is mid
 * item-use (eating, blocking, drawing) — a hand swap would corrupt it.
 *
 * <p>GATED OFF by default via {@link SurvivalConfig}.
 */
public final class ArmorChain implements TaskChain {

    /** Dormant-state scan cadence: the wardrobe changes rarely, 2s latency is plenty. */
    private static final int SCAN_INTERVAL_TICKS = 40;

    private static final EquipmentSlot[] ARMOR_SLOTS = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    /** Diary for completed wardrobe actions — may be null (e.g. unit tests). */
    private final com.dwinovo.numen.core.task.SurvivalJournal journal;

    /** Countdown to the next dormant-state scan. */
    private int ticksUntilScan;
    /** Last scan's verdict; while true the chain re-plans every tick until settled. */
    private boolean workAvailable;

    public ArmorChain() {
        this(null);
    }

    public ArmorChain(com.dwinovo.numen.core.task.SurvivalJournal journal) {
        this.journal = journal;
    }

    @Override
    public float getPriority(NumenPlayer companion) {
        if (!SurvivalConfig.enabled()) return Float.NEGATIVE_INFINITY;
        // Yield to a body mid item-use (eating, blocking, drawing a bow): both the
        // equip right-click and the hand swap behind it would corrupt the use.
        if (companion.isUsingItem()) return Float.NEGATIVE_INFINITY;
        if (workAvailable) {
            // Active: re-plan every tick so control drops the moment the wardrobe
            // is settled instead of holding the body until the next scan window.
            workAvailable = planAction(companion) != null;
        } else {
            if (--ticksUntilScan > 0) return SurvivalDecisions.DORMANT;
            ticksUntilScan = SCAN_INTERVAL_TICKS;
            workAvailable = planAction(companion) != null;
        }
        return workAvailable ? SurvivalDecisions.ARMOR_PRIORITY : SurvivalDecisions.DORMANT;
    }

    @Override
    public void tick(NumenPlayer companion) {
        Action action = planAction(companion);
        if (action == null) return;   // priority-gated; belt-and-braces
        if (action.isStrip()) {
            strip(companion, action.slot());
        } else {
            equip(companion, action.slot(), action.invSlot());
        }
    }

    /** No cross-tick state: every equip/strip completes within its own tick. */
    @Override
    public void onInterrupt(NumenPlayer companion) {}

    @Override
    public String name() {
        return "armor";
    }

    // ---- planning ----

    /** One wardrobe action: equip backpack slot {@code invSlot} into {@code slot},
     *  or ({@code invSlot < 0}) strip the nearly-broken worn piece off {@code slot}. */
    private record Action(EquipmentSlot slot, int invSlot, float gain) {
        boolean isStrip() {
            return invSlot < 0;
        }
    }

    /**
     * The single most valuable wardrobe action right now, or {@code null} when the
     * wardrobe is settled. Slots compete by score gain (candidate − worn), so the
     * biggest upgrade is done first; a strip (gain 0) only wins when no slot has an
     * upgrade. {@link #getPriority} and {@link #tick} both call this one method, so
     * "priority says there is work" and "tick picks the action" can never disagree.
     */
    private Action planAction(NumenPlayer companion) {
        Inventory inv = companion.getInventory();
        Action best = null;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack worn = companion.getItemBySlot(slot);
            // Untouchable slots: a bound curse can't be swapped off; a worn elytra
            // is deliberate flight gear, not a defense gap to "fix".
            if (ArmorScore.isCursedOn(worn)) continue;
            if (worn.getItem() instanceof ElytraItem) continue;

            boolean wornBreaking = ToolSelect.nearBreaking(worn);
            float wornScore = ArmorScore.score(worn, slot);
            // A piece about to shatter is worth less than nothing on the body: any
            // usable candidate replaces it, even a nominally lower-scoring one.
            float barToBeat = wornBreaking ? -1.0f : wornScore;

            int candSlot = -1;
            float candScore = barToBeat;
            for (int i = 0; i < inv.items.size(); i++) {   // main storage only — never rescan worn gear
                ItemStack s = inv.items.get(i);
                if (s.isEmpty() || ArmorScore.slotOf(s) != slot) continue;
                if (ArmorScore.isCursedOn(s) || ToolSelect.nearBreaking(s)) continue;
                float score = ArmorScore.score(s, slot);
                if (score > candScore) {
                    candScore = score;
                    candSlot = i;
                }
            }

            Action action = null;
            if (candSlot >= 0) {
                action = new Action(slot, candSlot, candScore - barToBeat);
            } else if (wornBreaking && !worn.isEmpty() && firstEmptyStorageSlot(inv) >= 0) {
                action = new Action(slot, -1, 0.0f);
            }
            if (action != null && (best == null || action.gain() > best.gain())) {
                best = action;
            }
        }
        return best;
    }

    // ---- actions (one slot per tick) ----

    /**
     * Equip the candidate via a native right-click (hold it, use it): the item's
     * own use behaviour routes it to the slot and returns the displaced piece to
     * the hand. Verified against the slot's content; a direct item-conserving swap
     * is the fallback for items whose use doesn't equip.
     */
    private void equip(NumenPlayer companion, EquipmentSlot slot, int invSlot) {
        Inventory inv = companion.getInventory();
        ItemStack candidate = inv.getItem(invSlot).copy();
        ItemStack wornBefore = companion.getItemBySlot(slot);
        String oldName = wornBefore.isEmpty() ? null : wornBefore.getHoverName().getString();

        companion.holdInHand(invSlot);
        companion.gameMode.useItem(companion, companion.level(),
                companion.getMainHandItem(), InteractionHand.MAIN_HAND);

        if (!ItemStack.isSameItemSameComponents(companion.getItemBySlot(slot), candidate)) {
            // Right-click didn't equip it — swap directly (hand ↔ slot, nothing lost).
            int sel = inv.selected;
            ItemStack held = inv.getItem(sel);
            inv.setItem(sel, companion.getItemBySlot(slot));
            companion.setItemSlot(slot, held);
            inv.setChanged();
        }

        if (journal != null) {
            String name = candidate.getHoverName().getString();
            journal.note(oldName == null
                    ? "捡到" + name + ",穿上了"
                    : "捡到" + name + ",穿上了(替换" + oldName + ")");
        }
    }

    /** Stow a nearly-broken worn piece into an empty backpack slot — off the body it
     *  stops losing durability and can still be repaired. */
    private void strip(NumenPlayer companion, EquipmentSlot slot) {
        Inventory inv = companion.getInventory();
        int empty = firstEmptyStorageSlot(inv);
        if (empty < 0) return;   // plan checked; belt-and-braces
        ItemStack worn = companion.getItemBySlot(slot).copy();
        companion.setItemSlot(slot, ItemStack.EMPTY);
        inv.setItem(empty, worn);
        inv.setChanged();
        if (journal != null) {
            journal.note(worn.getHoverName().getString() + "快碎了,脱下来收好");
        }
    }

    /** First empty slot among the 36 main-storage slots (armor/offhand excluded), or -1. */
    private static int firstEmptyStorageSlot(Inventory inv) {
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).isEmpty()) return i;
        }
        return -1;
    }
}
