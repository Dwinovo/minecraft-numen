package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): travel with full terrain-traversing navigation. */
public final class MoveToTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final MovementTools impl = new MovementTools();

    private record Args(Double x, Double y, Double z, String block) {}

    @Override
    public String name() {
        return "goto";
    }

    @Override
    public String description() {
        return """
                Travel anywhere. Full pathfinding: digs through, bridges gaps, pillars up, swims, auto-equips the right tool. Anything breakable is a route (no tool = slow punching, still works). Which fields you fill IS your intent — fill exactly one pattern:
                • x+z — go to a place. Y resolves to the surface. Your default for "go there".
                • block — e.g. block:'crafting_table'. Finds the nearest one and stops RIGHT BESIDE it, never damaging it. Always use this for a chest/station/ore you intend to use; you arrive in reach, interact directly.
                • x+y+z — stand EXACTLY in that cell, digging out whatever occupies it. Never aim this at a block you want to keep.
                • y — climb/descend to that elevation.
                Background task: returns task_id now, arrival comes as a task_finished event with the final position. Timed out mid-journey? Re-send the same call to continue.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "Target X. Null for an elevation-only move (y alone).")
                .nullableNumber("y", "Target Y (block height). LEAVE NULL to go to a location (x+z) — Y is "
                        + "auto-resolved to the surface. Only set it for an exact cell (x+y+z) or an "
                        + "elevation move (y alone).")
                .nullableNumber("z", "Target Z. Null for an elevation-only move (y alone).")
                .optionalString("block", "Namespaced block id of a block to walk up BESIDE (e.g. "
                        + "'crafting_table' or 'minecraft:chest') — it is never broken or buried. "
                        + "Give it ALONE (no coordinates); the nearest one is found by scanning. "
                        + "ALWAYS use this form for a block you intend to use or mine.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        dispatchAsync(companion, impl.moveTo(a.x(), a.y(), a.z(),
                a.block(), ctx(toolCallId, companion)), reply);
    }
}
