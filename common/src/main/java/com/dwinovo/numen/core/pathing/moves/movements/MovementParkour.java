package com.dwinovo.numen.core.pathing.moves.movements;

import java.util.Set;

import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

/** 跑酷跳:助跑跃过 2-4 格空隙落到同高或高一格的落点,从不挖方块。 */
public class MovementParkour extends Movement {

    public MovementParkour(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, new BlockPos[0]);
    }

    /** 成本入口:实际落点与成本写进 result。 */
    // TODO: 规格书 B6 节 —— 前置净空/逐格验证/跳跃放置完整成本
    public static void cost(CalculationContext context, int x, int y, int z,
                            Direction direction, MutableMoveResult result) {
        result.reset();
    }

    // TODO: 规格书 B6 节
    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B6 节 —— 执行状态机(起跳时机/空中放置)
    @Override
    public MovementState updateState(MovementState state) {
        return state.setStatus(MovementStatus.FAILED);
    }

    // TODO: 规格书 B6 节 —— src 起沿方向每格两层
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }
}
