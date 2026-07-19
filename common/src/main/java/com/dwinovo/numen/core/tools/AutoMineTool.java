package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): gather blocks by type and quantity. */
public final class AutoMineTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final BlockActionTools impl = new BlockActionTools();

    private record Args(List<String> block_ids, int count, Integer radius, Boolean force) {}

    @Override
    public String name() {
        return "mine";
    }

    @Override
    public String description() {
        return "Gather blocks by type and count. Give block id(s) and how many ITEMS you want — it finds "
                + "the nearest matches, travels to each with full terrain-traversing navigation (digs to "
                + "buried ores, pillars up cliffs, bridges gaps), mines, and repeats until `count` NEW items "
                + "are gained or none remain. No coordinates or goto needed. count is items, not blocks "
                + "(redstone_ore drops ~4). Include all variants in block_ids (iron_ore AND "
                + "deepslate_iron_ore). Optional radius caps the search. force (default false): only mines "
                + "what its tools actually harvest, and stops naming the needed tier if nothing qualifies; "
                + "force:true breaks blocks even when they drop nothing — only for clearing obstacles, "
                + "`count` may never advance. BACKGROUND task: returns a task_id at once; the outcome "
                + "arrives as a task_finished event — don't poll.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .stringArray("block_ids", "Namespaced block id(s) to gather; include all variants.", 1)
                .integer("count", "How many ITEMS to gather (not blocks) — a block may drop several, and it "
                        + "counts only items gained on top of what you already hold.", 1, 256)
                .optionalInteger("radius", "Optional max search radius in blocks (default auto-expands to 48).", 1, 96)
                .optionalBool("force", "Default false: skip targets the carried tools can't harvest (no drops) "
                        + "and stop with a tool-tier reminder when none are harvestable. true: break targets "
                        + "even without drops — for clearing blocks, not gathering.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        dispatchAsync(companion, impl.autoMine(a.block_ids(), a.count(), a.radius(), a.force(),
                ctx(toolCallId, companion)), reply);
    }
}
