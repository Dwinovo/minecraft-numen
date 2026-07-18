package com.dwinovo.numen.core.pathing.moves.movements;

import java.util.Set;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.FALL_N_BLOCKS_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.LADDER_DOWN_ONE_COST;

/** 原地向下挖:挖掉脚下方块落进下一格(或沿梯子/藤蔓下一格)。 */
public class MovementDownward extends Movement {

    /** 自由落体阶段计 tick(前 10 tick 不按键)。 */
    private int numTicks = 0;

    public MovementDownward(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, new BlockPos[]{dest});
    }

    @Override
    public void reset() {
        super.reset();
        numTicks = 0;
    }

    /**
     * 成本。总开关关闭或下二格不可站则不可行;脚下是梯/藤按下爬价,
     * 否则落一格 + 挖脚下(脚下若是落沙,轮到执行时早已变空,不计沙链)。
     */
    public static double cost(CalculationContext context, int x, int y, int z) {
        if (!context.allowDownward) {
            return COST_INF;
        }
        if (!MovementHelper.canWalkOn(context, x, y - 2, z)) {
            return COST_INF;
        }
        BlockState down = context.get(x, y - 1, z);
        Block downBlock = down.getBlock();
        if (downBlock == Blocks.LADDER || downBlock == Blocks.VINE) {
            return LADDER_DOWN_ONE_COST;
        } else {
            return FALL_N_BLOCKS_COST[1]
                    + MovementHelper.getMiningDurationTicks(context, x, y - 1, z, down, false);
        }
    }

    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return cost(context, src.getX(), src.getY(), src.getZ());
    }

    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        BlockPos feet = feet(player);
        if (feet.equals(dest)) {
            return state.setStatus(MovementStatus.SUCCESS);
        } else if (!playerInValidPosition()) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        double diffX = player.getX() - (dest.getX() + 0.5);
        double diffZ = player.getZ() - (dest.getZ() + 0.5);
        double ab = Math.sqrt(diffX * diffX + diffZ * diffZ);

        if (numTicks++ < 10 && ab < 0.2) {
            return state; // 前 10 tick 且没漂出中心:自由落体,不碰按键
        }
        MovementHelper.moveTowards(player, state, positionsToBreak[0]);
        return state;
    }
}
