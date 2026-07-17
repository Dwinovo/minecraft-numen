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

/** 平移一格:走到水平相邻的同高度格,必要时挖穿两格身位或在落脚点下搭桥。 */
public class MovementTraverse extends Movement {

    public MovementTraverse(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, new BlockPos[]{dest.above(), dest}, dest.below());
    }

    /** 成本入口。 */
    // TODO: 规格书 B1 节 —— 走路分支/搭桥分支完整成本
    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B1 节
    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B1 节 —— 执行状态机
    @Override
    public MovementState updateState(MovementState state) {
        return state.setStatus(MovementStatus.FAILED);
    }

    // TODO: 规格书 B1 节
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }
}
