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

/** World-action tool (raw NumenTool): melee-hunt mobs by type and quantity. */
public final class HuntTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final CombatTools impl = new CombatTools();

    private record Args(List<String> entity_ids, int count, Integer radius) {}

    @Override
    public String name() {
        return "hunt";
    }

    @Override
    public String description() {
        return "Hunt mobs by TYPE and count (e.g. minecraft:zombie) — no coordinates or entity ids. Finds "
                + "the nearest, chases with the full pathfinder, melees it, and repeats until the count is "
                + "met or none remain; optional radius caps the search. It auto-wields the strongest melee "
                + "weapon it carries (keep a good sword/axe in its pack) and auto-collects the drops "
                + "afterwards. Auto-eats if HP runs low. BACKGROUND task: returns a task_id at once; "
                + "kills, post-fight HP and anything eaten arrive as a task_finished event.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .stringArray("entity_ids", "Namespaced entity type id(s) to hunt (e.g. minecraft:zombie).", 1)
                .integer("count", "How many to kill.", 1, 64)
                .optionalInteger("radius", "Optional max search radius in blocks (default auto-expands to 48).", 1, 96)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        dispatchAsync(companion, impl.hunt(a.entity_ids(), a.count(), a.radius(),
                ctx(toolCallId, companion)), reply);
    }
}
