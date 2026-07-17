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

/** 坠落 ≥2 格:走出边缘垂直下落到落点,超过安全高度时空中放水桶接底。 */
public class MovementFall extends Movement {

    public MovementFall(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, new BlockPos[0]);
    }

    // TODO: 规格书 B4 节 —— 成本复用下降原语的坠落分档
    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B4 节 —— 执行状态机(水桶/回中/梯子回避)
    @Override
    public MovementState updateState(MovementState state) {
        return state.setStatus(MovementStatus.FAILED);
    }

    // TODO: 规格书 B4 节 —— src ∪ dest 上方整列
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }
}
