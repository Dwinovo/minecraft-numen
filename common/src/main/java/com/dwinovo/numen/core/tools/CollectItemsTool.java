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

/** World-action tool (raw NumenTool): pick up dropped items off the ground nearby. */
public final class CollectItemsTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final InventoryTools impl = new InventoryTools();

    private record Args(List<String> item_ids, Integer radius) {}

    @Override
    public String name() {
        return "collect_items";
    }

    @Override
    public String description() {
        return "Pick up dropped items off the ground nearby. The entity travels to each dropped item "
                + "(it auto-absorbs items it gets close to) until none remain in range — terrain is "
                + "handled automatically: it digs and bridges on its own if drops landed in a pit or "
                + "across a gap. Optionally restrict to specific item_ids (omit to collect everything). "
                + "Optional radius (default 16). Use after ranged combat or manual interactions; melee_attack collects its own drops. "
                + "BACKGROUND task: returns a task_id at once; the tally arrives as a task_finished event.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalStringArray("item_ids", "Optional namespaced item id(s) to collect; omit to collect all.")
                .optionalInteger("radius", "Optional search radius in blocks (default 16).", 1, 48)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        dispatchAsync(companion, impl.collectItems(a.item_ids(), a.radius(),
                ctx(toolCallId, companion)), reply);
    }
}
