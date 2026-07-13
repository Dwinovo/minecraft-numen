package com.dwinovo.numen.core.task;

/** Placeholder for persisted work that is deliberately not safe to replay. */
final class InterruptedTaskRecord extends TaskRecord {
    final String message;

    InterruptedTaskRecord(String toolName, String toolCallId, long deadline, String args, String message) {
        super(toolName, toolCallId, deadline);
        setArgumentsJson(args);
        this.message = message;
    }
}
