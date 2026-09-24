package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.core.gear.Wardrobe;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;

import java.util.Map;

/**
 * {@code equip_item} on the player body: runs {@link Wardrobe#wear} in the body's task slot. The
 * wearing, the auto-choosing of a slot and the refusals all live there; this task only carries the
 * outcome into the result envelope. One-tick (all work in {@link #onStart()}).
 *
 * <p>Nothing is "used" (right-clicked) here: equipping moves an item between the backpack and the
 * body, so it never pours a bucket or throws a snowball.
 */
public final class EquipCompanionTask extends AbstractCompanionTask<EquipTaskRecord> {

    private Wardrobe.Outcome outcome;

    public EquipCompanionTask(NumenPlayer player, EquipTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        outcome = Wardrobe.wear(player, r.item, r.slot);
        if (outcome.ok()) {
            succeed();
        } else {
            fail(outcome.message(), outcome.failure());
        }
    }

    @Override
    protected TaskState onTick() {
        return TaskState.FAILED;   // onStart always parks a terminal first
    }

    /** No nav / overlay to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected Map<String, Object> resultData() {
        return outcome == null ? super.resultData() : outcome.data();
    }

    @Override
    protected String successMessage() {
        return outcome.message();
    }

    @Override
    protected String cancelledMessage() {
        return "equip interrupted";
    }
}
