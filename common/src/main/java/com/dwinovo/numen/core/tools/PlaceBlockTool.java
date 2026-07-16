package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): place a block from inventory at a coordinate. */
public final class PlaceBlockTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final BlockActionTools impl = new BlockActionTools();

    private record Args(String block_id, int x, int y, int z, String facing, String axis, String half) {}

    @Override
    public String name() {
        return "place_block";
    }

    @Override
    public String description() {
        return "Place a block from your inventory at an absolute coordinate; the companion travels there "
                + "on its own and places it like a player. The coordinate is the cell the block will OCCUPY "
                + "— a torch on top of (x,y,z) targets (x,y+1,z); the cell must be empty with something to "
                + "attach to. Optional orientation: `facing` (north/south/east/west/up/down), `axis` (x/y/z, "
                + "logs), `half` (top/bottom, slabs/stairs). The result reports the ACTUAL orientation — if "
                + "it differs, break and retry from another angle. Failures include guidance and nearby "
                + "coordinates that would work.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("block_id", "Namespaced id of the block item to place, e.g. minecraft:torch.")
                .integer("x", "Target x.")
                .integer("y", "Target y.")
                .integer("z", "Target z.")
                .optionalNullableEnum("facing", "Optional. Which way the block should face (furnace/chest/stairs/…).",
                        "north", "south", "east", "west", "up", "down")
                .optionalNullableEnum("axis", "Optional. Pillar/log axis (y = upright).", "x", "y", "z")
                .optionalNullableEnum("half", "Optional. Which half for a slab / stairs.", "top", "bottom")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.placeBlock(a.block_id(), a.x(), a.y(), a.z(),
                a.facing(), a.axis(), a.half(), ctx(toolCallId, companion)), reply);
    }
}
