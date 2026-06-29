package com.dwinovo.numen.task;

import com.dwinovo.numen.entity.NumenPlayer;

/**
 * Maps a queued {@link TaskRecord} to the {@link CompanionTask} that runs it on
 * the player body. The base {@code numen-api} engine ships <em>no</em> task types
 * of its own — {@code numen-core} (and any third-party tool pack) contributes the
 * record types and their runners. Until a record type is recognised here it falls
 * back to {@link UnsupportedCompanionTask}, which fails the task cleanly rather
 * than crashing the tick loop.
 *
 * <p>This is the seam where concrete task execution plugs into the engine; it is
 * intentionally empty in the engine so that a chat-only companion has zero
 * world-action machinery wired in.
 */
public final class CompanionTaskFactory {

    private CompanionTaskFactory() {}

    public static CompanionTask create(NumenPlayer player, TaskRecord record) {
        return new UnsupportedCompanionTask(record);
    }
}
