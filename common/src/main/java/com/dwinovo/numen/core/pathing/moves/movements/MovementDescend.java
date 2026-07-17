package com.dwinovo.numen.core.pathing.moves.movements;

import java.util.Set;

import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

/** 下一格:走下水平相邻且低一格的落点;落点更深时由成本函数移交坠落语义。 */
public class MovementDescend extends Movement {

    public MovementDescend(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest,
                new BlockPos[]{dest.above(2), dest.above(), dest}, dest.below());
    }

    /**
     * 成本入口:结果写进 result;result.y == y-1 为普通下一格,
     * 更低则表示移交坠落语义。
     */
    // TODO: 规格书 B3 节 —— 下降成本与 dynamicFallCost 坠落分档
    public static void cost(CalculationContext context, int x, int y, int z,
                            int destX, int destZ, MutableMoveResult result) {
        result.reset();
    }

    // TODO: 规格书 B3 节
    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B3 节 —— 执行状态机(safeMode/fakeDest)
    @Override
    public MovementState updateState(MovementState state) {
        return state.setStatus(MovementStatus.FAILED);
    }

    // TODO: 规格书 B3 节
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest.above(), dest);
    }
}
