package com.dwinovo.numen.core.sleep;

import com.dwinovo.numen.task.TaskRecord;

public final class SleepTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "sleep";

    public SleepTaskRecord(String toolCallId, long deadlineGameTime) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
    }

    @Override
    public String describe() {
        return "find a nearby bed and enter verified sleep";
    }
}
