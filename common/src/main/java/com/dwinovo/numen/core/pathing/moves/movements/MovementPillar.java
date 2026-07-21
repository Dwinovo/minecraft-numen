package com.dwinovo.numen.core.pathing.moves.movements;

import java.util.Set;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.JUMP_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.LADDER_UP_ONE_COST;

/** 垫柱上一格:原地起跳在脚下放方块(或沿梯子/藤蔓/水柱直接上一格)。 */
public class MovementPillar extends Movement {

    public MovementPillar(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, new BlockPos[]{src.above(2)}, src);
    }

    /**
     * 成本。四形态:梯/藤直接爬(头顶要挖时挖速 ×5);已在水柱中
     * 上游按爬梯价;其余为垫柱——跳跃 + 放置罚金 + 跳跃罚金 + 头顶
     * 挖掘,悬空垫柱轻罚 +0.1。梯上不能垫、下半砖上不能垫、水上
     * (按水面行走语义)不能垫、睡莲/地毯浮在流体上不能垫、头顶是
     * 栅栏门不可行,头顶落沙柱只有整根都是落沙时才敢挖。
     */
    public static double cost(CalculationContext context, int x, int y, int z) {
        BlockState fromState = context.get(x, y, z);
        Block from = fromState.getBlock();
        boolean ladder = from == Blocks.LADDER || from == Blocks.VINE;
        BlockState fromDown = context.get(x, y - 1, z);
        if (!ladder) {
            if (fromDown.getBlock() == Blocks.LADDER || fromDown.getBlock() == Blocks.VINE) {
                return COST_INF; // 站在梯/藤顶上垫不了柱
            }
            if (fromDown.getBlock() instanceof SlabBlock
                    && fromDown.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
                return COST_INF; // 下半砖上跳不满一格
            }
        }
        if (from == Blocks.VINE && !hasAgainst(context, x, y, z)) {
            return COST_INF; // 四邻无实心方块的藤蔓爬不了
        }
        BlockState toBreak = context.get(x, y + 2, z);
        Block toBreakBlock = toBreak.getBlock();
        if (toBreakBlock instanceof FenceGateBlock) {
            return COST_INF; // 栅栏门顶头,跳不过去也挖不干净
        }
        BlockState srcUp = null;
        if (MovementHelper.isWater(toBreak) && MovementHelper.isWater(fromState)) {
            srcUp = context.get(x, y + 1, z);
            if (MovementHelper.isWater(srcUp)) {
                return LADDER_UP_ONE_COST; // 已在水柱中,允许继续上游
            }
        }
        double placeCost = 0;
        if (!ladder) {
            // 要在出发格原位放一块垫脚
            placeCost = context.costOfPlacingAt(x, y, z, fromState);
            if (placeCost >= COST_INF) {
                return COST_INF;
            }
            if (fromDown.getBlock() instanceof AirBlock) {
                placeCost += 0.1; // 悬空垫柱轻罚(1/200 秒)
            }
        }
        if ((MovementHelper.isLiquid(fromState)
                        && !MovementHelper.canPlaceAgainst(context, x, y - 1, z, fromDown))
                || (MovementHelper.isLiquid(fromDown) && context.assumeWalkOnWater)) {
            // 泡在水里贴不到下面、或按水面行走语义站在水上,都垫不了
            return COST_INF;
        }
        if ((from == Blocks.LILY_PAD || from instanceof CarpetBlock)
                && !fromDown.getFluidState().isEmpty()) {
            return COST_INF; // 想上去得先拆自己站的睡莲/地毯
        }
        double hardness = MovementHelper.getMiningDurationTicks(context, x, y + 2, z, toBreak, true);
        if (hardness >= COST_INF) {
            return COST_INF;
        }
        if (hardness != 0) {
            if (toBreakBlock == Blocks.LADDER || toBreakBlock == Blocks.VINE) {
                hardness = 0; // 头顶是梯/藤:直接爬,不挖
            } else {
                BlockState check = context.get(x, y + 3, z);
                if (check.getBlock() instanceof FallingBlock) {
                    // 头顶再上一格是落沙:除非整根(含身位上格)都是落沙
                    // 早已被清理的情形,否则挖了会塌下来闷住
                    if (srcUp == null) {
                        srcUp = context.get(x, y + 1, z);
                    }
                    if (!(toBreakBlock instanceof FallingBlock)
                            || !(srcUp.getBlock() instanceof FallingBlock)) {
                        return COST_INF;
                    }
                }
            }
        }
        if (ladder) {
            return LADDER_UP_ONE_COST + hardness * 5; // 挂梯上挖极慢
        } else {
            return JUMP_ONE_BLOCK_COST + placeCost + context.jumpPenalty + hardness;
        }
    }

    /** 四个水平邻格有无实心方块(藤蔓攀爬依据)。 */
    public static boolean hasAgainst(CalculationContext context, int x, int y, int z) {
        return MovementHelper.isBlockNormalCube(context.get(x + 1, y, z))
                || MovementHelper.isBlockNormalCube(context.get(x - 1, y, z))
                || MovementHelper.isBlockNormalCube(context.get(x, y, z + 1))
                || MovementHelper.isBlockNormalCube(context.get(x, y, z - 1));
    }

    /** 藤蔓格四邻里第一个实心方块(攀爬贴面),没有则 null。 */
    public static BlockPos getAgainst(BlockGetter level, BlockPos vine) {
        if (MovementHelper.isBlockNormalCube(level.getBlockState(vine.north()))) {
            return vine.north();
        }
        if (MovementHelper.isBlockNormalCube(level.getBlockState(vine.south()))) {
            return vine.south();
        }
        if (MovementHelper.isBlockNormalCube(level.getBlockState(vine.east()))) {
            return vine.east();
        }
        if (MovementHelper.isBlockNormalCube(level.getBlockState(vine.west()))) {
            return vine.west();
        }
        return null;
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

        if (feet(player).getY() < src.getY()) {
            return state.setStatus(MovementStatus.UNREACHABLE);
        }

        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        BlockState fromDown = level.getBlockState(src);
        if (MovementHelper.isWater(fromDown)
                && MovementHelper.isWater(level.getBlockState(dest))) {
            // 水柱:看向上方目标中心保持居中上浮(上浮强跳由基类通用规则按)
            Vec3 destCenter = MovementHelper.blockCenter(dest);
            state.setTarget(new MovementState.MovementTarget(
                    MovementHelper.yawTo(eye, destCenter),
                    MovementHelper.pitchTo(eye, destCenter), false));
            if (Math.abs(player.getX() - destCenter.x) > 0.2
                    || Math.abs(player.getZ() - destCenter.z) > 0.2) {
                state.setInput(Input.MOVE_FORWARD, true);
            }
            if (feet(player).equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            return state;
        }
        boolean ladder = fromDown.getBlock() == Blocks.LADDER || fromDown.getBlock() == Blocks.VINE;
        boolean vine = fromDown.getBlock() == Blocks.VINE;
        Vec3 placeCenter = MovementHelper.blockCenter(positionToPlace);
        float placePitch = MovementHelper.pitchTo(eye, placeCenter);
        if (!ladder) {
            // 只压 pitch 看向脚下放置点,yaw 保持current(垫柱不需要转身)
            state.setTarget(new MovementState.MovementTarget(player.getYRot(), placePitch, true));
        }

        boolean blockIsThere = MovementHelper.canWalkOn(level, src) || ladder;
        if (ladder) {
            BlockPos against = vine
                    ? getAgainst(level, src)
                    : src.relative(fromDown.getValue(LadderBlock.FACING).getOpposite());
            if (against == null) {
                return state.setStatus(MovementStatus.UNREACHABLE); // 藤蔓四邻无贴面爬不了
            }
            if (feet(player).equals(against.above())
                    || feet(player).equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (MovementHelper.isBottomSlab(level.getBlockState(src.below()))) {
                state.setInput(Input.JUMP, true); // 从下半砖上够梯子得先跳
            }
            MovementHelper.moveTowards(player, state, against);
            return state;
        } else {
            // 垫柱:先把耗材拿到手
            if (!MovementPlacement.selectForLocation(player, src, true)) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }

            // 高过目标或还没跳起时按潜行:先蹲一 tick 再右键,放置更稳
            state.setInput(Input.SNEAK,
                    player.getY() > dest.getY() || player.getY() < src.getY() + 0.2);

            double diffX = player.getX() - (dest.getX() + 0.5);
            double diffZ = player.getZ() - (dest.getZ() + 0.5);
            double dist = Math.sqrt(diffX * diffX + diffZ * diffZ);
            double flatMotion = Math.sqrt(player.getDeltaMovement().x * player.getDeltaMovement().x
                    + player.getDeltaMovement().z * player.getDeltaMovement().z);
            if (dist > 0.17) { // 需小于潜行边缘保护的 0.2,留余量
                // 偏出中心(可能被卡):完整看向放置点走回去
                state.setInput(Input.MOVE_FORWARD, true);
                state.setTarget(new MovementState.MovementTarget(
                        MovementHelper.yawTo(eye, placeCenter), placePitch, true));
            } else if (flatMotion < 0.05) {
                // 横速稳了才跳;到高度就松跳
                state.setInput(Input.JUMP, player.getY() < dest.getY());
            }

            if (!blockIsThere) {
                BlockState frState = level.getBlockState(src);
                if (!(frState.getBlock() instanceof AirBlock || frState.canBeReplaced())) {
                    // 出发格有不可替换的杂物:射线可视时才改看向它;跳着挖
                    // 慢五倍,停跳,按左键打掉
                    Vec3 aim = MovementHelper.reachableAimPoint(player, src);
                    if (aim != null) {
                        state.setTarget(new MovementState.MovementTarget(
                                MovementHelper.yawTo(eye, aim),
                                MovementHelper.pitchTo(eye, aim), true));
                    }
                    state.setInput(Input.JUMP, false);
                    state.setInput(Input.CLICK_LEFT, true);
                } else if (player.isCrouching()
                        && (MovementPlacement.isLookingAt(player, src.below())
                                || MovementPlacement.isLookingAt(player, src))
                        && player.getY() > dest.getY() + 0.1) {
                    // 已蹲稳、看准、跳够高度:放块
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
        }

        if (feet(player).equals(dest) && blockIsThere) {
            return state.setStatus(MovementStatus.SUCCESS);
        }
        return state;
    }

    /** 站梯/藤上挖掘时按住潜行;头顶目标格上方是水则不做准备挖掘。 */
    @Override
    protected boolean prepared(MovementState state) {
        BlockPos feet = feet(player);
        if (feet.equals(src) || feet.equals(src.below())) {
            Block block = player.level().getBlockState(src.below()).getBlock();
            if (block == Blocks.LADDER || block == Blocks.VINE) {
                state.setInput(Input.SNEAK, true);
            }
        }
        if (MovementHelper.isWater(player.level().getBlockState(dest.above()))) {
            return true;
        }
        return super.prepared(state);
    }
}

