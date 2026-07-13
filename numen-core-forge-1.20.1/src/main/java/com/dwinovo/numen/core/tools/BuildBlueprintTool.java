package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.blueprint.Blueprint;
import com.dwinovo.numen.core.blueprint.BlueprintPlanner;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.task.BuildBlueprintTaskRecord;
import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

/** Starts a bounded, resumable blueprint build after an explicit confirmation. */
public final class BuildBlueprintTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private static final int SURVIVAL_DEFAULT_BATCH = 64;
    private static final int SURVIVAL_MAX_BATCH = 256;
    private static final int CREATIVE_DEFAULT_BATCH = 2048;
    private static final int CREATIVE_MAX_BATCH = 8192;

    private record Args(String name, int x, int y, int z, String rotation,
                        boolean confirm, Integer batch_limit, String reservation_purpose) {}

    @Override public String name() { return "build_blueprint"; }

    @Override
    public String description() {
        return "Build a saved blueprint at an absolute anchor in bounded, resumable batches. ALWAYS call plan_blueprint first and pass confirm=true only after reviewing conflicts/materials. It never clears air and refuses to overwrite occupied blocks. Survival uses real player placement and consumes inventory; creative restores exact saved states at a limited number per tick. Repeat with identical anchor/rotation to continue remaining blocks.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("name", "Saved blueprint name.")
                .integer("x", "Target anchor X for local 0,0,0.")
                .integer("y", "Target anchor Y for local 0,0,0.")
                .integer("z", "Target anchor Z for local 0,0,0.")
                .optionalEnum("rotation", "Y-axis clockwise rotation; default 0.", "0", "90", "180", "270")
                .bool("confirm", "Must be true after reviewing plan_blueprint.")
                .optionalInteger("batch_limit", "Maximum changes this invocation. Survival max 256; creative max 8192.", 1, CREATIVE_MAX_BATCH)
                .optionalString("reservation_purpose", "Required when resources are reserved; must exactly match the reservation purpose that owns this build.")
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Args a = GSON.fromJson(args, Args.class);
            if (!a.confirm()) {
                reply.accept(TaskResult.fail("confirmation required: run plan_blueprint, review it, then call build_blueprint with confirm=true").toJson());
                return;
            }
            Blueprint blueprint = BlueprintStore.load(companion.level.getServer(),
                    companion.getOwnerUuid(), a.name());
            BlueprintPlanner.Turn turn = BlueprintPlanner.Turn.parse(a.rotation());
            BlockPos anchor = new BlockPos(a.x(), a.y(), a.z());
            BlueprintPlanner.Plan plan = BlueprintPlanner.plan(blueprint, companion, anchor, turn);
            var reservations = com.dwinovo.numen.core.task.ReservationGuard.fromArgs(args);
            LinkedHashMap<String, Integer> protectedItems = new LinkedHashMap<>();
            for (var reservation : reservations) {
                int protectedCount = com.dwinovo.numen.core.task.ReservationGuard.protectedCount(
                        reservations, reservation.item(), a.reservation_purpose());
                if (protectedCount > 0) protectedItems.put(reservation.item(), protectedCount);
            }
            boolean creative = companion.isCreative();
            if (!creative) {
                LinkedHashMap<String, Integer> reservationMissing = new LinkedHashMap<>();
                for (var required : plan.required().entrySet()) {
                    String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(required.getKey()).toString();
                    int availableForBuild = plan.available().getOrDefault(required.getKey(), 0)
                            - protectedItems.getOrDefault(itemId, 0);
                    if (availableForBuild < required.getValue()) {
                        reservationMissing.put(itemId, required.getValue() - Math.max(0, availableForBuild));
                    }
                }
                if (!reservationMissing.isEmpty()) {
                    reply.accept(TaskResult.fail("blueprint would consume resources reserved for another goal",
                            "reserved_resource", Map.of("missing_after_reservations", reservationMissing,
                                    "protected_items", protectedItems)).toJson());
                    return;
                }
            }
            if (!plan.buildable(creative)) {
                reply.accept(TaskResult.fail("blueprint is not buildable now; run plan_blueprint and resolve conflicts, missing materials, state fixes, or blocks without items",
                        Map.of("conflicts", plan.conflicts().size(),
                                "missing_material_types", plan.missing().size(),
                                "state_fixes", plan.stateFixes().size(),
                                "unplaceable_types", plan.unplaceable().size())).toJson());
                return;
            }
            if (plan.pendingChanges() == 0) {
                reply.accept(TaskResult.ok("blueprint '" + blueprint.name() + "' is already complete",
                        Map.of("complete", true, "changed_this_batch", 0)).toJson());
                return;
            }

            int defaultBatch = creative ? CREATIVE_DEFAULT_BATCH : SURVIVAL_DEFAULT_BATCH;
            int maxBatch = creative ? CREATIVE_MAX_BATCH : SURVIVAL_MAX_BATCH;
            int batch = a.batch_limit() == null ? defaultBatch : a.batch_limit();
            batch = Math.max(1, Math.min(maxBatch, batch));
            long budget = creative
                    ? Math.max(1_200L, Math.min(12_000L, batch * 2L))
                    : Math.max(1_200L, Math.min(288_000L, batch * 600L));
            BuildBlueprintTaskRecord record = new BuildBlueprintTaskRecord(
                    toolCallId,
                    companion.level.getGameTime() + budget,
                    blueprint.name(),
                    anchor,
                    turn,
                    creative,
                    batch,
                    protectedItems);
            enqueue(companion, record);
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail(ex.getMessage()).toJson());
        }
    }
}
