package com.dwinovo.numen.core.pathing.moves.movements;

import java.util.Set;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.CENTER_AFTER_FALL_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.FALL_N_BLOCKS_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.JUMP_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.SPRINT_MULTIPLIER;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST;

/** 对角一步:斜穿到对角格(可平级或 ±1 格高差),从不挖方块,只在两角通透时绕行。 */
public class MovementDiagonal extends Movement {

    private static final double SQRT_2 = Math.sqrt(2);

    public MovementDiagonal(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, buildPositionsToBreak(src, dest));
    }

    /** 两个切角柱(各两层)+ 终点柱(两层);对角从不真挖,只用作通透检查。 */
    private static BlockPos[] buildPositionsToBreak(BlockPos src, BlockPos dest) {
        BlockPos cornerA = new BlockPos(src.getX(), src.getY(), dest.getZ());
        BlockPos cornerB = new BlockPos(dest.getX(), src.getY(), src.getZ());
        return new BlockPos[]{cornerA, cornerA.above(), cornerB, cornerB.above(), dest, dest.above()};
    }

    /**
     * 成本。终点头顶必须通透;终点身位不通 → 对角上升档(需开关+净空);
     * 落脚不可站 → 对角下降档(需开关+下有底);灵魂沙两端各半罚、
     * 水面对角罚 ×√2、切角下方岩浆不可行;水中覆盖为水速;两侧绕行柱
     * 都堵死不可行,单侧堵按 √2−ε 绕行(等效两个直步略优),全通且
     * 非水可疾跑;最终乘 √2,降档再加落一格成本。
     */
    public static void cost(CalculationContext context, int x, int y, int z,
                            int destX, int destZ, MutableMoveResult res) {
        if (!MovementHelper.canWalkThrough(context, destX, y + 1, destZ)) {
            return;
        }
        BlockState destInto = context.get(destX, y, destZ);
        BlockState fromDown;
        boolean ascend = false;
        BlockState destWalkOn;
        boolean descend = false;
        boolean frostWalker = false;
        if (!MovementHelper.canWalkThrough(context, destX, y, destZ, destInto)) {
            // 终点身位不通:只可能是对角上升
            ascend = true;
            if (!context.allowDiagonalAscend
                    || !MovementHelper.canWalkThrough(context, x, y + 2, z)
                    || !MovementHelper.canWalkOn(context, destX, y, destZ, destInto)
                    || !MovementHelper.canWalkThrough(context, destX, y + 2, destZ)) {
                return;
            }
            destWalkOn = destInto;
            fromDown = context.get(x, y - 1, z);
        } else {
            destWalkOn = context.get(destX, y - 1, destZ);
            fromDown = context.get(x, y - 1, z);
            boolean standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, fromDown);
            frostWalker = standingOnABlock && MovementHelper.canUseFrostWalker(context, destWalkOn);
            if (!frostWalker && !MovementHelper.canWalkOn(context, destX, y - 1, destZ, destWalkOn)) {
                // 落脚不可站:只可能是对角下降
                descend = true;
                if (!context.allowDiagonalDescend
                        || !MovementHelper.canWalkOn(context, destX, y - 2, destZ)
                        || !MovementHelper.canWalkThrough(context, destX, y - 1, destZ, destWalkOn)) {
                    return;
                }
            }
            // 水面行走语义下不能指望水冻住(先判完下降档再收紧)
            frostWalker &= !context.assumeWalkOnWater;
        }
        double multiplier = WALK_ONE_BLOCK_COST;
        // 两端灵魂沙各影响一半行程
        if (destWalkOn.getBlock() == Blocks.SOUL_SAND) {
            multiplier += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
        } else if (frostWalker) {
            // 霜行者冻出的冰面无罚
        } else if (destWalkOn.getBlock() == Blocks.WATER) {
            multiplier += context.walkOnWaterOnePenalty * SQRT_2;
        }
        Block fromDownBlock = fromDown.getBlock();
        if (fromDownBlock == Blocks.LADDER || fromDownBlock == Blocks.VINE) {
            return;
        }
        if (fromDownBlock == Blocks.SOUL_SAND) {
            multiplier += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
        }
        // 切过的两个角落下方不能是岩浆(块/液),会燎到
        BlockState cuttingOver1 = context.get(x, y - 1, destZ);
        if (cuttingOver1.getBlock() == Blocks.MAGMA_BLOCK || MovementHelper.isLava(cuttingOver1)) {
            return;
        }
        BlockState cuttingOver2 = context.get(destX, y - 1, z);
        if (cuttingOver2.getBlock() == Blocks.MAGMA_BLOCK || MovementHelper.isLava(cuttingOver2)) {
            return;
        }
        boolean water = false;
        BlockState startState = context.get(x, y, z);
        Block startIn = startState.getBlock();
        if (MovementHelper.isWater(startState) || MovementHelper.isWater(destInto)) {
            if (ascend) {
                return;
            }
            // 浮在水里,脚下是什么都不再重要:覆盖之前所有修正
            multiplier = context.waterWalkSpeed;
            water = true;
        }
        BlockState pb0 = context.get(x, y, destZ);
        BlockState pb2 = context.get(destX, y, z);
        if (ascend) {
            boolean aTop = MovementHelper.canWalkThrough(context, x, y + 2, destZ);
            boolean aMid = MovementHelper.canWalkThrough(context, x, y + 1, destZ);
            boolean aLow = MovementHelper.canWalkThrough(context, x, y, destZ, pb0);
            boolean bTop = MovementHelper.canWalkThrough(context, destX, y + 2, z);
            boolean bMid = MovementHelper.canWalkThrough(context, destX, y + 1, z);
            boolean bLow = MovementHelper.canWalkThrough(context, destX, y, z, pb2);
            if ((!(aTop && aMid && aLow) && !(bTop && bMid && bLow)) // 两条绕行柱都不通
                    || MovementHelper.avoidWalkingInto(pb0)
                    || MovementHelper.avoidWalkingInto(pb2)
                    || (aTop && aMid && MovementHelper.canWalkOn(context, x, y, destZ, pb0)) // 其实可以直接普通上一格
                    || (bTop && bMid && MovementHelper.canWalkOn(context, destX, y, z, pb2))
                    || (!aTop && aMid && aLow) // 头碰 A
                    || (!bTop && bMid && bLow)) { // 头碰 B
                return;
            }
            res.cost = multiplier * SQRT_2 + JUMP_ONE_BLOCK_COST; // 上升档无跳跃罚金
            res.x = destX;
            res.z = destZ;
            res.y = y + 1;
            return;
        }
        // 平/降档:两侧柱逐项算并提前剪枝,两侧都要挖即不可行(对角从不挖)
        double optionA = MovementHelper.getMiningDurationTicks(context, x, y, destZ, pb0, false);
        double optionB = MovementHelper.getMiningDurationTicks(context, destX, y, z, pb2, false);
        if (optionA != 0 && optionB != 0) {
            return;
        }
        BlockState pb1 = context.get(x, y + 1, destZ);
        optionA += MovementHelper.getMiningDurationTicks(context, x, y + 1, destZ, pb1, true);
        if (optionA != 0 && optionB != 0) {
            return;
        }
        BlockState pb3 = context.get(destX, y + 1, z);
        if (optionA == 0 && ((MovementHelper.avoidWalkingInto(pb2) && pb2.getBlock() != Blocks.WATER)
                || MovementHelper.avoidWalkingInto(pb3))) {
            // A 侧通畅时要从 B 侧绕:B 侧不能是危险格(水除外)
            return;
        }
        optionB += MovementHelper.getMiningDurationTicks(context, destX, y + 1, z, pb3, true);
        if (optionA != 0 && optionB != 0) {
            return;
        }
        if (optionB == 0 && ((MovementHelper.avoidWalkingInto(pb0) && pb0.getBlock() != Blocks.WATER)
                || MovementHelper.avoidWalkingInto(pb1))) {
            return;
        }
        if (optionA != 0 || optionB != 0) {
            // 单侧堵:绕行 ≈ 两次直走,取 √2−ε 让绕行略优于两个平移步
            multiplier *= SQRT_2 - 0.001;
            if (startIn == Blocks.LADDER || startIn == Blocks.VINE) {
                // 从梯/藤起步侧移会变成攀爬,绕不动
                return;
            }
        } else {
            // 全通才可疾跑(水中不行)
            if (context.canSprint && !water) {
                multiplier *= SPRINT_MULTIPLIER;
            }
        }
        res.cost = multiplier * SQRT_2;
        if (descend) {
            res.cost += Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST);
            res.y = y - 1;
        } else {
            res.y = y;
        }
        res.x = destX;
        res.z = destZ;
    }

    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        cost(context, src.getX(), src.getY(), src.getZ(), dest.getX(), dest.getZ(), result);
        if (result.y != dest.getY()) {
            return COST_INF; // 高差档不符,本实例不适用
        }
        return result.cost;
    }

    /** 平级为 src/dest/两角;升降档再加对应层。 */
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        BlockPos diagA = new BlockPos(src.getX(), src.getY(), dest.getZ());
        BlockPos diagB = new BlockPos(dest.getX(), src.getY(), src.getZ());
        if (dest.getY() < src.getY()) {
            return Set.of(src, dest.above(), diagA, diagB, dest, diagA.below(), diagB.below());
        }
        if (dest.getY() > src.getY()) {
            return Set.of(src, src.above(), diagA, diagB, dest, diagA.above(), diagB.above());
        }
        return Set.of(src, dest, diagA, diagB);
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
        } else if (!playerInValidPosition()
                && !(MovementHelper.isLiquid(player.level().getBlockState(src))
                        && getValidPositions().contains(feet.above()))) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (dest.getY() > src.getY() && player.getY() < src.getY() + 0.1 && player.horizontalCollision) {
            state.setInput(Input.JUMP, true);
        }
        if (sprint()) {
            state.setInput(Input.SPRINT, true);
        }
        MovementHelper.moveTowards(player, state, dest);
        return state;
    }

    /** 四个角柱全通透(且不在禁疾跑的水里)才可疾跑斜穿。 */
    private boolean sprint() {
        Level level = player.level();
        if (MovementHelper.isLiquid(level.getBlockState(feet(player)))
                && !NavSettings.get().sprintInWater) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (!MovementHelper.canWalkThrough(level, positionsToBreak[i])) {
                return false;
            }
        }
        return true;
    }

    /** 对角不做准备挖掘。 */
    @Override
    protected boolean prepared(MovementState state) {
        return true;
    }

    /** 对角的待挖集只含终点柱两格(切角柱是挤过去的,不是挖穿的)。 */
    @Override
    public java.util.List<BlockPos> toBreak(net.minecraft.world.level.BlockGetter level) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        java.util.List<BlockPos> result = new java.util.ArrayList<>();
        for (int i = 4; i < 6; i++) {
            if (!MovementHelper.canWalkThrough(level, positionsToBreak[i])) {
                result.add(positionsToBreak[i]);
            }
        }
        toBreakCached = result;
        return result;
    }

    /** 四个切角柱里此刻仍不通透的格:身体会硬挤着蹭过去的位置。 */
    @Override
    public java.util.List<BlockPos> toWalkInto(net.minecraft.world.level.BlockGetter level) {
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new java.util.ArrayList<>();
        }
        java.util.List<BlockPos> result = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            if (!MovementHelper.canWalkThrough(level, positionsToBreak[i])) {
                result.add(positionsToBreak[i]);
            }
        }
        toWalkIntoCached = result;
        return toWalkIntoCached;
    }

    /**
     * 在角上且两角都没脚踩时,检查玩家包围盒四角 ±0.25 有无支撑,
     * 没有支撑说明正悬在角缝里,中断会掉下去。
     */
    @Override
    protected boolean safeToCancel(MovementState state) {
        Level level = player.level();
        BlockPos feet = feet(player);
        double offset = 0.25;
        double x = player.getX();
        double y = player.getY() - 1;
        double z = player.getZ();
        if (feet.equals(src)) {
            return true;
        }
        BlockPos cornerA = new BlockPos(src.getX(), src.getY() - 1, dest.getZ());
        BlockPos cornerB = new BlockPos(dest.getX(), src.getY() - 1, src.getZ());
        if (MovementHelper.canWalkOn(level, cornerA) && MovementHelper.canWalkOn(level, cornerB)) {
            return true;
        }
        if (feet.equals(new BlockPos(src.getX(), src.getY(), dest.getZ()))
                || feet.equals(new BlockPos(dest.getX(), src.getY(), src.getZ()))) {
            return MovementHelper.canWalkOn(level, BlockPos.containing(x + offset, y, z + offset))
                    || MovementHelper.canWalkOn(level, BlockPos.containing(x + offset, y, z - offset))
                    || MovementHelper.canWalkOn(level, BlockPos.containing(x - offset, y, z + offset))
                    || MovementHelper.canWalkOn(level, BlockPos.containing(x - offset, y, z - offset));
        }
        return true;
    }
}
