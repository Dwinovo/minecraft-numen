package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.task.TaskRecord;
import com.dwinovo.numen.core.task.MoveToTaskRecord;

/**
 * Movement tools authored on the {@link NumenAction} surface. {@code move_to}
 * is the first world-action dogfood: it returns a {@link TaskRecord}, so the
 * adapter ships it to the body and the task queue runs it — the method's only
 * job is to validate args and build the record (the {@link ToolContext} carries
 * the call id and deadline basis). Behaviour matches the hand-written tool.
 */
public final class MovementTools {

    /** Base budget: 30 seconds at vanilla 20 tps (the goal extends it by distance at runtime). */
    private static final long DEFAULT_TIMEOUT_TICKS = 30 * 20;
    private static final double MIN_SPEED = 0.1;
    private static final double MAX_SPEED = 2.0;

    public TaskRecord moveTo(
Double x,
Double y,
Double z,
double speed,
            ToolContext ctx) {
        if (speed < MIN_SPEED) speed = MIN_SPEED;
        if (speed > MAX_SPEED) speed = MAX_SPEED;
        // MoveToTaskRecord validates the x/y/z combination and throws a teaching
        // error for an ambiguous one (e.g. only x given).
        return new MoveToTaskRecord(ctx.toolCallId(), ctx.deadline(DEFAULT_TIMEOUT_TICKS), x, y, z, speed);
    }
}
