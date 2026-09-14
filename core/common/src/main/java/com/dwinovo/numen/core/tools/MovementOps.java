package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.JsonObject;

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
     * @param spec  路线规格的 JSON(见 {@link RouteSpecJson});null 即出厂规格——不说就是只走不改
     * @param route 路线簿 id;给了就走那条路,它自带规格,所以不能再给 spec
     */
    public TaskRecord moveTo(Double x, Double y, Double z, String block, JsonObject spec, String route,
                             ToolContext ctx) {
        if (route != null && !route.isBlank() && spec != null) {
            throw new IllegalArgumentException(
                    "route already carries its own spec (the one it was planned under) — give route"
                    + " alone. To walk under a different spec, goto the destination coordinates with"
                    + " that spec, or plan_route again.");
        }
        // MoveToTaskRecord validates the x/y/z/block/route combination, throwing a
        // teaching error for an ambiguous one (e.g. only x given, or block
        // combined with coordinates).
        RouteSpec parsed = route != null && !route.isBlank() ? null : RouteSpecJson.parse(spec);
        return new MoveToTaskRecord(ctx.toolCallId(), ctx.deadline(DEFAULT_TIMEOUT_TICKS),
                x, y, z, block, parsed, route);
    }
}
