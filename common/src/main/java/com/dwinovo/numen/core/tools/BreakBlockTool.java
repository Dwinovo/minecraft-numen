package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): break the one block at exact coordinates. */
public final class BreakBlockTool implements NumenTool {

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
                + "mistake, prune obstructions. It does NOT travel: you must ALREADY be within working reach "
                + "(~4.5 blocks; goto the block first — it stops right beside it). Drops are collected into "
                + "your inventory. Requires the right tool in hand for blocks that need one (same rule as "
                + "mine — stone needs a pickaxe); fails with guidance otherwise. To GATHER resources by "
                + "type, use mine instead — it finds blocks itself.";
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
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.breakBlock(a.x(), a.y(), a.z(), ctx(toolCallId, companion)), reply);
    }
}
