package com.dwinovo.numen.core.pathing.moves.movements;

import java.util.HashSet;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.SPRINT_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_BLOCK_COST;

/** 跑酷跳:助跑跃过 2-4 格空隙落到同高或高一格的落点,从不挖方块。 */
public class MovementParkour extends Movement {

    private static final BlockPos[] EMPTY = new BlockPos[0];

    private final Direction direction;
    private final int dist;
    private final boolean ascend;

    public MovementParkour(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, EMPTY, dest.below());
        int dx = dest.getX() - src.getX();
        int dz = dest.getZ() - src.getZ();
        this.direction = Direction.getNearest(dx, 0, dz, Direction.NORTH);
        this.dist = Math.abs(dx) + Math.abs(dz);
        this.ascend = dest.getY() > src.getY();
    }

    /**
     * 成本:实际落点与成本写进 result。前置链:总开关、建筑高度上限、
     * 紧邻格空(能走就不跑酷)、紧邻下方无危险、起跳净空、起跳块不是
     * 梯/藤/楼梯/下半砖、不在(且不站)水里。逐格验证 2..maxJump
     * (灵魂沙 2、疾跑 4、否则 3):落点柱两层净空;落点格实心 →
     * 跑酷上升判定;落点下可站 → 平跳落点(过冲两格安全才收);
     * 均无则需更高一层净空才能继续飞。开启跳跃放置时,从最远验证距离
     * 往回找可放贴面(排除跳来的反方向)。
     */
    public static void cost(CalculationContext context, int x, int y, int z,
                            Direction dir, MutableMoveResult res) {
        if (!context.allowParkour) {
            return;
        }
        if (!context.allowJumpAtBuildLimit && y >= context.worldHeight - 1) {
            return;
        }
        int xDiff = dir.getStepX();
        int zDiff = dir.getStepZ();
        if (!MovementHelper.fullyPassable(context, x + xDiff, y, z + zDiff)) {
            // 最常见剪枝:紧邻格不是空的
            return;
        }
        BlockState adj = context.get(x + xDiff, y - 1, z + zDiff);
        if (MovementHelper.canWalkOn(context, x + xDiff, y - 1, z + zDiff, adj)) {
            // 能直接走过去就不跑酷
            return;
        }
        if (MovementHelper.avoidWalkingInto(adj) && !(adj.getFluidState().getType() instanceof WaterFluid)) {
            return; // 紧邻下方是岩浆之类,过冲危险
        }
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 1, z + zDiff)) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x + xDiff, y + 2, z + zDiff)) {
            return;
        }
        if (!MovementHelper.fullyPassable(context, x, y + 2, z)) {
            return;
        }
        BlockState standingOn = context.get(x, y - 1, z);
        if (standingOn.getBlock() == Blocks.VINE || standingOn.getBlock() == Blocks.LADDER
                || standingOn.getBlock() instanceof StairBlock
                || MovementHelper.isBottomSlab(standingOn)) {
            return;
        }
        // 水面行走语义下不能确定脚下的水已冻住,不敢起跳
        if (context.assumeWalkOnWater && !standingOn.getFluidState().isEmpty()) {
            return;
        }
        if (!context.get(x, y, z).getFluidState().isEmpty()) {
            return; // 水里跳不起来
        }
        int maxJump;
        if (standingOn.getBlock() == Blocks.SOUL_SAND) {
            maxJump = 2; // 只能跳过一格空隙
        } else {
            maxJump = context.canSprint ? 4 : 3;
        }

        // 从近到远逐格验证障碍与落点
        int verifiedMaxJump = 1;
        for (int i = 2; i <= maxJump; i++) {
            int destX = x + xDiff * i;
            int destZ = z + zDiff * i;

            // 身位与头顶
            if (!MovementHelper.fullyPassable(context, destX, y + 1, destZ)) {
                break;
            }
            if (!MovementHelper.fullyPassable(context, destX, y + 2, destZ)) {
                break;
            }

            // 落点格实心:跑酷上升判定
            BlockState destInto = context.get(destX, y, destZ);
            if (!MovementHelper.fullyPassable(context, destX, y, destZ, destInto)) {
                if (i <= 3 && context.allowParkourAscend && context.canSprint
                        && MovementHelper.canWalkOn(context, destX, y, destZ, destInto)
                        && checkOvershootSafety(context, destX + xDiff, y + 1, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y + 1;
                    res.z = destZ;
                    res.cost = i * SPRINT_ONE_BLOCK_COST + context.jumpPenalty;
                    return;
                }
                break; // 撞墙:转入跳跃放置扫描
            }

            // 平跳落点:禁落耕地(踩塌);霜行者可冻住水面时也算落点
            BlockState landingOn = context.get(destX, y - 1, destZ);
            if ((landingOn.getBlock() != Blocks.FARMLAND
                    && MovementHelper.canWalkOn(context, destX, y - 1, destZ, landingOn))
                    || (Math.min(16, context.frostWalker + 2) >= i
                            && MovementHelper.canUseFrostWalker(context, landingOn))) {
                if (checkOvershootSafety(context, destX + xDiff, y, destZ + zDiff)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + context.jumpPenalty;
                    return;
                }
                break; // 落点过冲不安全:转入跳跃放置扫描
            }

            // 没有落点:再高一层也得通透才能继续往更远飞
            if (!MovementHelper.fullyPassable(context, destX, y + 3, destZ)) {
                break;
            }

            verifiedMaxJump = i;
        }

        // 跳跃放置:空中在落点下方放一块
        if (!context.allowParkourPlace) {
            return;
        }
        // 从最远的已验证距离往回找可放位置
        for (int i = verifiedMaxJump; i > 1; i--) {
            int destX = x + i * xDiff;
            int destZ = z + i * zDiff;
            BlockState toReplace = context.get(destX, y - 1, destZ);
            double placeCost = context.costOfPlacingAt(destX, y - 1, destZ, toReplace);
            if (placeCost >= COST_INF) {
                continue;
            }
            if (!MovementHelper.isReplaceable(destX, y - 1, destZ, toReplace, context.loadedTest)) {
                continue;
            }
            if (!checkOvershootSafety(context, destX + xDiff, y, destZ + zDiff)) {
                continue;
            }
            for (int j = 0; j < 5; j++) {
                int againstX = destX + MovementPlacement.HORIZONTALS_AND_DOWN[j].getStepX();
                int againstY = y - 1 + MovementPlacement.HORIZONTALS_AND_DOWN[j].getStepY();
                int againstZ = destZ + MovementPlacement.HORIZONTALS_AND_DOWN[j].getStepZ();
                if (againstX == destX - xDiff && againstZ == destZ - zDiff) {
                    continue; // 跳来的反方向,空中转身太慢瞄不到
                }
                if (MovementHelper.canPlaceAgainst(context, againstX, againstY, againstZ)) {
                    res.x = destX;
                    res.y = y;
                    res.z = destZ;
                    res.cost = costFromJumpDistance(i) + placeCost + context.jumpPenalty;
                    return;
                }
            }
        }
    }

    /** 落地后还会往前冲两格身位,那两格不能是危险格。 */
    private static boolean checkOvershootSafety(CalculationContext context, int x, int y, int z) {
        return !MovementHelper.avoidWalkingInto(context.get(x, y, z))
                && !MovementHelper.avoidWalkingInto(context.get(x, y + 1, z));
    }

    /** 跳距 → 成本:2/3 格按平走计,4 格必须疾跑。 */
    private static double costFromJumpDistance(int dist) {
        switch (dist) {
            case 2:
                return WALK_ONE_BLOCK_COST * 2;
            case 3:
                return WALK_ONE_BLOCK_COST * 3;
            case 4:
                return SPRINT_ONE_BLOCK_COST * 4;
            default:
                throw new IllegalStateException("非法跳距 " + dist);
        }
    }

    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        cost(context, src.getX(), src.getY(), src.getZ(), direction, result);
        if (result.x != dest.getX() || result.y != dest.getY() || result.z != dest.getZ()) {
            return COST_INF;
        }
        return result.cost;
    }

    /** src 起沿方向每格两层。 */
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        Set<BlockPos> set = new HashSet<>();
        for (int i = 0; i <= dist; i++) {
            for (int y = 0; y < 2; y++) {
                set.add(src.relative(direction, i).above(y));
            }
        }
        return set;
    }

    /** 起跳后动量不可控,RUNNING 之后绝不可取消。 */
    @Override
    protected boolean safeToCancel(MovementState state) {
        return state.getStatus() != MovementStatus.RUNNING;
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }
        BlockPos feet = feet(player);
        if (feet.getY() < src.getY()) {
            // 掉下去了
            return state.setStatus(MovementStatus.UNREACHABLE);
        }
        if (dist >= 4 || ascend) {
            state.setInput(Input.SPRINT, true);
        }
        MovementHelper.moveTowards(player, state, dest);
        if (feet.equals(dest)) {
            Block d = player.level().getBlockState(dest).getBlock();
            if (d == Blocks.VINE || d == Blocks.LADDER) {
                // 跳到藤/梯上直接算成功
                return state.setStatus(MovementStatus.SUCCESS);
            }
            if (player.getY() - feet.getY() < 0.094) { // 睡莲容差
                state.setStatus(MovementStatus.SUCCESS);
            }
        } else if (!feet.equals(src)) {
            if (feet.equals(src.relative(direction)) || player.getY() - src.getY() > 0.0001) {
                // 已跳出第一格或已离地
                if (NavSettings.get().allowPlace
                        && MovementPlacement.selectForLocation(player, dest.below(), false)
                        && !MovementHelper.canWalkOn(player.level(), dest.below())
                        && !player.onGround()
                        && MovementPlacement.attemptToPlaceABlock(state, player, dest.below(), true, false)
                                == MovementPlacement.PlaceResult.READY_TO_PLACE) {
                    // preferDown:优先朝下的贴面,空中不必歪头搅乱轨迹
                    state.setInput(Input.CLICK_RIGHT, true);
                }
                if (dist == 3 && !ascend) {
                    // 两格空隙:离起点不足 0.7 时先不跳,防早跳落坑
                    double xDiff = (src.getX() + 0.5) - player.getX();
                    double zDiff = (src.getZ() + 0.5) - player.getZ();
                    double distFromStart = Math.max(Math.abs(xDiff), Math.abs(zDiff));
                    if (distFromStart < 0.7) {
                        return state;
                    }
                }
                state.setInput(Input.JUMP, true);
            } else if (!feet.equals(dest.relative(direction, -1))) {
                // 走偏了:松开疾跑,退回起跳线重来
                state.setInput(Input.SPRINT, false);
                if (feet.equals(src.relative(direction, -1))) {
                    MovementHelper.moveTowards(player, state, src);
                } else {
                    MovementHelper.moveTowards(player, state, src.relative(direction, -1));
                }
            }
        }
        return state;
    }
}

