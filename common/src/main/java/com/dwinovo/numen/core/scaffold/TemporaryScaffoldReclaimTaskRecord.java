package com.dwinovo.numen.core.scaffold;

import com.dwinovo.numen.task.TaskRecord;

public final class TemporaryScaffoldReclaimTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "reclaim_temporary_scaffolds";

    public TemporaryScaffoldReclaimTaskRecord(String toolCallId, long deadlineGameTime) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
    }

    @Override
    public String describe() {
        return "reclaim exact temporary-scaffold ledger coordinates when currently safe";
    }
}
