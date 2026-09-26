package com.dwinovo.numen.core.tools.work;
import com.dwinovo.numen.core.tools.MovementOps;
import com.dwinovo.numen.core.tools.RouteSpecJson;

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
    private final MovementOps impl = new MovementOps();

    private record Args(Double x, Double y, Double z, String block, JsonObject spec, String route) {}

    @Override
    public String name() {
        return "goto";
    }

    @Override
    public String description() {
        return """
                Travel to ONE new destination with full terrain pathfinding: walks, jumps, swims, climbs, opens doors and gates, parkours, and auto-equips tools. Which fields you fill IS your intent — fill exactly one pattern:
                • x+z — go to a place. Y resolves to the surface. This is the DEFAULT for exploration or "go there"; omit y.
                • block — e.g. block:'minecraft:crafting_table'. Walks up BESIDE the one she can reach most easily and never damages it. Easiest to reach is not always closest in a straight line, so it may not be the first block scan_blocks listed — give coordinates when it has to be a specific one.
                • x+y+z — stand EXACTLY in that cell. A block occupying it would have to be dug out (only a spec with alter:'natural' allows that), so never aim this at a chest, furnace or anything you want to keep — use block or x+z for those.
                • y — climb/descend to that elevation.
                • route — walk a route by id (r1, r2, ...) from an earlier refusal or plan_route reply. Give it ALONE: its destination and spec are already fixed. A route is spent once walked, and only valid while you still stand where it was planned.
                TERRAIN: the walk never changes the world unless you say so — walls, floors, other people's builds and the landscape stay exactly as they were. When there is no clean route, the call FAILS and lists candidate routes, each with an id, its length and exactly which blocks it would break or place — blocks that are someone's are marked as needing consent, and walking such a route asks the owner first; then either goto route:<id> or pick another destination. Underground travel and climbing out of pits usually need alter:'natural'. Every call reports what it actually broke or placed.
                SPEC (optional object, every field optional): alter 'none'|'natural' — may the walk dig, bridge, pillar; avoid — types to keep out of (water, flowing_water, lava, hazard, door, ladder, vine, snow_layer); penalties {place, break, jump, wade} — make an action pricier so she detours instead; avoid_break / avoid_place / avoid_step — blocks (ids or #tags) or cells/boxes she must not break, place into, or stand on; parkour — running jumps over gaps; climb_vines; max_fall — highest drop without water; alter_budget — how many blocks the whole route may change; routes over budget are dropped.
                VEHICLES: start a goto while sitting in a boat (see <riding>) and she pilots it over the water toward the target — a destination on the water keeps her aboard, a destination ashore has her step off at the shore and finish on foot. Any other vehicle is stepped off the moment walking begins. Boarding is use entity right on the boat.
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
                .optionalString("block", "Namespaced block id to walk up BESIDE (e.g. 'crafting_table' "
                        + "or 'minecraft:chest') — never broken or buried. Give it ALONE (no coordinates); "
                        + "she picks the one easiest to reach. ALWAYS use this form for a block you intend "
                        + "to use or mine.")
                .optionalString("route", "Id of a planned route (r1, r2, ...) from a goto refusal or a "
                        + "plan_route reply. Give it ALONE — no coordinates, block or spec.")
                .optionalObject("spec", "How the walk may behave. Omit for the default: never breaks or "
                        + "places a block.", RouteSpecJson::schema)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.moveTo(a.x(), a.y(), a.z(),
                a.block(), a.spec(), a.route(), ctx(toolCallId, companion)), args, reply);
    }
}
