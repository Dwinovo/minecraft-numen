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

    private record Args(Double x, Double y, Double z, double speed, String block) {}

    @Override
    public String name() {
        return "goto";
    }

    @Override
    public String description() {
        return """
                Travel somewhere — full terrain-traversing navigation, not just walking. Pick ONE of four intents by which inputs you fill (leave the others null):
                • Go to a LOCATION: give x and z, leave y null. The companion walks to that spot and stands on whatever ground is there — Y is auto-resolved to the surface. THIS IS THE DEFAULT for 'go over there' / following / exploring; never guess a Y for a location.
                • Go to an EXACT cell: give x, y and z (no block). It occupies exactly that cell — if something solid is there, it digs it out and stands in its place. NEVER send bare coordinates of a block you want to USE (that would dig it) — use the block form below instead.
                • Change ELEVATION: give y only (x and z null) to climb to the surface or descend to a mining depth at your current column.
                • Go to a BLOCK to use it (chest, crafting table, furnace, ore…): give block:'<its name>' alone — it scans nearby, walks up beside the closest one (never touching it) and reports its position. For ONE specific block you already have coordinates for, goto its location (x and z) instead, then interact there.
                En route it digs, bridges gaps and pillars up, auto-equipping the best tool from its whole inventory (no equip_item needed). Anything breakable counts as a route — cost is pure digging time, so with the right tool it tunnels fast, and with no tool it will still slowly punch through (e.g. bare-handing stone at ~7s a block) when that is the only way out; it always prefers the faster route when one exists. It strongly avoids breaking functional blocks (chests, furnaces…) and never touches the target block itself. BACKGROUND task: returns a task_id at once; the position actually reached arrives as a task_finished event — on timeout, re-dispatch the same call to resume; NO path or stopping far short means pick a NEARER waypoint or scan first. To open/use a station, give its coordinate to interact_at instead.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "Target X. Null for an elevation-only move (y alone).")
                .nullableNumber("y", "Target Y (block height). LEAVE NULL to go to a location (x+z) — Y is "
                        + "auto-resolved to the surface. Only set it for an exact cell (x+y+z) or an "
                        + "elevation move (y alone).")
                .nullableNumber("z", "Target Z. Null for an elevation-only move (y alone).")
                .number("speed", "Speed multiplier in [0.1, 2.0]. 1.0 is normal walking speed.", 0.1, 2.0)
                .optionalString("block", "Namespaced block id of a block to walk up BESIDE (e.g. "
                        + "'crafting_table' or 'minecraft:chest') — it is never broken or buried. "
                        + "Give it ALONE (no coordinates); the nearest one is found by scanning. "
                        + "ALWAYS use this form for a block you intend to use or mine.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        dispatchAsync(companion, impl.moveTo(a.x(), a.y(), a.z(), a.speed(),
                a.block(), ctx(toolCallId, companion)), reply);
    }
}
