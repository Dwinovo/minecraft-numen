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

/** 原地向下挖:挖掉脚下方块落进下一格(或沿梯子/藤蔓下一格)。 */
public class MovementDownward extends Movement {

    public MovementDownward(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, new BlockPos[]{dest});
    }

    /** 成本入口。 */
    // TODO: 规格书 B8 节 —— 下方可站校验与挖掘成本
    public static double cost(CalculationContext context, int x, int y, int z) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B8 节
    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B8 节 —— 执行状态机
    @Override
    public MovementState updateState(MovementState state) {
        return state.setStatus(MovementStatus.FAILED);
    }

    // TODO: 规格书 B8 节
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }
}
