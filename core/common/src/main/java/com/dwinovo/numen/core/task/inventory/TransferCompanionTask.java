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
 * 在她打开的界面里搬一次东西。这一步要是从容器里拿东西({@link ContainerOps#taking}),点下去之前交给权限层:放行就搬,
 * 要等主人就挂在这一步上,拒绝就不搬并带上理由。其余一刻就完。
 */
public final class TransferCompanionTask extends AbstractCompanionTask<TransferTaskRecord> {

    private final ContainerOps ops = new ContainerOps();
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
        Action take = ContainerOps.taking(r.move, player);
        if (take != null) {
            Permit permit = permit(take);
            if (permit.state() == PermitState.WAITING) {
                InputDriver.halt(player);
                return TaskState.RUNNING;
            }
            if (permit.state() == PermitState.REFUSED) {
                fail("did not take " + BuiltInRegistries.ITEM.getKey(take.item()).getPath() + ": "
                        + permit.refusal(), FailureType.REFUSED);
                return TaskState.FAILED;
            }
        }
        doneMessage = ops.step(r.move, player);
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
        return "transfer timed out before the items moved";
    }

    @Override
    protected String cancelledMessage() {
        return "transfer interrupted before the items moved";
    }
}
