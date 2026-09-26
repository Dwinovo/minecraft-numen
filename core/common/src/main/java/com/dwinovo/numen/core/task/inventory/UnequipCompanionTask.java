package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.core.gear.Wardrobe;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;

import java.util.Map;

/**
 * {@code gear remove} on the player body:在身体的任务槽里跑 {@link Wardrobe#remove}。
 * 摘不摘得下、背包放不放得下、腾主手怎么腾都在那里;这里只把结论交进回执。
 * One-tick (all work in {@link #onStart()}).
 */
public final class UnequipCompanionTask extends AbstractCompanionTask<UnequipTaskRecord> {

    private Wardrobe.Outcome outcome;

    public UnequipCompanionTask(NumenPlayer player, UnequipTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        outcome = Wardrobe.remove(player, r.slot, r.item);
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
        return "unequip interrupted";
    }
}
