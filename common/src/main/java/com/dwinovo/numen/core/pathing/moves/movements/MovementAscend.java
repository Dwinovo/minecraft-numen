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
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.JUMP_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST;

/** 上一格:跳上水平相邻且高一格的落点,必要时先在落点下放置方块。 */
public class MovementAscend extends Movement {

    /** 需要放置时,连续多少 tick 还没把块放上(用于退开自救与取消保护)。 */
    private int ticksWithoutPlacement = 0;

    public MovementAscend(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest,
                new BlockPos[]{dest, src.above(2), dest.above()}, dest.below());
    }

    @Override
    public void reset() {
        super.reset();
        ticksWithoutPlacement = 0;
    }

    /**
     * 成本。落点下不可站则计放置(贴面枚举排除来向,那块届时已被挖);
     * 头顶三格外有落沙且够不成"已被清干净的沙柱"则不可行;从梯/藤、
     * 从下半砖(目标非下半砖)不可行;下半砖三分支与灵魂沙起跳
     * 减速按各自步速计,常规起跳含跳跃罚金;再叠三格挖掘成本。
     */
    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        BlockState toPlace = context.get(destX, y, destZ);
        double additionalPlacementCost = 0;
        if (!MovementHelper.canWalkOn(context, destX, y, destZ, toPlace)) {
            additionalPlacementCost = context.costOfPlacingAt(destX, y, destZ, toPlace);
            if (additionalPlacementCost >= COST_INF) {
                return COST_INF;
            }
            if (!MovementHelper.isReplaceable(destX, y, destZ, toPlace, context.loadedTest)) {
                return COST_INF;
            }
            boolean foundPlaceOption = false;
            for (int i = 0; i < 5; i++) {
                int againstX = destX + MovementPlacement.HORIZONTALS_AND_DOWN[i].getStepX();
                int againstY = y + MovementPlacement.HORIZONTALS_AND_DOWN[i].getStepY();
                int againstZ = destZ + MovementPlacement.HORIZONTALS_AND_DOWN[i].getStepZ();
                if (againstX == x && againstZ == z) {
                    // 来向那块到时候已经被挖掉了,不能作贴面
                    continue;
                }
                if (MovementHelper.canPlaceAgainst(context, againstX, againstY, againstZ)) {
                    foundPlaceOption = true;
                    break;
                }
            }
            if (!foundPlaceOption) {
                return COST_INF;
            }
        }
        BlockState srcUp2 = context.get(x, y + 2, z);
        if (context.get(x, y + 3, z).getBlock() instanceof FallingBlock
                && (MovementHelper.canWalkThrough(context, x, y + 1, z)
                        || !(srcUp2.getBlock() instanceof FallingBlock))) {
            // 跳上去的瞬间头顶沙柱塌下来会闷住;只有"src.above 不可穿且
            // src.above(2) 也是落沙"的情形说明整根沙柱已在准备期清掉,才放行
            return COST_INF;
        }
        BlockState srcDown = context.get(x, y - 1, z);
        if (srcDown.getBlock() == Blocks.LADDER || srcDown.getBlock() == Blocks.VINE) {
            return COST_INF;
        }
        // 灵魂沙上能起跳,下半砖上跳不满一格
        boolean jumpingFromBottomSlab = MovementHelper.isBottomSlab(srcDown);
        boolean jumpingToBottomSlab = MovementHelper.isBottomSlab(toPlace);
        if (jumpingFromBottomSlab && !jumpingToBottomSlab) {
            return COST_INF; // 从下半砖只能上到另一块下半砖
        }
        double walk;
        if (jumpingToBottomSlab) {
            if (jumpingFromBottomSlab) {
                walk = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST);
                walk += context.jumpPenalty;
            } else {
                walk = WALK_ONE_BLOCK_COST; // 整块上半砖:直接走进去,不跳
            }
        } else {
            if (toPlace.getBlock() == Blocks.SOUL_SAND) {
                walk = WALK_ONE_OVER_SOUL_SAND_COST;
            } else {
                walk = Math.max(JUMP_ONE_BLOCK_COST, WALK_ONE_BLOCK_COST);
            }
            walk += context.jumpPenalty;
        }

        double totalCost = walk + additionalPlacementCost;
        // 头顶那格不必查落沙:上面的落沙检查已把危险情形判成不可行
        totalCost += MovementHelper.getMiningDurationTicks(context, x, y + 2, z, srcUp2, false);
        if (totalCost >= COST_INF) {
            return COST_INF;
        }
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, false);
        if (totalCost >= COST_INF) {
            return COST_INF;
        }
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y + 2, destZ, true);
        return totalCost;
    }

    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return cost(context, src.getX(), src.getY(), src.getZ(), dest.getX(), dest.getZ());
    }

    /** 含后退放置位与连跳过程位。 */
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        BlockPos dir = getDirection();
        // prior = src 减去爬升方向后再上一格:垫柱后退放置/sprint 上台/skip descend
        // 落点会经过这里(dir.y=1,subtract 后 y-1,above 后回到 src 同高)
        BlockPos prior = src.subtract(dir).above();
        return Set.of(src, src.above(), dest, prior, prior.above());
    }

    @Override
    public MovementState updateState(MovementState state) {
        if (feet(player).getY() < src.getY()) {
            // 掉下去了:即使还在挖掘准备期也判不可达
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        Level level = player.level();
        BlockPos feet = feet(player);
        BlockPos dir = getDirection();
        if (feet.equals(dest) || feet.equals(dest.offset(dir.getX(), dir.getY() - 1, dir.getZ()))) {
            // 到了,或冲过头落在同层前方一格(爬升方向 Y=1,减 1 即同层)也算成功
            return state.setStatus(MovementStatus.SUCCESS);
        }

        BlockState jumpingOnto = level.getBlockState(positionToPlace);
        if (!MovementHelper.canWalkOn(level, positionToPlace)) {
            // 落点块还不在:潜行放置
            ticksWithoutPlacement++;
            if (MovementPlacement.attemptToPlaceABlock(state, player, dest.below(), false, true)
                    == MovementPlacement.PlaceResult.READY_TO_PLACE) {
                state.setInput(Input.SNEAK, true);
                if (player.isCrouching()) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
            if (ticksWithoutPlacement > 10) {
                // 十个 tick 还没放上:多半是自己站在放置点上,退开
                state.setInput(Input.MOVE_BACK, true);
            }
            return state;
        }
        MovementHelper.moveTowards(player, state, dest);
        if (MovementHelper.isBottomSlab(jumpingOnto)
                && !MovementHelper.isBottomSlab(level.getBlockState(src.below()))) {
            return state; // 从整块走进下半砖不用跳
        }

        if (NavSettings.get().assumeStep || feet.equals(src.above())) {
            // 已在空中(或有自动上台阶能力),不必再按跳
            return state;
        }

        int xAxis = Math.abs(src.getX() - dest.getX());
        int zAxis = Math.abs(src.getZ() - dest.getZ());
        double flatDistToNext = xAxis * Math.abs((dest.getX() + 0.5) - player.getX())
                + zAxis * Math.abs((dest.getZ() + 0.5) - player.getZ());
        double sideDist = zAxis * Math.abs((dest.getX() + 0.5) - player.getX())
                + xAxis * Math.abs((dest.getZ() + 0.5) - player.getZ());

        double lateralMotion = xAxis * player.getDeltaMovement().z + zAxis * player.getDeltaMovement().x;
        if (player.tickCount % 10 == 0) {
            // 上台阶的四道闸门逐值:卡在这一步时外面只看到"人不动",全靠猜
            com.dwinovo.numen.core.Constants.LOG.debug(
                    "[numen-ascend] src={} dest={} 身位={} 前距={} 侧偏={} 横漂={} 头顶通={} 在地={} 踩={}",
                    src.toShortString(), dest.toShortString(), feet.toShortString(),
                    String.format("%.2f", flatDistToNext), String.format("%.2f", sideDist),
                    String.format("%.3f", lateralMotion), headBonkClear(), player.onGround(),
                    jumpingOnto.getBlock().builtInRegistryHolder().key().location().getPath());
        }
        if (Math.abs(lateralMotion) > 0.1) {
            return state; // 横向还在漂,先走正再跳
        }

        if (headBonkClear()) {
            return state.setInput(Input.JUMP, true); // 头顶四邻通透,随时可跳
        }

        if (flatDistToNext > 1.2 || sideDist > 0.2) {
            return state; // 太远或偏得多,先贴近走正,防跳早撞头
        }

        return state.setInput(Input.JUMP, true);
    }

    /** src 上方两格的四个水平邻格是否全部通透(跳起不会撞头)。 */
    public boolean headBonkClear() {
        BlockPos startUp = src.above(2);
        for (int i = 0; i < 4; i++) {
            BlockPos check = startUp.relative(Direction.from2DDataValue(i));
            if (!MovementHelper.canWalkThrough(player.level(), check)) {
                return false;
            }
        }
        return true;
    }

    /** 已经放过方块就不许中断(半途取消会留柱)。 */
    @Override
    protected boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING || ticksWithoutPlacement == 0;
    }
}
