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
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.SNEAK_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.SPRINT_MULTIPLIER;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST;

/** 平移一格:走到水平相邻的同高度格,必要时挖穿两格身位或在落脚点下搭桥。 */
public class MovementTraverse extends Movement {

    /** 桥块是否一直都在(false 表示本次执行需要现搭)。 */
    private boolean wasTheBridgeBlockAlwaysThere = true;

    public MovementTraverse(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, new BlockPos[]{dest.above(), dest}, dest.below());
    }

    @Override
    public void reset() {
        super.reset();
        wasTheBridgeBlockAlwaysThere = true;
    }

    /**
     * 成本。走路分支:落脚点可站(或霜行者可冻),水中用水速、
     * 灵魂沙两端各计半罚、水面行走加罚,身位两格通透且非水可疾跑;
     * 需要挖时站在梯/藤上挖速 ×5。搭桥分支:落脚点下可替换才可放,
     * 侧贴面(排除来向)可贴按普通走速计,只能背贴时按潜行速
     * ×(潜行/平走) 计,灵魂沙/非双层台阶/水上出发不可背贴。
     */
    public static double cost(CalculationContext context, int x, int y, int z, int destX, int destZ) {
        BlockState pb0 = context.get(destX, y + 1, destZ);
        BlockState pb1 = context.get(destX, y, destZ);
        BlockState destOn = context.get(destX, y - 1, destZ);
        BlockState srcDown = context.get(x, y - 1, z);
        Block srcDownBlock = srcDown.getBlock();
        boolean standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, srcDown);
        boolean frostWalker = standingOnABlock && !context.assumeWalkOnWater
                && MovementHelper.canUseFrostWalker(context, destOn);
        if (frostWalker || MovementHelper.canWalkOn(context, destX, y - 1, destZ, destOn)) {
            // 走路分支:桥本来就在
            double WC = WALK_ONE_BLOCK_COST;
            boolean water = false;
            if (MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1)) {
                WC = context.waterWalkSpeed;
                water = true;
            } else {
                if (destOn.getBlock() == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                } else if (frostWalker) {
                    // 霜行者冻出的冰面走起来没有水面罚金
                } else if (destOn.getBlock() == Blocks.WATER) {
                    WC += context.walkOnWaterOnePenalty;
                }
                if (srcDownBlock == Blocks.SOUL_SAND) {
                    WC += (WALK_ONE_OVER_SOUL_SAND_COST - WALK_ONE_BLOCK_COST) / 2;
                }
            }
            double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
            if (hardness1 >= COST_INF) {
                return COST_INF;
            }
            // 只有上格计入落沙链:挖穿上格才会引沙下坠
            double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true);
            if (hardness1 == 0 && hardness2 == 0) {
                if (!water && context.canSprint) {
                    // 身位通透且不在水里,可以疾跑(灵魂沙上也能疾跑,不必排除)
                    WC *= SPRINT_MULTIPLIER;
                }
                return WC;
            }
            if (srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE) {
                // 挂在梯/藤上挖极慢
                hardness1 *= 5;
                hardness2 *= 5;
            }
            return WC + hardness1 + hardness2;
        } else {
            // 搭桥分支:需要在落脚点下放一块
            if (srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE) {
                return COST_INF;
            }
            if (!MovementHelper.isReplaceable(destX, y - 1, destZ, destOn, context.loadedTest)) {
                return COST_INF;
            }
            boolean throughWater = MovementHelper.isWater(pb0) || MovementHelper.isWater(pb1);
            if (MovementHelper.isWater(destOn) && throughWater) {
                // 在水里且要往水里垫:这套水上行走语义不允许
                return COST_INF;
            }
            double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, destOn);
            if (placeCost >= COST_INF) {
                return COST_INF;
            }
            double hardness1 = MovementHelper.getMiningDurationTicks(context, destX, y, destZ, pb1, false);
            if (hardness1 >= COST_INF) {
                return COST_INF;
            }
            double hardness2 = MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, pb0, true);
            double WC = throughWater ? context.waterWalkSpeed : WALK_ONE_BLOCK_COST;
            for (int i = 0; i < 5; i++) {
                int againstX = destX + MovementPlacement.HORIZONTALS_AND_DOWN[i].getStepX();
                int againstY = y - 1 + MovementPlacement.HORIZONTALS_AND_DOWN[i].getStepY();
                int againstZ = destZ + MovementPlacement.HORIZONTALS_AND_DOWN[i].getStepZ();
                if (againstX == x && againstZ == z) {
                    // 来向那面是背贴,单独按潜行处理
                    continue;
                }
                if (MovementHelper.canPlaceAgainst(context, againstX, againstY, againstZ)) {
                    // 有侧贴面,不必潜行
                    return WC + placeCost + hardness1 + hardness2;
                }
            }
            // 只能背贴(潜行悬出边缘,回身贴脚下那块的侧面放)
            if (srcDownBlock == Blocks.SOUL_SAND
                    || (srcDownBlock instanceof SlabBlock && srcDown.getValue(SlabBlock.TYPE) != SlabType.DOUBLE)) {
                return COST_INF; // 灵魂沙 / 非双层台阶顶面矮一截,背贴瞄不到贴面
            }
            if (!standingOnABlock) {
                return COST_INF; // 站在水上没法潜行背贴
            }
            Block blockSrc = context.getBlock(x, y, z);
            if ((blockSrc == Blocks.LILY_PAD || blockSrc instanceof CarpetBlock)
                    && !srcDown.getFluidState().isEmpty()) {
                return COST_INF; // 睡莲/地毯能站不能贴
            }
            WC = WC * (SNEAK_ONE_BLOCK_COST / WALK_ONE_BLOCK_COST); // 全程潜行
            return WC + placeCost + hardness1 + hardness2;
        }
    }

    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        return cost(context, src.getX(), src.getY(), src.getZ(), dest.getX(), dest.getZ());
    }

    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        Level level = player.level();
        BlockState pb0 = level.getBlockState(positionsToBreak[0]);
        BlockState pb1 = level.getBlockState(positionsToBreak[1]);
        if (state.getStatus() != MovementStatus.RUNNING) {
            // 准备期(挖前方方块)可以边挖边走,贴近后再停下专心挖
            if (!NavSettings.get().walkWhileBreaking) {
                return state;
            }
            if (state.getStatus() != MovementStatus.PREPPING) {
                return state;
            }
            if (MovementHelper.avoidWalkingInto(pb0) || MovementHelper.avoidWalkingInto(pb1)) {
                return state;
            }
            double dist = Math.max(Math.abs(player.getX() - (dest.getX() + 0.5)),
                    Math.abs(player.getZ() - (dest.getZ() + 0.5)));
            if (dist < 0.83) {
                return state; // 已经贴上了
            }
            if (!state.getTarget().hasRotation()) {
                // 偶发:落沙实体迟到,挖掘目标还没建立
                return state;
            }
            // yaw 对准终点中心,pitch 沿用挖掘目标;两格都是整方块/空气时用固定俯角
            float yawToDest = MovementHelper.yawTo(player.getEyePosition(), MovementHelper.blockCenter(dest));
            float pitchToBreak = state.getTarget().getPitch();
            if (MovementHelper.isBlockNormalCube(pb0)
                    || (pb0.getBlock() instanceof AirBlock
                            && (MovementHelper.isBlockNormalCube(pb1) || pb1.getBlock() instanceof AirBlock))) {
                pitchToBreak = 26;
            }
            return state.setTarget(new MovementState.MovementTarget(yawToDest, pitchToBreak, true))
                    .setInput(Input.MOVE_FORWARD, true)
                    .setInput(Input.SPRINT, true);
        }

        // 准备期可能按过潜行,先松开
        state.setInput(Input.SNEAK, false);

        Block fd = level.getBlockState(src.below()).getBlock();
        boolean ladder = fd == Blocks.LADDER || fd == Blocks.VINE;

        // 木门:挡路且能开 → 看门中心右键
        if (pb0.getBlock() instanceof DoorBlock || pb1.getBlock() instanceof DoorBlock) {
            boolean notPassable = (pb0.getBlock() instanceof DoorBlock
                            && !MovementHelper.isDoorPassable(level, src, dest))
                    || (pb1.getBlock() instanceof DoorBlock
                            && !MovementHelper.isDoorPassable(level, dest, src));
            boolean canOpen = !(pb0.getBlock() == Blocks.IRON_DOOR || pb1.getBlock() == Blocks.IRON_DOOR);
            if (notPassable && canOpen) {
                Vec3 center = MovementHelper.blockCenter(positionsToBreak[0]);
                return state.setTarget(new MovementState.MovementTarget(
                                MovementHelper.yawTo(player.getEyePosition(), center),
                                MovementHelper.pitchTo(player.getEyePosition(), center), true))
                        .setInput(Input.CLICK_RIGHT, true);
            }
        }

        // 栅栏门:找出挡路的那扇,射线可视时才看向它右键;完全不可视
        // 则不点击,落到后续行走逻辑继续推进
        if (pb0.getBlock() instanceof FenceGateBlock || pb1.getBlock() instanceof FenceGateBlock) {
            BlockPos blocked = !MovementHelper.isGatePassable(level, positionsToBreak[0], src.above())
                    ? positionsToBreak[0]
                    : !MovementHelper.isGatePassable(level, positionsToBreak[1], src)
                            ? positionsToBreak[1]
                            : null;
            if (blocked != null) {
                Vec3 aim = MovementHelper.reachableAimPoint(player, blocked);
                if (aim != null) {
                    return state.setTarget(new MovementState.MovementTarget(
                                    MovementHelper.yawTo(player.getEyePosition(), aim),
                                    MovementHelper.pitchTo(player.getEyePosition(), aim), true))
                            .setInput(Input.CLICK_RIGHT, true);
                }
            }
        }

        boolean isTheBridgeBlockThere = MovementHelper.canWalkOn(level, positionToPlace)
                || ladder
                || MovementPlacement.canUseFrostWalker(player, level.getBlockState(positionToPlace));
        BlockPos feet = feet(player);
        if (feet.getY() != dest.getY() && !ladder) {
            // 高度不对:低了跳一下,高了等下落
            if (feet.getY() < dest.getY()) {
                return state.setInput(Input.JUMP, true);
            }
            return state;
        }

        if (isTheBridgeBlockThere) {
            if (feet.equals(dest)) {
                return state.setStatus(MovementStatus.SUCCESS);
            }
            BlockPos dir = getDirection();
            if (NavSettings.get().overshootTraverse
                    && (feet.equals(dest.offset(dir)) || feet.equals(dest.offset(dir).offset(dir)))) {
                // 冲过头一两格也算成功,不必回身减速
                return state.setStatus(MovementStatus.SUCCESS);
            }
            Block low = level.getBlockState(src).getBlock();
            Block high = level.getBlockState(src.above()).getBlock();
            if (player.getY() > src.getY() + 0.1 && !player.onGround()
                    && (low == Blocks.VINE || low == Blocks.LADDER
                            || high == Blocks.VINE || high == Blocks.LADDER)) {
                // 挂在梯/藤上离地,按前进会变成往上爬,等落地
                return state;
            }
            BlockPos into = dest.subtract(src).offset(dest);
            BlockState intoBelow = level.getBlockState(into);
            BlockState intoAbove = level.getBlockState(into.above());
            if (wasTheBridgeBlockAlwaysThere
                    && (!MovementHelper.isLiquid(level.getBlockState(feet)) || NavSettings.get().sprintInWater)
                    && (!MovementHelper.avoidWalkingInto(intoBelow) || MovementHelper.isWater(intoBelow))
                    && !MovementHelper.avoidWalkingInto(intoAbove)) {
                state.setInput(Input.SPRINT, true);
            }

            BlockState destDown = level.getBlockState(dest.below());
            BlockPos against = positionsToBreak[0];
            if (feet.getY() != dest.getY() && ladder
                    && (destDown.getBlock() == Blocks.VINE || destDown.getBlock() == Blocks.LADDER)) {
                // 往下一段梯/藤走:朝攀爬贴面方向走
                against = destDown.getBlock() == Blocks.VINE
                        ? MovementPillar.getAgainst(level, dest.below())
                        : dest.relative(destDown.getValue(LadderBlock.FACING).getOpposite());
                if (against == null) {
                    return state.setStatus(MovementStatus.UNREACHABLE);
                }
            }
            MovementHelper.moveTowards(player, state, against);
            return state;
        } else {
            // 搭桥执行:桥块不在(或被挖掉了),现场放
            wasTheBridgeBlockAlwaysThere = false;
            Block standingOn = level.getBlockState(feet.below()).getBlock();
            if (standingOn == Blocks.SOUL_SAND || standingOn instanceof SlabBlock) {
                // 顶面矮一截的方块上潜行容易滑出去,离得近就倒着走稳住
                double dist = Math.max(Math.abs(dest.getX() + 0.5 - player.getX()),
                        Math.abs(dest.getZ() + 0.5 - player.getZ()));
                if (dist < 0.85) { // 0.5 + 0.3 + 容差
                    MovementHelper.moveTowards(player, state, dest);
                    return state.setInput(Input.MOVE_FORWARD, false)
                            .setInput(Input.MOVE_BACK, true);
                }
            }
            double dist1 = Math.max(Math.abs(player.getX() - (dest.getX() + 0.5)),
                    Math.abs(player.getZ() - (dest.getZ() + 0.5)));
            MovementPlacement.PlaceResult p = MovementPlacement.attemptToPlaceABlock(
                    state, player, dest.below(), false, true);
            if ((p == MovementPlacement.PlaceResult.READY_TO_PLACE || dist1 < 0.6)
                    && !NavSettings.get().assumeSafeWalk) {
                state.setInput(Input.SNEAK, true);
            }
            switch (p) {
                case READY_TO_PLACE: {
                    // 潜行确认后下一 tick 才右键(先蹲住再放,防滑落)
                    if (player.isCrouching() || NavSettings.get().assumeSafeWalk) {
                        state.setInput(Input.CLICK_RIGHT, true);
                    }
                    return state;
                }
                case ATTEMPTING: {
                    if (dist1 > 0.83) {
                        // 还没贴上去,放置目标又在正前方时才敢继续前进
                        float yaw = MovementHelper.yawTo(player.getEyePosition(),
                                MovementHelper.blockCenter(dest));
                        if (Math.abs(state.getTarget().getYaw() - yaw) < 0.1) {
                            return state.setInput(Input.MOVE_FORWARD, true);
                        }
                    } else if (MovementPlacement.isFacing(player, state.getTarget())) {
                        // 对准了还放不上,说明有东西挡着,打掉
                        return state.setInput(Input.CLICK_LEFT, true);
                    }
                    return state;
                }
                default:
                    break;
            }
            if (feet.equals(dest)) {
                // 已潜行悬在目标格上空:回身贴 src 脚下那块的侧面放(背贴)
                double faceX = (dest.getX() + src.getX() + 1.0) * 0.5;
                double faceY = (dest.getY() + src.getY() - 1.0) * 0.5;
                double faceZ = (dest.getZ() + src.getZ() + 1.0) * 0.5;
                Vec3 face = new Vec3(faceX, faceY, faceZ);
                BlockPos goalLook = src.below(); // 刚离开的脚下块,就贴它
                float facePitch = MovementHelper.pitchTo(player.getEyePosition(), face);
                double dist2 = Math.max(Math.abs(player.getX() - faceX),
                        Math.abs(player.getZ() - faceZ));
                if (dist2 < 0.29) {
                    // 贴面太近瞄不到,反向 yaw 倒退拉开距离
                    float yaw = MovementHelper.yawTo(MovementHelper.blockCenter(dest),
                            player.getEyePosition());
                    state.setTarget(new MovementState.MovementTarget(yaw, facePitch, true));
                    state.setInput(Input.MOVE_BACK, true);
                } else {
                    state.setTarget(new MovementState.MovementTarget(
                            MovementHelper.yawTo(player.getEyePosition(), face), facePitch, true));
                }
                if (MovementPlacement.isLookingAt(player, goalLook)) {
                    return state.setInput(Input.CLICK_RIGHT, true);
                }
                if (MovementPlacement.isFacing(player, state.getTarget())) {
                    // 对准了还不行,有杂物挡视线,打掉
                    state.setInput(Input.CLICK_LEFT, true);
                }
                return state;
            }
            MovementHelper.moveTowards(player, state, positionsToBreak[0]);
            return state;
        }
    }

    /** 正在潜行悬空放置时不可中断,其余时刻可。 */
    @Override
    protected boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING
                || MovementHelper.canWalkOn(player.level(), dest.below());
    }

    /** 站在梯/藤上挖掘时按住潜行防滑落。 */
    @Override
    protected boolean prepared(MovementState state) {
        BlockPos feet = feet(player);
        if (feet.equals(src) || feet.equals(src.below())) {
            Block block = player.level().getBlockState(src.below()).getBlock();
            if (block == Blocks.LADDER || block == Blocks.VINE) {
                state.setInput(Input.SNEAK, true);
            }
        }
        return super.prepared(state);
    }
}
