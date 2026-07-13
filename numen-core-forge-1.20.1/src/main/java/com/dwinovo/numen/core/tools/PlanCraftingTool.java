package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Plans a multi-step crafting dependency tree against the companion's current inventory. */
public final class PlanCraftingTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String item_id, Integer count, Integer max_depth) {}

    @Override
    public String name() {
        return "plan_crafting";
    }

    @Override
    public String description() {
        return "Plan how to craft an item from the companion's current inventory. Recursively expands crafting, furnace/blasting/smoking/campfire, and stonecutter dependencies, choosing ingredient alternatives already in inventory when possible. Returns steps, missing_base_materials, and whether the target is craftable now. Use before making multi-stage items; then gather missing materials or execute steps with lookup_recipe, interact_at and transfer.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Target namespaced item id, e.g. minecraft:diamond_pickaxe.")
                .optionalInteger("count", "Target item count to plan for.", 1, 999)
                .optionalInteger("max_depth", "Maximum recursive recipe depth. Default 4, max 5.", 1, 5)
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        int count = a.count() == null ? 1 : a.count();
        int depth = a.max_depth() == null ? 4 : a.max_depth();
        reply.accept(CraftingPlanner.planJson(a.item_id(), count, depth, companion));
    }
}
