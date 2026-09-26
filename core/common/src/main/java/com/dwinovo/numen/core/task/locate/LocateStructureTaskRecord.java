package com.dwinovo.numen.core.task.locate;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;

/**
 * Typed task descriptor for {@code locate structure}: "where is the nearest
 * X?" — X being a structure id ({@code minecraft:fortress}) or a structure
 * tag ({@code #minecraft:village}). Resolution happens server-side in the
 * goal, where the registry lives.
 */
public final class LocateStructureTaskRecord extends TaskRecord {

    /** Raw structure argument as the LLM gave it: an id, or a {@code #}-prefixed tag. */
    public final String structure;

    public LocateStructureTaskRecord(ServerSource source, long deadlineGameTime, String structure) {
        super(source, deadlineGameTime);
        this.structure = structure;
    }

    @Override
    public String describe() {
        return getToolName() + " " + structure;
    }
}
