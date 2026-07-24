package com.dwinovo.numen.core.pathing.moves.movements;

import java.util.HashSet;
import java.util.Set;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/** 坠落 ≥2 格:走出边缘垂直下落到落点,超过安全高度时空中放水桶接底。 */
public class MovementFall extends Movement {

    public MovementFall(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest, buildPositionsToBreak(src, dest));
    }

    /** 成本复用下降原语的坠落分档;落点不符则本实例不适用。 */
    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        MovementDescend.cost(context, src.getX(), src.getY(), src.getZ(),
                dest.getX(), dest.getZ(), result);
        if (result.y != dest.getY()) {
            return COST_INF; // 该位置属于下降而非坠落
        }
        return result.cost;
    }

    /** src 加上落点上方整列(下落全程身体都会经过)。 */
    @Override
    protected Set<BlockPos> calculateValidPositions() {
        Set<BlockPos> set = new HashSet<>();
        set.add(src);
        for (int y = src.getY() - dest.getY(); y >= 0; y--) {
            set.add(dest.above(y));
        }
        return set;
    }

    /** 重算坠落分档:本次坠落是否需要空中放水桶。 */
    private boolean willPlaceBucket() {
        CalculationContext context = new CalculationContext(player, player.level(),
                ChunkLoadedTest.ALWAYS, false);
        MutableMoveResult result = new MutableMoveResult();
        return MovementDescend.dynamicFallCost(context, src.getX(), src.getY(), src.getZ(),
                dest.getX(), dest.getZ(), 0,
                context.get(dest.getX(), src.getY() - 2, dest.getZ()), result);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        Level level = player.level();
        BlockPos feet = feet(player);
        Vec3 eye = player.getEyePosition();
        Vec3 destCenter = MovementHelper.blockCenter(dest);
        float toDestYaw = MovementHelper.yawTo(eye, destCenter);
        boolean forcedRotation = false;
        BlockState destState = level.getBlockState(dest);
        boolean isWater = destState.getFluidState().getType() instanceof WaterFluid;
        if (!isWater && willPlaceBucket() && !feet.equals(dest)) {
            int bucketSlot = hotbarSlotWith(Items.WATER_BUCKET);
            if (bucketSlot == -1 || level.dimension() == Level.NETHER) {
                return state.setStatus(MovementStatus.UNREACHABLE);
            }
            if (player.getY() - dest.getY() < NavSettings.get().blockReachDistance
                    && !player.onGround()) {
                // 够得着落点了:切水桶、竖直向下瞄,命中落点即放水
                player.getInventory().setSelectedSlot(bucketSlot);
                state.setTarget(new MovementState.MovementTarget(toDestYaw, 90.0f, true));
                forcedRotation = true;
                if (MovementPlacement.isLookingAt(player, dest)
                        || MovementPlacement.isLookingAt(player, dest.below())) {
                    state.setInput(Input.CLICK_RIGHT, true);
                }
            }
        }
        if (!forcedRotation) {
            state.setTarget(new MovementState.MovementTarget(toDestYaw,
                    MovementHelper.pitchTo(eye, destCenter), false));
        }
        if (feet.equals(dest) && (player.getY() - feet.getY() < 0.094 || isWater)) { // 睡莲容差
            if (isWater) {
                // 落进自己放的水:收水再走
                int emptySlot = hotbarSlotWith(Items.BUCKET);
                if (emptySlot != -1) {
                    player.getInventory().setSelectedSlot(emptySlot);
                    if (player.getDeltaMovement().y >= 0) {
                        return state.setInput(Input.CLICK_RIGHT, true);
                    }
                    return state; // 还在下沉,等浮上来再收
                } else {
                    if (player.getDeltaMovement().y >= 0) {
                        return state.setStatus(MovementStatus.SUCCESS);
                    }
                    // 下沉中不提前返回:水面下可能有暗流,继续对中
                }
            } else {
                return state.setStatus(MovementStatus.SUCCESS);
            }
        }
        // 空中对中:预测下 tick 位置偏出 0.1 就往中心挤,竖速大时按潜行刹车
        if (Math.abs(player.getX() + player.getDeltaMovement().x - destCenter.x) > 0.1
                || Math.abs(player.getZ() + player.getDeltaMovement().z - destCenter.z) > 0.1) {
            if (!player.onGround() && Math.abs(player.getDeltaMovement().y) > 0.4) {
                state.setInput(Input.SNEAK, true);
            }
            state.setInput(Input.MOVE_FORWARD, true);
        }
        // 梯子回避:下方有梯子时把瞄点往梯子朝向偏,免得挂上去
        Direction avoidDir = avoid();
        Vec3i avoid = avoidDir == null ? null : avoidDir.getUnitVec3i();
        if (avoid == null) {
            avoid = src.subtract(dest);
        } else {
            double dist = Math.abs(avoid.getX() * (destCenter.x - avoid.getX() / 2.0 - player.getX()))
                    + Math.abs(avoid.getZ() * (destCenter.z - avoid.getZ() / 2.0 - player.getZ()));
            if (dist < 0.6) {
                state.setInput(Input.MOVE_FORWARD, true);
            } else if (!player.onGround()) {
                state.setInput(Input.SNEAK, false);
            }
        }
        if (!forcedRotation) {
            Vec3 destCenterOffset = new Vec3(destCenter.x + 0.125 * avoid.getX(),
                    destCenter.y, destCenter.z + 0.125 * avoid.getZ());
            state.setTarget(new MovementState.MovementTarget(
                    MovementHelper.yawTo(eye, destCenterOffset),
                    MovementHelper.pitchTo(eye, destCenterOffset), false));
        }
        return state;
    }

    /** 脚下 15 格内的第一段梯子的朝向(回避向量)。 */
    private Direction avoid() {
        for (int i = 0; i < 15; i++) {
            BlockState state = player.level().getBlockState(feet(player).below(i));
            if (state.getBlock() == Blocks.LADDER) {
                return state.getValue(LadderBlock.FACING);
            }
        }
        return null;
    }

    /** 还没走出边缘(或还在准备期)才可取消;空中动量收不回来。 */
    @Override
    protected boolean safeToCancel(MovementState state) {
        return feet(player).equals(src) || state.getStatus() != MovementStatus.RUNNING;
    }

    /** 快捷栏里找(物品与组件都相同的)指定物品槽位,找不到返回 -1。 */
    private int hotbarSlotWith(Item item) {
        int slot = player.getInventory().findSlotMatchingItem(new net.minecraft.world.item.ItemStack(item));
        return net.minecraft.world.entity.player.Inventory.isHotbarSlot(slot) ? slot : -1;
    }

    /** 从 src.above() 到 dest 的整列(高 diffY+2)。 */
    private static BlockPos[] buildPositionsToBreak(BlockPos src, BlockPos dest) {
        int diffY = Math.abs(src.getY() - dest.getY());
        BlockPos[] toBreak = new BlockPos[diffY + 2];
        for (int i = 0; i < toBreak.length; i++) {
            toBreak[i] = new BlockPos(dest.getX(), src.getY() + 1 - i, dest.getZ());
        }
        return toBreak;
    }

    /** 只有顶端四格需要挖时才走通用挖掘;更深处(可能是水)忽略。 */
    @Override
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        for (int i = 0; i < 4 && i < positionsToBreak.length; i++) {
            if (!MovementHelper.canWalkThrough(player.level(), positionsToBreak[i])) {
                return super.prepared(state);
            }
        }
        return true;
    }
}
