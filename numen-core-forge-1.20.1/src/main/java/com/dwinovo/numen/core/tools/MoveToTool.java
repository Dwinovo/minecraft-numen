package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): travel with full terrain-traversing navigation. */
public final class MoveToTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final MovementTools impl = new MovementTools();

    private record Args(Double x, Double y, Double z, double speed) {}

    @Override
    public String name() {
        return "move_to";
    }

    @Override
    public String description() {
        return """
                Travel somewhere — full terrain-traversing navigation, not just walking. Pick ONE of three intents by which coordinates you fill (leave the others null):
                • Go to a LOCATION: give x and z, leave y null. The companion walks to that spot and stands on whatever ground is there — Y is auto-resolved to the surface. THIS IS THE DEFAULT for 'go over there' / following / exploring; never guess a Y for a location.
                • Go to an EXACT cell: give x, y and z. Only for a specific cell you know is reachable (e.g. a block you scanned). If that cell is mid-air or walled in it will report it couldn't reach it.
                • Change ELEVATION: give y only (x and z null) to climb to the surface or descend to a mining depth at your current column.
                En route it mines through obstructions, digs down/up, bridges gaps and pillars up with cobblestone/dirt from inventory. Digging is gated by your HELD tool: stone/deepslate need a pickaxe IN HAND (equip_item first); a sword held makes stone an impassable wall. Consumes scaffold blocks and tool durability; carry cobblestone/dirt for gaps. Timeout scales with distance; the result reports the actual position reached (and the real ground height) — call again with the same target to resume. But if it reports NO path or stops far short, that spot is unreachable or too far: pick a NEARER waypoint, or scan first — don't just repeat the same unreachable target. move_to is for getting somewhere to STAND; to open/use a station give its coordinate to interact_at instead.""";
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
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.moveTo(a.x(), a.y(), a.z(), a.speed(), ctx(toolCallId, companion)));
    }
}
