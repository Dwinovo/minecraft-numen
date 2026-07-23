package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.util.BlockHelper;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.Set;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/** Cost context used while a construction task owns the path search. */
final class BuildCalculationContext extends CalculationContext {

    private final Map<Long, BuildTaskRecord.Target> activeTargets;
    private final Set<BlockState> availableStates;
    private final boolean replaceExisting;

    BuildCalculationContext(ServerPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                            boolean safeForThreadedUse, LongSet sacred, LongSet deniedPlace,
                            Map<Long, BuildTaskRecord.Target> activeTargets,
                            Set<BlockState> availableStates, boolean replaceExisting) {
        super(player, view, loadedTest, safeForThreadedUse, sacred, deniedPlace);
        this.activeTargets = Map.copyOf(activeTargets);
        this.availableStates = Set.copyOf(availableStates);
        this.replaceExisting = replaceExisting;
        this.jumpPenalty += 10.0;
        this.backtrackCostFavoringCoefficient = 1.0;
    }

    @Override
    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        long key = BlockPos.asLong(x, y, z);
        BuildTaskRecord.Target target = activeTargets.get(key);
        if (target != null) {
            if (sacred.contains(key) || deniedPlace.contains(key)) {
                return COST_INF;
            }
            if (!MovementHelper.placeableWithinBorder(worldBorder, x, z)) {
                return COST_INF;
            }
            if (target.block() instanceof net.minecraft.world.level.block.AirBlock) {
                // 目标应为空气却被问能否在此放置(脚手架):恒计"放错块"有限成本,迟早还要挖掉。
                return placeBlockCost * NavSettings.get().placeIncorrectBlockPenaltyMultiplier;
            }
            if (target.matches(current)) {
                return COST_INF;
            }
            if (containsAvailableState(target.desiredState())
                    && MovementHelper.isReplaceable(x, y, z, current, loadedTest)) {
                return 0.0;
            }
            return hasThrowaway
                    ? placeBlockCost * 1.5 * NavSettings.get().placeIncorrectBlockPenaltyMultiplier
                    : COST_INF;
        }
        return super.costOfPlacingAt(x, y, z, current);
    }

    private boolean containsAvailableState(BlockState desired) {
        for (BlockState state : availableStates) {
            if (BuildValidity.sameBlockState(state, desired)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        long key = BlockPos.asLong(x, y, z);
        if (sacred.contains(key)) {
            return COST_INF;
        }
        if (BlockHelper.shouldAvoidBreaking(view, new BlockPos(x, y, z))) {
            return COST_INF;
        }
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        BuildTaskRecord.Target target = activeTargets.get(key);
        if (target != null) {
            if (target.matches(current)) {
                return NavSettings.get().breakCorrectBlockPenaltyMultiplier;
            }
            return replaceExisting ? 1.0 : COST_INF;
        }
        return super.breakCostMultiplierAt(x, y, z, current);
    }
}