package com.dwinovo.numen.task.inventory;

import com.dwinovo.numen.api.task.Activity;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.tools.ContainerOps;
import com.dwinovo.numen.api.task.TaskRecord;

/**
 * 在她打开的界面里搬一次东西({@code gui move} 或 {@code gui quick}):一次调用一步。要搬好几样就同一轮发好几行,
 * 串行的派发器一行一行排开。
 */
public final class TransferTaskRecord extends TaskRecord<Void> {

    public final ContainerOps.Move move;

    public TransferTaskRecord(ServerCall source, long deadlineGameTime, ContainerOps.Move move) {
        super(source, deadlineGameTime);
        this.move = move;
    }

    @Override
    public String describe() {
        return getToolName() + " " + move.from() + (move.to() == null ? "" : " " + move.to());
    }

    /** 给主人看的一句(头顶气泡、档案页),客户端按当前语言翻;{@link #describe()} 是给模型的。 */
    @Override
    public Activity activity() {
        return Activity.of("numen.activity.transfer");
    }
}
