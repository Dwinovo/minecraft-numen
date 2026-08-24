package com.dwinovo.numen.core.tools.work;
import com.dwinovo.numen.core.tools.MovementOps;

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

    private record Args(Double x, Double y, Double z, String block, Boolean may_alter_terrain) {}

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
                • x+y+z — stand EXACTLY in that cell. A block occupying it would have to be dug out (needs may_alter_terrain), so never aim this at a chest, furnace or anything you want to keep — use block or x+z for those.
                • y — climb/descend to that elevation.
                TERRAIN: by default the walk never breaks or places a block — walls, floors, other people's builds and the landscape stay exactly as they were. If the only route would need digging, bridging or pillaring, the call FAILS and lists exactly which blocks that route would break or place; read the list (planks/glass/bricks near the surface are usually someone's build; stone/dirt underground usually are not) and, if altering them is acceptable, re-send the SAME call with may_alter_terrain=true. Underground travel and climbing out of pits usually need it. Every call reports what it actually broke or placed.
                VEHICLES: start a goto while sitting in a boat (see <riding>) and she pilots it over the water toward the target — a destination on the water keeps her aboard, a destination ashore has her step off at the shore and finish on foot. Any other vehicle is stepped off the moment walking begins. Boarding is interact_entity right on the boat.
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
                .optionalBool("may_alter_terrain", "Consent to dig through, bridge or pillar on the way. "
                        + "Omit/false = leave every block untouched (default). Set true only after a failed "
                        + "goto listed the blocks a route would alter and you judge that acceptable, or "
                        + "when you already know the way is underground/through natural terrain.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.moveTo(a.x(), a.y(), a.z(),
                a.block(), a.may_alter_terrain(), ctx(toolCallId, companion)), args, reply);
    }
}
