package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): destroy entities at range with a bow/crossbow. */
public final class ShootTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final CombatTools impl = new CombatTools();

    private record Args(List<String> entity_ids, int count, Integer radius) {}

    @Override
    public String name() {
        return "shoot";
    }

    @Override
    public String description() {
        return "Destroy entities at range with a ranged weapon — a bow or crossbow (vanilla or modded). "
                + "Give the entity id(s) and how many — the companion finds the nearest, walks to within "
                + "firing range and line of sight (pathfinding around obstacles), and fires until each is "
                + "down, repeating until the count is met. Targets may be non-living: use this for the Ender "
                + "Dragon's end_crystal (which MUST be destroyed at range) and for blazes. REQUIRES a bow or "
                + "crossbow in your main hand (equip_item) and matching ammo in your inventory — fails up "
                + "front if either is missing. Optional radius (default auto-expands). Returns the actual "
                + "number destroyed. Unlike hunt, shoot does NOT pick up drops — ranged kills scatter their "
                + "loot away from you, so call collect_items afterward if you want it.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .stringArray("entity_ids", "Namespaced entity type id(s) to destroy (e.g. minecraft:end_crystal).", 1)
                .integer("count", "How many to destroy.", 1, 64)
                .optionalInteger("radius", "Optional max search radius in blocks (default auto-expands to 64).", 1, 128)
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.shoot(a.entity_ids(), a.count(), a.radius(), ctx(toolCallId, companion)));
    }
}
