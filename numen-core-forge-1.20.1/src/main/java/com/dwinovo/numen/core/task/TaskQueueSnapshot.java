package com.dwinovo.numen.core.task;

import java.util.List;
import java.util.UUID;

/** Persistable scheduler view: active first, then the exact FIFO tail. */
public record TaskQueueSnapshot(
        UUID companionUuid,
        boolean paused,
        TaskRecord active,
        List<TaskRecord> pending,
        long gameTime,
        long pausedAtGameTime) {
    public TaskQueueSnapshot {
        pending = List.copyOf(pending);
    }
}
