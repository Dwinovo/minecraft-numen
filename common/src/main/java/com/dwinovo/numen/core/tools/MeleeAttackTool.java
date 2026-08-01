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

/** Attack an explicit set of live entities by the runtime ids returned by perception. */
public final class MeleeAttackTool implements NumenTool {
    private static final Gson GSON = new Gson();
    private final CombatTools impl = new CombatTools();

    private record Args(List<Integer> entity_ids) {}

    @Override public String name() { return "melee_attack"; }

    @Override
    public String description() {
        return "Melee-attack one or more SPECIFIC living entities by runtime id from "
                + "scan_nearby_entities. Tracks moving targets with the full pathfinder, uses "
                + "nearest-target selection, visible aiming, vanilla cooldown attacks and weapon switching, "
                + "then picks up each target's drops before moving on. Players and mobs use the same ids. "
                + "BACKGROUND: acceptance means combat is already running; do not resend ids, poll, or launch "
                + "another body action until task_finished reports the terminal outcome.";
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
        dispatchAsync(companion, impl.meleeAttack(a.entity_ids(), ctx(toolCallId, companion)), reply);
    }
}
