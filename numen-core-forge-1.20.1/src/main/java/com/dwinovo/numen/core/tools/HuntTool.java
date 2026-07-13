package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): melee-hunt mobs by type and quantity. */
public final class HuntTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private final CombatTools impl = new CombatTools();

    private record Args(List<String> entity_ids, int count, Integer radius) {}

    @Override
    public String name() {
        return "hunt";
    }

    @Override
    public String description() {
        return "Hunt mobs by type and quantity. Give the entity id(s) and how many to kill — the entity "
                + "finds the nearest, chases it with the full pathfinder (bridging gaps, digging through "
                + "cover, jumping to close in), and melees it to death, repeating until the count is met or "
                + "none remain nearby. It AUTO-SELECTS the strongest melee weapon in its inventory before "
                + "every swing (no need to equip_item first — but it can only wield what it carries, so keep "
                + "a good sword/axe in its pack for real damage), and once the fight ends it AUTO-COLLECTS "
                + "the mob drops around the battlefield (loot ends up in its pack — only reach for "
                + "collect_items for drops flung far away). You do NOT provide coordinates or entity ids — "
                + "give TYPES (e.g. minecraft:zombie). Optional radius caps how far to look (default "
                + "auto-expands). Returns the actual number killed, which may be less if the area runs dry. "
                + "If HP runs low mid-fight you auto-eat from your inventory; the result reports your "
                + "post-fight HP and anything eaten.";
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
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.hunt(a.entity_ids(), a.count(), a.radius(), ctx(toolCallId, companion)));
    }
}
