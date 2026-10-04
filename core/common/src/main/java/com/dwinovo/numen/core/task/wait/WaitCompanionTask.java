package com.dwinovo.numen.core.task.wait;

import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;


/**
 * {@code time wait} on the body: stand where she is, hands off the keys, until the time is up. It holds the task slot
 * like any other job, so the owner's stop or a new job ends it early, and the result says how long she waited.
 */
public final class WaitCompanionTask extends AbstractCompanionTask<WaitTaskRecord> {

    private long startedAt;

    public WaitCompanionTask(NumenPlayer player, WaitTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        startedAt = player.level().getGameTime();
        player.controls().stop();
    }

    @Override
    protected TaskState onTick() {
        if (player.level().getGameTime() >= r.until) {
            succeed();
            return TaskState.SUCCESS;
        }
        return TaskState.RUNNING;
    }

    /** 真等了几秒:提前被叫停时比要等的少。 */
    private double waited() {
        return Math.round((player.level().getGameTime() - startedAt) / 2.0) / 10.0;
    }

    @Override
    protected Double value() {
        return waited();
    }

    /** No nav / overlay to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected String successMessage() {
        return "waited " + waited() + "s";
    }

    @Override
    protected String cancelledMessage() {
        return "stopped waiting after " + waited() + "s of " + r.seconds + "s";
    }
}
