package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.core.task.CraftItemsTaskRecord;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.world.item.Item;

import java.util.Map;
import java.util.function.Consumer;

/** Executes recursive vanilla crafting plans without generating items. */
public final class CraftItemsTool extends ServerNumenTool {
    private static final Gson GSON = new Gson();
    private record Args(String item_id, Integer count, Integer max_depth, String reservation_purpose) {}

    @Override public String name() { return CraftItemsTaskRecord.TOOL_NAME; }

    @Override public String description() {
        return "Craft additional items by recursively executing vanilla recipes with real inventory consumption. Supports your 2x2 grid, nearby crafting tables, furnaces, blast furnaces, smokers, stonecutters and campfires. It walks to required workstations, verifies every recipe and inventory step, supplies real fuel, waits for machines, supports pause/timeout/restart recovery, and never creates free items. Call plan_crafting first when you want to inspect missing materials.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced item id to craft.")
                .optionalInteger("count", "How many NEW items to produce, on top of inventory already held.", 1, 999)
                .optionalInteger("max_depth", "Maximum recursive recipe depth. Default 4, max 5.", 1, 5)
                .optionalString("reservation_purpose", "Required when resources are reserved; must exactly match the reservation purpose that owns this craft.")
                .build();
    }

    @Override public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion,
                                      Consumer<String> reply) {
        try {
            Args a = GSON.fromJson(args, Args.class);
            Item target = ToolArgs.parseItem(a.item_id());
            int count = a.count() == null ? 1 : a.count();
            int depth = a.max_depth() == null ? 4 : a.max_depth();
            CraftingPlanner.Plan plan = CraftingPlanner.createAdditional(target, count, depth, companion,
                    com.dwinovo.numen.core.task.ReservationGuard.fromArgs(args), a.reservation_purpose());
            if (!plan.craftable()) {
                reply.accept(TaskResult.fail("No currently satisfiable recipe chain was found. Do not repeat the same recipe. Inspect lookup_recipe alternatives and ask the owner which recipe/material source to use if the choice is still ambiguous.",
                        Map.of("target", CraftingPlanner.id(target),
                                "missing_materials", plan.missing().toString(),
                                "planned_steps", plan.steps().size(),
                                "needs_owner_help", true)).toJson());
                return;
            }
            long batches = plan.steps().stream().mapToLong(CraftingPlanner.Step::batches).sum();
            long budget = Math.max(1_200L, Math.min(1_728_000L, 1_200L + batches * 1_200L));
            enqueue(companion, new CraftItemsTaskRecord(toolCallId,
                    companion.level.getGameTime() + budget, target, count, depth, plan.steps()));
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
