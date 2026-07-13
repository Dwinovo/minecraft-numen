package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.exec.InputDriver;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;

import java.util.Map;

/**
 * {@code wait} on the player body: idle in place for the requested duration
 * (game-time based, freeze/tick-rate aware). Player-body twin of WaitTaskGoal.
 */
public final class WaitCompanionTask extends AbstractCompanionTask<WaitTaskRecord> {

    private long wakeAtGameTime;

    public WaitCompanionTask(NumenPlayer player, WaitTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        this.wakeAtGameTime = player.level().getGameTime() + r.seconds * 20L;
        InputDriver.halt(player);
    }

    @Override
    protected TaskState onTick() {
        InputDriver.halt(player);   // hold still while idling
        return player.level().getGameTime() >= wakeAtGameTime ? TaskState.SUCCESS : TaskState.RUNNING;
    }

    /** No nav / overlay to release — keep the original's empty cleanup. */
    @Override
    protected void cleanup() {}

    @Override
    protected Map<String, Object> resultData() {
        return Map.of("seconds", r.seconds);
    }

    @Override
    protected String successMessage() {
        return "waited " + label();
    }

    @Override
    protected String timeoutMessage() {
        return "wait timed out unexpectedly";
    }

    @Override
    protected String cancelledMessage() {
        return "wait interrupted before " + label() + " elapsed";
    }

    private String label() {
        return r.seconds + "s" + (r.reason.isEmpty() ? "" : " (" + r.reason + ")");
    }
}
