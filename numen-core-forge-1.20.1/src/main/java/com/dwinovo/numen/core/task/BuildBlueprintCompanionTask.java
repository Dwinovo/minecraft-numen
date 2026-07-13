package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.blueprint.BlueprintPlanner;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Applies a planned blueprint in bounded batches. Survival delegates every block
 * to the existing native {@link PlaceBlockCompanionTask}; creative mode writes
 * at most a small number of states per tick and then performs a second state
 * pass so neighbours/doors settle consistently.
 */
public final class BuildBlueprintCompanionTask implements CompanionTask {

    private static final int CREATIVE_OPS_PER_TICK = 64;

    private final NumenPlayer player;
    private final BuildBlueprintTaskRecord record;
    private int index;
    private int pass;
    private String failReason = "blueprint build failed";
    private BlueprintPlanner.Plan activePlan;
    private List<BlueprintPlanner.Target> activeTargets = List.of();
    private final Set<BlockPos> creativeSelected = new HashSet<>();
    private final Set<BlockPos> creativeChanged = new HashSet<>();
    private final Set<BlockPos> creativeSkipped = new HashSet<>();

    private PlaceBlockCompanionTask child;
    private PlaceBlockTaskRecord childRecord;

    public BuildBlueprintCompanionTask(NumenPlayer player, BuildBlueprintTaskRecord record) {
        this.player = player;
        this.record = record;
    }

    @Override
    public void start() {
        if (record.creative != player.isCreative()) {
            failReason = "game mode changed after blueprint planning; run build_blueprint again";
            record.setState(TaskState.FAILED);
            return;
        }
        com.dwinovo.numen.core.blueprint.Blueprint blueprint = BlueprintStore.load(
                player.level.getServer(), player.getOwnerUuid(), record.blueprintName);
        activePlan = BlueprintPlanner.plan(blueprint, player, record.anchor, record.turn);
        activeTargets = BlueprintPlanner.targets(activePlan.blueprint(), activePlan.anchor(), activePlan.turn());
        if (!activePlan.buildable(record.creative)) {
            failReason = "blueprint plan changed before construction; run plan_blueprint again";
            record.setState(TaskState.FAILED);
        }
    }

    @Override
    public TaskState tick() {
        return record.creative ? tickCreative() : tickSurvival();
    }

    private TaskState tickCreative() {
        int operations = 0;
        int visited = 0;
        while (operations < CREATIVE_OPS_PER_TICK && visited < 512) {
            if (index >= activeTargets.size()) {
                if (pass == 0) {
                    pass = 1;
                    index = 0;
                    continue;
                }
                return TaskState.SUCCESS;
            }
            BlueprintPlanner.Target target = activeTargets.get(index++);
            visited++;
            BlockState current = player.level.getBlockState(target.pos());
            if (current.equals(target.state())) {
                if (pass == 0 && creativeSkipped.add(target.pos())) record.incrementSkipped();
                continue;
            }
            if (pass == 0 && target.secondary()) continue;
            boolean selected = creativeSelected.contains(target.pos());
            if (!selected && record.getChanged() >= record.batchLimit) continue;
            if (target.pos().getY() < player.level.getMinBuildHeight()
                    || target.pos().getY() >= player.level.getMaxBuildHeight()
                    || !player.level.hasChunkAt(target.pos())) {
                failReason = "target became unavailable at " + target.pos().toShortString();
                return TaskState.FAILED;
            }
            current = player.level.getBlockState(target.pos());
            if (current.equals(target.state())) continue;
            if (current.getBlock() != target.state().getBlock()
                    && !current.isAir() && !current.canBeReplaced()) {
                failReason = "target became occupied at " + target.pos().toShortString();
                return TaskState.FAILED;
            }
            creativeSelected.add(target.pos());
            if (!player.level.setBlock(target.pos(), target.state(), 3)) {
                failReason = "could not set " + target.pos().toShortString();
                return TaskState.FAILED;
            }
            if (creativeChanged.add(target.pos())) record.incrementChanged();
            operations++;
        }
        return TaskState.RUNNING;
    }

    private TaskState tickSurvival() {
        List<BlueprintPlanner.Placement> placements = activePlan.placements();
        if (record.getChanged() >= record.batchLimit || index >= placements.size()) return TaskState.SUCCESS;

        if (child == null) {
            BlueprintPlanner.Placement placement = placements.get(index);
            BlockState current = player.level.getBlockState(placement.pos());
            if (BlueprintPlanner.survivalPlacementStateMatches(current, placement.state())) {
                record.incrementSkipped();
                index++;
                return TaskState.RUNNING;
            }
            if (!current.isAir() && !current.canBeReplaced()) {
                failReason = "target became occupied at " + placement.pos().toShortString();
                return TaskState.FAILED;
            }
            String itemId = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(placement.item()).toString();
            int protectedCount = record.protectedItems.getOrDefault(itemId, 0);
            if (PlayerInv.count(player.getInventory(), placement.item()) - placement.itemCount() < protectedCount) {
                failReason = "out of " + net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(placement.item()) + " while preserving " + protectedCount + " reserved item(s)";
                return TaskState.FAILED;
            }
            BlockState wanted = placement.state();
            childRecord = new PlaceBlockTaskRecord(record.getToolCallId(), record.getDeadlineGameTime(),
                    wanted.getBlock(), placement.item(), placement.pos(),
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(wanted.getBlock()).getPath(),
                    facing(wanted), axis(wanted), topHalf(wanted));
            childRecord.setState(TaskState.RUNNING);
            child = new PlaceBlockCompanionTask(player, childRecord);
            child.start();
        }

        TaskState state = childRecord.getState();
        if (state == TaskState.RUNNING) state = child.tick();
        childRecord.setState(state);
        if (!state.isTerminal()) return TaskState.RUNNING;

        TaskResult result = child.buildResult(state);
        BlueprintPlanner.Placement placement = placements.get(index);
        child = null;
        childRecord = null;
        if (state != TaskState.SUCCESS) {
            failReason = result == null ? "block placement failed" : result.message();
            return state == TaskState.CANCELLED ? TaskState.CANCELLED : TaskState.FAILED;
        }
        if (!BlueprintPlanner.survivalPlacementStateMatches(
                player.level.getBlockState(placement.pos()), placement.state())) {
            failReason = "placed " + placement.pos().toShortString()
                    + " but its state/orientation differs from the blueprint; clear it and retry from another side";
            return TaskState.FAILED;
        }
        record.incrementChanged();
        index++;
        return TaskState.RUNNING;
    }

    @Override
    public TaskResult buildResult(TaskState finalState) {
        if (child != null) {
            child.buildResult(finalState == TaskState.TIMEOUT ? TaskState.TIMEOUT : TaskState.CANCELLED);
            child = null;
            childRecord = null;
        }
        if (activePlan == null) {
            return TaskResult.fail(failReason, "blueprint_restore_failed",
                    Map.of("blueprint", record.blueprintName));
        }
        BlueprintPlanner.Plan remaining = BlueprintPlanner.plan(activePlan.blueprint(), player,
                record.anchor, record.turn);
        int changedCount = record.getChanged();
        int skippedCount = record.getSkipped();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("blueprint", record.blueprintName);
        data.put("changed_this_batch", changedCount);
        data.put("skipped_correct", skippedCount);
        data.put("remaining_blocks_to_place", remaining.placements().size());
        data.put("remaining_state_fixes", remaining.stateFixes().size());
        data.put("remaining_conflicts", remaining.conflicts().size());
        data.put("complete", remaining.pendingChanges() == 0 && remaining.conflicts().isEmpty());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok(remaining.pendingChanges() == 0
                    ? "blueprint '" + record.blueprintName + "' complete"
                    : "blueprint batch complete; " + remaining.pendingChanges()
                            + " changes remain — call build_blueprint again to continue", data);
            case TIMEOUT -> TaskResult.timeout("blueprint build timed out", "timeout", data);
            case CANCELLED -> TaskResult.cancelled("blueprint build interrupted", "cancelled", data);
            default -> TaskResult.fail(failReason, "blueprint_build_failed", data);
        };
    }

    private static Direction facing(BlockState state) {
        if (state.hasProperty(BlockStateProperties.FACING)) return state.getValue(BlockStateProperties.FACING);
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        return null;
    }

    private static Direction.Axis axis(BlockState state) {
        if (state.hasProperty(BlockStateProperties.AXIS)) return state.getValue(BlockStateProperties.AXIS);
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            return state.getValue(BlockStateProperties.HORIZONTAL_AXIS);
        }
        return null;
    }

    private static Boolean topHalf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType slab = state.getValue(BlockStateProperties.SLAB_TYPE);
            return slab == SlabType.DOUBLE ? null : slab == SlabType.TOP;
        }
        if (state.hasProperty(BlockStateProperties.HALF)) {
            return state.getValue(BlockStateProperties.HALF) == Half.TOP;
        }
        return null;
    }
}
