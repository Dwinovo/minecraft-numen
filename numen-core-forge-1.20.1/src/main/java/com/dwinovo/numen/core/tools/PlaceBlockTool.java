package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): place a block from inventory at a coordinate. */
public final class PlaceBlockTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final BlockActionTools impl = new BlockActionTools();

    private record Args(String block_id, int x, int y, int z, String facing, String axis, String half) {}

    @Override
    public String name() {
        return "place_block";
    }

    @Override
    public String description() {
        return "Place a block from your inventory at an absolute coordinate. The companion travels to a "
                + "reachable spot next to the target on its own — digging through obstacles, bridging gaps, "
                + "pillaring up — then places it like a real player (steps to the edge, looks, places). The "
                + "coordinate is the cell the block will OCCUPY, not the block it sits on — to put a torch on "
                + "top of a block at (x,y,z), target (x,y+1,z). Placement still needs a block to attach to "
                + "(you can't place in pure mid-air), and the target cell must be empty. Optional orientation "
                + "for blocks that have one: `facing` (north/south/east/west/up/down — "
                + "furnace/chest/stairs/observer…), `axis` (x/y/z — logs/pillars), `half` (top/bottom — "
                + "slabs/stairs). The result reports the block's ACTUAL orientation, so if it differs from "
                + "what you asked, break it and retry from another angle. Fails with guidance (incl. nearby "
                + "coords that WOULD work) if you lack the block, it isn't placeable, the target is occupied, "
                + "or there's no reachable spot — so don't place where a block already is; build somewhere "
                + "clear instead. Use for torches, walls/shelter, sealing caves, or positioning a crafting "
                + "table/furnace/chest.";
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
                .optionalString("reservation_purpose", "Only set when placement belongs to the exact purpose that reserved this block item.")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.placeBlock(a.block_id(), a.x(), a.y(), a.z(),
                a.facing(), a.axis(), a.half(), ctx(toolCallId, companion)));
    }
}
