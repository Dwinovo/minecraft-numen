package com.dwinovo.numen.core.follow;

import com.dwinovo.numen.task.TaskRecord;

public final class FollowOwnerTaskRecord extends TaskRecord {
    public static final String TOOL_NAME = "follow_owner";

    public FollowOwnerTaskRecord(String toolCallId, long deadlineGameTime) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
    }

    @Override
    public String describe() {
        return "follow owner until within horizontal radius=4 and same floor";
    }
}
