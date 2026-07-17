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

/** 上一格:跳上水平相邻且高一格的落点,必要时先在落点下放置方块。 */
public class MovementAscend extends Movement {

    public MovementAscend(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest,
                new BlockPos[]{dest, src.above(2), dest.above()}, dest.below());
    }

    /** 成本入口。 */
    // TODO: 规格书 B2 节 —— 放置/落沙保护/半砖规则完整成本
    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B2 节
    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B2 节 —— 执行状态机
    @Override
    public MovementState updateState(MovementState state) {
        return state.setStatus(MovementStatus.FAILED);
    }

    // TODO: 规格书 B2 节
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }
}
