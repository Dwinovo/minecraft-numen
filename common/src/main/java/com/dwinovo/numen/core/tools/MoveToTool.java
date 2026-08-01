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
                Travel to ONE new destination with full terrain pathfinding: digs through, bridges gaps, pillars up, swims, and auto-equips tools. Which fields you fill IS your intent — fill exactly one pattern:
                • x+z — go to a place. Y resolves to the surface. This is the DEFAULT for exploration or "go there"; omit y.
                • block — e.g. block:'minecraft:crafting_table'. Finds the nearest one and stops RIGHT BESIDE it, never damaging it. Use this for a chest/station/block you intend to interact with.
                • x+y+z — stand EXACTLY in that cell, digging out whatever occupies it. Use only when the exact feet cell matters; never aim it at a block you want to keep.
                • y — climb/descend to that elevation.
                BACKGROUND: a successful call means movement is already running. Do not call goto again or launch another body action while <current_task> exists; wait for matching task_finished. status=done means that destination is complete, so advance the plan and never resend identical coordinates. Only status=timeout permits the same call to resume.""";
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
