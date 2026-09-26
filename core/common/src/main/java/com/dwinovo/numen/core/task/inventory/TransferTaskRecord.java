package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.tools.ContainerOps;
import com.dwinovo.numen.task.TaskRecord;

/**
 * 在她打开的界面里搬一次东西({@code use transfer} 或 {@code use shift}):一次调用一步。要搬好几样就同一轮发好几行,
 * 串行的派发器一行一行排开。
 */
public final class TransferTaskRecord extends TaskRecord {

    public final ContainerOps.Move move;

    public TransferTaskRecord(ServerSource source, long deadlineGameTime, ContainerOps.Move move) {
        super(source, deadlineGameTime);
        this.move = move;
    }

    @Override
    public String describe() {
        return getToolName() + " " + move.from() + (move.to() == null ? "" : " " + move.to());
    }
}
