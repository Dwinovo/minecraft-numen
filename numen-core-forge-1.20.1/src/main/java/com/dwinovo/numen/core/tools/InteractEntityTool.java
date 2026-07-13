package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): press a mouse button on a moving entity. */
public final class InteractEntityTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final BlockActionTools impl = new BlockActionTools();

    private record Args(String button, int entity_id, Integer hold_ticks, String item_id) {}

    @Override
    public String name() {
        return "interact_entity";
    }

    @Override
    public String description() {
        return "Press a mouse button on an ENTITY — the native interaction for moving targets. Auto-paths "
                + "and FOLLOWS the entity (id from scan_nearby_entities), then acts once the crosshair "
                + "reaches it (a wall in the way makes it re-position, not hit through).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("button", "left = attack/hit, right = use/interact.", "left", "right")
                .integer("entity_id", "Target entity id (from scan_nearby_entities).")
                .nullableInteger("hold_ticks", "0/null = single press; >0 = hold that many ticks; -1 = hold until done/timeout (e.g. attack until dead).")
                .nullableString("item_id", "Optional namespaced item to equip-and-use, e.g. minecraft:wheat. Null = use what's in hand.")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.interactEntity(a.button(), a.entity_id(), a.hold_ticks(), a.item_id(),
                ctx(toolCallId, companion)));
    }
}
