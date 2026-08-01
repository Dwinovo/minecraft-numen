package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.dispatchAsync;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Attack an explicit set of entities by runtime id with a bow or crossbow. */
public final class RangedAttackTool implements NumenTool {
    private static final Gson GSON = new Gson();
    private final CombatTools impl = new CombatTools();

    private record Args(List<Integer> entity_ids) {}

    @Override
    public String name() {
        return "ranged_attack";
    }

    @Override
    public String description() {
        return "Ranged-attack one or more SPECIFIC entities by runtime id from "
                + "scan_nearby_entities. Uses a bow or crossbow from your inventory, follows moving "
                + "targets with the pathfinder until an arrow can hit, backs away when too close, "
                + "aims visibly, fires only after the shot path is clear, and stops with a clear "
                + "failure if no bow/crossbow or arrows are available. Targets may be living entities "
                + "or breakable non-living entities such as end crystals. BACKGROUND: acceptance means combat "
                + "is already running; do not resend ids, poll, or launch another body action until task_finished.";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "integer");
        Map<String, Object> ids = new LinkedHashMap<>();
        ids.put("type", "array");
        ids.put("description", "Runtime entity ids from scan_nearby_entities (1-20 distinct targets).");
        ids.put("items", item);
        ids.put("minItems", 1);
        ids.put("maxItems", 20);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("entity_ids", ids);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("entity_ids"));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        dispatchAsync(companion, impl.rangedAttack(a.entity_ids(), ctx(toolCallId, companion)), reply);
    }
}
