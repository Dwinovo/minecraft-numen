package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.tools.ContainerOps;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.registries.BuiltInRegistries;

/**
 * {@code transfer} on the player body: run the moves in order in the open GUI. A move that takes something
 * out of a container ({@link ContainerOps#taking}) is handed to the permission layer right before its clicks:
 * allowed, it runs; waiting for the owner, the call hangs on that move; refused, that move is skipped with the
 * reason and the result is a refusal. Everything else is one tick.
 */
public final class TransferCompanionTask extends AbstractCompanionTask<TransferTaskRecord> {

    private final ContainerOps ops = new ContainerOps();
    private final StringBuilder lines = new StringBuilder();
    /** 下一步是第几步(从 0 数);等主人答复时停在要问的那一步。 */
    private int next;
    private boolean refused;
    private String doneMessage = "done";

    public TransferCompanionTask(NumenPlayer player, TransferTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        run();
    }

    @Override
    protected TaskState onTick() {
        return run();
    }

    private TaskState run() {
        while (next < r.moves.size()) {
            ContainerOps.Move move = r.moves.get(next);
            Action take = ContainerOps.taking(move, player);
            String line;
            if (take == null) {
                line = ops.step(move, player);
            } else {
                Permit permit = permit(take, r.describe());
                if (permit.state() == PermitState.WAITING) {
                    InputDriver.halt(player);
                    return TaskState.RUNNING;
                }
                if (permit.state() == PermitState.REFUSED) {
                    refused = true;
                    line = "did not take " + BuiltInRegistries.ITEM.getKey(take.item()).getPath() + ": "
                            + permit.refusal();
                } else {
                    line = ops.step(move, player);
                }
            }
            lines.append(next + 1).append(". ").append(line).append('\n');
            next++;
        }
        String text = lines.toString().stripTrailing();
        if (refused) {
            fail(text, FailureType.REFUSED);
            return TaskState.FAILED;
        }
        doneMessage = text;
        succeed();
        return TaskState.SUCCESS;
    }

    /** No nav / overlay to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected String successMessage() {
        return doneMessage;
    }

    @Override
    protected String timeoutMessage() {
        return "transfer timed out" + (lines.isEmpty() ? "" : " after: " + lines.toString().stripTrailing());
    }

    @Override
    protected String cancelledMessage() {
        return "transfer interrupted" + (lines.isEmpty() ? "" : " after: " + lines.toString().stripTrailing());
    }
}
