package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): break the one block at exact coordinates. */
public final class BreakBlockTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final BlockActionTools impl = new BlockActionTools();

    private record Args(int x, int y, int z) {}

    @Override
    public String name() {
        return "break_block";
    }

    @Override
    public String description() {
        return "Break the ONE block at exact coordinates — the precision inverse of place_block, for "
                + "construction work: clear the cell a structure block must occupy, remove a block placed by "
                + "mistake, prune obstructions. The entity walks within reach first. Drops are collected into "
                + "your inventory. Requires the right tool in hand for blocks that need one (same rule as "
                + "auto_mine — stone needs a pickaxe); fails with guidance otherwise. To GATHER resources by "
                + "type, use auto_mine instead — it finds blocks itself.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X.")
                .integer("y", "Block Y.")
                .integer("z", "Block Z.")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.breakBlock(a.x(), a.y(), a.z(), ctx(toolCallId, companion)));
    }
}
