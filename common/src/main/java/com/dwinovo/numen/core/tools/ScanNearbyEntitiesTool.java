package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Query tool (raw NumenTool): list nearby entities, sorted by distance. */
public final class ScanNearbyEntitiesTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final QueryExtraTools impl = new QueryExtraTools();

    private record Args(double radius, String type_filter) {}

    @Override
    public String name() {
        return "scan_nearby_entities";
    }

    @Override
    public String description() {
        return "List entities within a radius around you, sorted by distance. Use type_filter to "
                + "narrow: 'hostile' for monsters, 'passive' for animals/items, 'player' for players, "
                + "'all' for everything. Returns at most 20 entities; truncated:true means more exist. "
                + "Each entry has id, type, position, distance, hp, and category. Pass "
                + "the returned runtime ids to melee_attack or ranged_attack; neither tool can attack outside that set.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .number("radius", "Search radius in blocks. Range [1, 64].", 1, 64)
                .enumStr("type_filter", "One of: hostile, passive, player, all.",
                        "hostile", "passive", "player", "all")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.scanNearbyEntities(a.radius(), a.type_filter(), self));
    }
}
