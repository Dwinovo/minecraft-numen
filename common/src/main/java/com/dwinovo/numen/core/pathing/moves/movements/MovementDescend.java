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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.CENTER_AFTER_FALL_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.FALL_N_BLOCKS_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.LADDER_DOWN_ONE_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_OFF_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST;

/** 下一格:走下水平相邻且低一格的落点;落点更深时由成本函数移交坠落语义。 */
public class MovementDescend extends Movement {

    /** 冲出边缘阶段的计 tick(前 20 tick 冲 fakeDest 加速离沿)。 */
    private int numTicks = 0;
    /** 执行层根据下一动作注入的强制稳走标志。 */
    private boolean forceSafeMode = false;

    public MovementDescend(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest,
                new BlockPos[]{dest.above(2), dest.above(), dest}, dest.below());
    }

    @Override
    public void reset() {
        super.reset();
        numTicks = 0;
        forceSafeMode = false;
    }

    /** 由路径执行器调用:下一动作需要本次下降稳着走。 */
    public void forceSafeMode() {
        forceSafeMode = true;
    }

    /**
     * 成本。先按 落点下一格 / 落点格 / 落点上格(含落沙链)三挖序累加;
     * 从梯/藤出发不可行;落点下二格不可站则转坠落分档;落点是梯/藤或
     * 会被霜行者冻住则不可行(退化为平移处理);否则走离边缘 + 落一格。
     */
    public static void cost(CalculationContext context, int x, int y, int z,
                            int destX, int destZ, MutableMoveResult res) {
        double totalCost = 0;
        BlockState destDown = context.get(destX, y - 1, destZ);
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y - 1, destZ, destDown, false);
        if (totalCost >= COST_INF) {
            return;
        }
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y, destZ, false);
        if (totalCost >= COST_INF) {
            return;
        }
        // 三格里只有最上面那格需要考虑引落沙链
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, true);
        if (totalCost >= COST_INF) {
            return;
        }

        Block fromDown = context.get(x, y - 1, z).getBlock();
        if (fromDown == Blocks.LADDER || fromDown == Blocks.VINE) {
            return;
        }

        BlockState below = context.get(destX, y - 2, destZ);
        if (!MovementHelper.canWalkOn(context, destX, y - 2, destZ, below)) {
            // 下面还空:转坠落分档
            dynamicFallCost(context, x, y, z, destX, destZ, totalCost, below, res);
            return;
        }

        if (destDown.getBlock() == Blocks.LADDER || destDown.getBlock() == Blocks.VINE) {
            return;
        }
        if (MovementHelper.canUseFrostWalker(context, destDown)) {
            return; // 走过去水面会被冻住,这一步实际是平移
        }

        // 走半格加 0.3 到边缘,剩下 0.2 与下落并行
        double walk = WALK_OFF_BLOCK_COST;
        if (fromDown == Blocks.SOUL_SAND) {
            walk *= WALK_ONE_OVER_SOUL_SAND_COST / WALK_ONE_BLOCK_COST;
        }
        totalCost += walk + Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST);
        res.x = destX;
        res.y = y - 1;
        res.z = destZ;
        res.cost = totalCost;
    }

    /**
     * 坠落分档:逐层向下扫,遇水(可穿、非流动、非水面行走、下有底)
     * 即落水;遇梯/藤(净坠 ≤11 才抓得住)重置有效落速继续;可站且
     * 净坠不超无水上限则落地;超限但有水桶且不超水桶上限则加放桶价
     * 并返回 true(执行期需要放水桶);下半砖/不可站(如岩浆)放弃。
     */
    public static boolean dynamicFallCost(CalculationContext context, int x, int y, int z,
                                          int destX, int destZ, double frontBreak,
                                          BlockState below, MutableMoveResult res) {
        if (frontBreak != 0 && context.get(destX, y + 2, destZ).getBlock() instanceof FallingBlock) {
            // 前壁要挖就会惊动这根沙柱塌进坑里(还可能填掉要落的水),放弃
            return false;
        }
        if (!MovementHelper.canWalkThrough(context, destX, y - 2, destZ, below)) {
            return false;
        }
        double costSoFar = 0;
        int effectiveStartHeight = y;
        for (int fallHeight = 3; true; fallHeight++) {
            int newY = y - fallHeight;
            if (newY < context.worldBottom) {
                // 末地可能一路落进虚空,别读世界外的方块
                return false;
            }
            boolean reachedMinimum = fallHeight >= context.minFallHeight;
            BlockState ontoBlock = context.get(destX, newY, destZ);
            // 被梯/藤重置后的净坠高度
            int unprotectedFallHeight = fallHeight - (y - effectiveStartHeight);
            double tentativeCost = WALK_OFF_BLOCK_COST
                    + FALL_N_BLOCKS_COST[unprotectedFallHeight] + frontBreak + costSoFar;
            if (reachedMinimum && MovementHelper.isWater(ontoBlock)) {
                if (!MovementHelper.canWalkThrough(context, destX, newY, destZ, ontoBlock)) {
                    return false;
                }
                if (context.assumeWalkOnWater) {
                    return false;
                }
                if (MovementHelper.isFlowing(context.view, destX, newY, destZ, ontoBlock)) {
                    return false;
                }
                if (!MovementHelper.canWalkOn(context, destX, newY - 1, destZ)) {
                    // 水太浅会直接穿透砸到下面的东西
                    return false;
                }
                // 落水,不需要水桶
                res.x = destX;
                res.y = newY;
                res.z = destZ;
                res.cost = tentativeCost;
                return false;
            }
            if (unprotectedFallHeight <= 11
                    && (ontoBlock.getBlock() == Blocks.VINE || ontoBlock.getBlock() == Blocks.LADDER)) {
                // 净坠 ≥11 格时抓不住梯/藤;抓住即重置落速
                costSoFar += FALL_N_BLOCKS_COST[unprotectedFallHeight - 1]; // 落到该格顶面(不含该格)
                costSoFar += LADDER_DOWN_ONE_COST;
                effectiveStartHeight = newY;
                continue;
            }
            if (MovementHelper.canWalkThrough(context, destX, newY, destZ, ontoBlock)) {
                continue;
            }
            if (!MovementHelper.canWalkOn(context, destX, newY, destZ, ontoBlock)) {
                return false; // 岩浆之类:不可穿也不可站
            }
            if (MovementHelper.isBottomSlab(ontoBlock)) {
                return false; // 落半砖判定飘忽且额外摔伤
            }
            if (reachedMinimum && unprotectedFallHeight <= context.maxFallHeightNoWater + 1) {
                // fallHeight=4 时落点上格恰好低三格,是无水上限
                res.x = destX;
                res.y = newY + 1;
                res.z = destZ;
                res.cost = tentativeCost;
                return false;
            }
            if (reachedMinimum && context.hasWaterBucket
                    && unprotectedFallHeight <= context.maxFallHeightBucket + 1) {
                res.x = destX;
                res.y = newY + 1; // 砸在 newY 那块上,落点是它上面
                res.z = destZ;
                res.cost = tentativeCost + context.placeBucketCost();
                return true; // 需要执行期放水桶
            } else {
                return false;
            }
        }
    }

    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        cost(context, src.getX(), src.getY(), src.getZ(), dest.getX(), dest.getZ(), result);
        if (result.y != dest.getY()) {
            return COST_INF; // 落点更深,该位置属于坠落而非下降
        }
        return result.cost;
    }

    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest.above(), dest);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        BlockPos feet = feet(player);
        BlockPos fakeDest = new BlockPos(dest.getX() * 2 - src.getX(), dest.getY(),
                dest.getZ() * 2 - src.getZ());
        if ((feet.equals(dest) || feet.equals(fakeDest))
                && (MovementHelper.isLiquid(player.level().getBlockState(dest))
                        || player.getY() - dest.getY() < 0.5)) {
            // 等真正落地再报成功,否则下一动作接飞
            return state.setStatus(MovementStatus.SUCCESS);
        }
        if (safeMode()) {
            // 稳走:瞄 src→dest 的 0.83 处,不看满 dest,防冲过
            double aimX = (src.getX() + 0.5) * 0.17 + (dest.getX() + 0.5) * 0.83;
            double aimZ = (src.getZ() + 0.5) * 0.17 + (dest.getZ() + 0.5) * 0.83;
            float yaw = MovementHelper.yawTo(player.getEyePosition(),
                    new Vec3(aimX, dest.getY(), aimZ));
            state.setTarget(new MovementState.MovementTarget(yaw, player.getXRot(), false))
                    .setInput(Input.MOVE_FORWARD, true);
            return state;
        }
        double diffX = player.getX() - (dest.getX() + 0.5);
        double diffZ = player.getZ() - (dest.getZ() + 0.5);
        double ab = Math.sqrt(diffX * diffX + diffZ * diffZ);
        double x = player.getX() - (src.getX() + 0.5);
        double z = player.getZ() - (src.getZ() + 0.5);
        double fromStart = Math.sqrt(x * x + z * z);
        if (!feet.equals(dest) || ab > 0.25) {
            if (numTicks++ < 20 && fromStart < 1.25) {
                // 前 20 tick 冲过冲点加速离沿
                MovementHelper.moveTowards(player, state, fakeDest);
            } else {
                MovementHelper.moveTowards(player, state, dest);
            }
        }
        return state;
    }

    /**
     * 是否需要稳走:被执行层强制;或落点前方一格"下不可穿、上两格可穿"
     * (直冲会卡进去);或前方三格身位有危险格。
     */
    public boolean safeMode() {
        if (forceSafeMode) {
            return true;
        }
        BlockPos into = destOvershoot();
        if (skipToAscend()) {
            return true;
        }
        for (int i = 0; i <= 2; i++) {
            if (MovementHelper.avoidWalkingInto(player.level().getBlockState(into.above(i)))) {
                return true;
            }
        }
        return false;
    }

    /** 冲过头会不会正好嵌进"下一格是墙、上两格是洞"的台阶口。 */
    public boolean skipToAscend() {
        BlockPos into = destOvershoot();
        Level level = player.level();
        return !MovementHelper.canWalkThrough(level, into)
                && MovementHelper.canWalkThrough(level, into.above())
                && MovementHelper.canWalkThrough(level, into.above(2));
    }

    /** dest 再沿同方向一格(疾跑冲过时会撞到的柱)。 */
    private BlockPos destOvershoot() {
        int dx = dest.getX() - src.getX();
        int dz = dest.getZ() - src.getZ();
        return dest.offset(dx, 0, dz);
    }
}
