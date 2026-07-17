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

/** 对角一步:斜穿到对角格(可平级或 ±1 格高差),从不挖方块,只在两角通透时绕行。 */
public class MovementDiagonal extends Movement {

    public MovementDiagonal(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, new BlockPos[0]);
    }

    /** 成本入口:结果(含实际落点 y)写进 result。 */
    // TODO: 规格书 B5 节 —— 切角安全/绕行/水中/升降档完整成本
    public static void cost(CalculationContext context, int x, int y, int z,
                            int destX, int destZ, MutableMoveResult result) {
        result.reset();
    }

    // TODO: 规格书 B5 节
    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return ActionCosts.COST_INF;
    }

    // TODO: 规格书 B5 节 —— 执行状态机
    @Override
    public MovementState updateState(MovementState state) {
        return state.setStatus(MovementStatus.FAILED);
    }

    // TODO: 规格书 B5 节 —— src/dest/两角(升降档另加对应层)
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }
}
