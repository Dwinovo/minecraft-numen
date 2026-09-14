package com.dwinovo.numen.core.task.inventory;

import com.dwinovo.numen.core.tools.ContainerOps;
import com.dwinovo.numen.task.TaskRecord;

import java.util.List;

/**
 * Typed task descriptor for {@code transfer}: the slot-to-slot moves to run, in order, in the
 * GUI the body has open.
 */
public final class TransferTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "transfer";

    public final List<ContainerOps.Move> moves;

    public TransferTaskRecord(String toolCallId, long deadlineGameTime, List<ContainerOps.Move> moves) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.moves = List.copyOf(moves);
    }

    @Override
    public String describe() {
        return TOOL_NAME + " " + moves.size() + " move(s) in the open GUI";
    }
}
