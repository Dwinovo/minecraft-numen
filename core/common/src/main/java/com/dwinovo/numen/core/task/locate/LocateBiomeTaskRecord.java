package com.dwinovo.numen.core.task.locate;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;

/**
 * Typed task descriptor for {@code locate biome}: "where is the nearest X?" —
 * X being a biome id ({@code minecraft:warped_forest}) or a biome tag
 * ({@code #minecraft:is_forest}). Resolution happens server-side in the goal,
 * where the registry lives.
 */
public final class LocateBiomeTaskRecord extends TaskRecord {

    /** Raw biome argument as the LLM gave it: an id, or a {@code #}-prefixed tag. */
    public final String biome;

    public LocateBiomeTaskRecord(ServerSource source, long deadlineGameTime, String biome) {
        super(source, deadlineGameTime);
        this.biome = biome;
    }

    @Override
    public String describe() {
        return getToolName() + " " + biome;
    }
}
