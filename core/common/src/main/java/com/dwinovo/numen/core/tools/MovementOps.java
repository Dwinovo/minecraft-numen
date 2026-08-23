package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;

/**
 * Movement tool implementation — the business half of {@code MoveToTool}
 * ({@code goto}): its only job is to validate args and build the
 * {@link TaskRecord} the body's task queue runs (the {@link ToolContext}
 * carries the call id and deadline basis).
 */
public final class MovementOps {

    /** Base budget: 30 seconds at vanilla 20 tps (the goal extends it by distance at runtime). */
    private static final long DEFAULT_TIMEOUT_TICKS = 30 * 20;

    /**
     * @param mayAlterTerrain 模型是否授权路上挖/放方块;null 即 false——不说就是不许
     */
    public TaskRecord moveTo(Double x, Double y, Double z, String block, Boolean mayAlterTerrain,
                             ToolContext ctx) {
        // MoveToTaskRecord validates the x/y/z/block combination, throwing a
        // teaching error for an ambiguous one (e.g. only x given, or block
        // combined with coordinates).
        return new MoveToTaskRecord(ctx.toolCallId(), ctx.deadline(DEFAULT_TIMEOUT_TICKS),
                x, y, z, block, Boolean.TRUE.equals(mayAlterTerrain));
    }
}
