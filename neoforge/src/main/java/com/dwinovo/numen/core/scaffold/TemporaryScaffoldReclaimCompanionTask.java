package com.dwinovo.numen.core.scaffold;

import com.dwinovo.numen.core.task.FailureType;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TemporaryScaffoldReclaimCompanionTask
    extends AbstractCompanionTask<TemporaryScaffoldReclaimTaskRecord> {

    private TemporaryScaffoldReclaimProgress progress;

    public TemporaryScaffoldReclaimCompanionTask(
        NumenPlayer player,
        TemporaryScaffoldReclaimTaskRecord record
    ) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        int initial = remaining();
        progress = new TemporaryScaffoldReclaimProgress(initial, player.level().getGameTime());
        if (initial == 0) {
            succeed();
            return;
        }
        TemporaryScaffoldController.beginExplicit(player);
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }

        TemporaryScaffoldController.tickExplicit(player);
        int remaining = remaining();
        TemporaryScaffoldReclaimProgress.State state = progress.observe(
            remaining,
            TemporaryScaffoldController.hasActionableCleanup(player),
            player.level().getGameTime()
        );
        return switch (state) {
            case RUNNING -> TaskState.RUNNING;
            case COMPLETE -> TaskState.SUCCESS;
            case BLOCKED -> finishIncomplete(
                "remaining tracked scaffolds are currently unsafe or unreachable",
                FailureType.HAZARD
            );
            case STALLED -> finishIncomplete(
                "cleanup stopped after 30 seconds without reclaiming another tracked scaffold",
                FailureType.OUT_OF_REACH
            );
        };
    }

    private TaskState finishIncomplete(String reason, FailureType type) {
        fail(
            "reclaimed " + reclaimed() + "/" + progress.initialCount() + "; " + reason,
            type
        );
        return TaskState.FAILED;
    }

    @Override
    protected void cleanup() {
        TemporaryScaffoldController.endExplicit(player);
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new LinkedHashMap<>();
        int initial = progress == null ? remaining() : progress.initialCount();
        data.put("tracked", initial);
        data.put("reclaimed", progress == null ? 0 : progress.reclaimed(remaining()));
        data.put("remaining", remaining());
        return data;
    }

    @Override
    protected String successMessage() {
        return "reclaimed " + reclaimed() + "/" + progress.initialCount()
            + " tracked temporary scaffolds";
    }

    @Override
    protected String timeoutMessage() {
        return "temporary scaffold cleanup timed out after reclaiming " + reclaimed()
            + "/" + progress.initialCount();
    }

    @Override
    protected String cancelledMessage() {
        return "temporary scaffold cleanup stopped after reclaiming " + reclaimed()
            + "/" + progress.initialCount();
    }

    private int reclaimed() {
        return progress == null ? 0 : progress.reclaimed(remaining());
    }

    private int remaining() {
        return TemporaryScaffoldLedger.entries(player.getUUID()).size();
    }
}
