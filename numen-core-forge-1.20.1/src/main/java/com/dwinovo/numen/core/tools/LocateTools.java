package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.LocateStructureTaskRecord;

/**
 * Locate tools authored on the {@link NumenAction} surface — the LLM-facing
 * faces of vanilla's {@code /locate structure} and {@code /locate biome}. Both
 * are world-actions: they return a {@link TaskRecord} the body's task queue runs
 * across ticks. Behaviour matches the hand-written {@code NumenTool} classes.
 */
public final class LocateTools {

    private static final long TIMEOUT_TICKS = 30 * 20;
    private static final int MAX_ARG_LENGTH = 128;

    public TaskRecord locateStructure(
String structure,
            ToolContext ctx) {
        structure = structure.trim();
        if (structure.isEmpty() || structure.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException("invalid structure argument");
        }
        return new LocateStructureTaskRecord(ctx.toolCallId(), ctx.deadline(TIMEOUT_TICKS), structure);
    }

    public TaskRecord locateBiome(
String biome,
            ToolContext ctx) {
        biome = biome.trim();
        if (biome.isEmpty() || biome.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException("invalid biome argument");
        }
        return new LocateBiomeTaskRecord(ctx.toolCallId(), ctx.deadline(TIMEOUT_TICKS), biome);
    }
}
