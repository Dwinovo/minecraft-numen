package com.dwinovo.numen.core.pathing.moves.movements;

import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 移动原语执行期的放置与视线共用逻辑:五贴面枚举、贴面中心瞄点、
 * 目标转角射线校验、耗材选取、视线命中判定。
 *
 * <p>视角推进目前采用"目标转角直接命中"的简化模型:假定下一 tick
 * 头就能转到目标转角,直接沿目标转角做射线校验。带 currentYaw /
 * currentPitch 参数的重载是留给后续接入真实转头节奏的接缝——届时
 * 把预测转角传进来即可,判定逻辑不变。
 */
final class MovementPlacement {

    private MovementPlacement() {}

    /** 一次放置尝试的结论。 */
    enum PlaceResult {
        /** 视线已对准正确贴面,本 tick 可右键。 */
        READY_TO_PLACE,
        /** 找到可行贴面,已把目标转角写进 state,等转头。 */
        ATTEMPTING,
        /** 五个贴面都不可行。 */
        NO_OPTION
    }

    /** 放置贴面枚举顺序:四个水平向在前,DOWN 最后(不含 UP)。 */
    static final Direction[] HORIZONTALS_AND_DOWN = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.DOWN
    };

    /** 潜行时的眼高(米)。 */
    private static final double SNEAK_EYE_HEIGHT = 1.27;

    /** 以玩家当前视角作为"当前转角"的便捷入口。 */
    static PlaceResult attemptToPlaceABlock(MovementState state, ServerPlayer player,
                                            BlockPos placeAt, boolean preferDown, boolean wouldSneak) {
        return attemptToPlaceABlock(state, player, placeAt, preferDown, wouldSneak,
                player.getYRot(), player.getXRot());
    }

    /**
     * 尝试对 placeAt 找一个可行的放置贴面:先试直视 placeAt 本体
     * (可替换方块自带轮廓时能命中),再按 {@link #HORIZONTALS_AND_DOWN}
     * 枚举五个贴面,要求贴面方块可贴、且沿目标转角的射线命中该贴面
     * 且命中面的邻格恰为 placeAt。preferDown=false 取第一个可行
     * (水平优先),true 取最后一个(DOWN 优先,空中放置不必歪头)。
     *
     * <p>当前转角已命中正确目标 → READY_TO_PLACE(右键由调用方按);
     * 找到贴面但没对准 → ATTEMPTING;找不到 → NO_OPTION。
     * 没有可垫路耗材时置 UNREACHABLE 并返回 NO_OPTION。
     */
    static PlaceResult attemptToPlaceABlock(MovementState state, ServerPlayer player,
                                            BlockPos placeAt, boolean preferDown, boolean wouldSneak,
                                            float currentYaw, float currentPitch) {
        Level level = player.level();
        double reach = NavSettings.get().blockReachDistance;
        Vec3 eye = eyePosition(player, wouldSneak);
        boolean found = false;

        // 直视 placeAt 本体(走到这一步说明该格必是可替换的)
        Vec3 placeCenter = MovementHelper.blockCenter(placeAt);
        float directYaw = MovementHelper.yawTo(eye, placeCenter);
        float directPitch = MovementHelper.pitchTo(eye, placeCenter);
        BlockHitResult directHit = rayTrace(player, eye, directYaw, directPitch, reach);
        if (directHit.getType() == HitResult.Type.BLOCK && directHit.getBlockPos().equals(placeAt)) {
            state.setTarget(new MovementState.MovementTarget(directYaw, directPitch, true));
            found = true;
        }

        for (int i = 0; i < 5; i++) {
            BlockPos against = placeAt.relative(HORIZONTALS_AND_DOWN[i]);
            if (!MovementHelper.canPlaceAgainst(level, against)) {
                continue;
            }
            if (!selectThrowaway(player, false)) {
                state.setStatus(MovementStatus.UNREACHABLE);
                return PlaceResult.NO_OPTION;
            }
            // 贴面中心:两格坐标的中点,落在共享面上
            double faceX = (placeAt.getX() + against.getX() + 1.0) * 0.5;
            double faceY = (placeAt.getY() + against.getY() + 0.5) * 0.5;
            double faceZ = (placeAt.getZ() + against.getZ() + 1.0) * 0.5;
            Vec3 face = new Vec3(faceX, faceY, faceZ);
            float yaw = MovementHelper.yawTo(eye, face);
            float pitch = MovementHelper.pitchTo(eye, face);
            // 视角推进简化:直接沿目标转角做射线,要求命中贴面方块且命中面邻格 == placeAt
            BlockHitResult hit = rayTrace(player, eye, yaw, pitch, reach);
            if (hit.getType() == HitResult.Type.BLOCK
                    && hit.getBlockPos().equals(against)
                    && hit.getBlockPos().relative(hit.getDirection()).equals(placeAt)) {
                state.setTarget(new MovementState.MovementTarget(yaw, pitch, true));
                found = true;
                if (!preferDown) {
                    break; // 水平优先:第一个可行即取
                }
            }
        }

        // 当前转角已经命中正确目标 → 就绪
        BlockHitResult looking = rayTrace(player, eyePosition(player, false), currentYaw, currentPitch, reach);
        if (looking.getType() == HitResult.Type.BLOCK) {
            BlockPos selected = looking.getBlockPos();
            if (selected.equals(placeAt)
                    || (MovementHelper.canPlaceAgainst(level, selected)
                            && selected.relative(looking.getDirection()).equals(placeAt))) {
                if (wouldSneak) {
                    state.setInput(Input.SNEAK, true);
                }
                selectThrowaway(player, true);
                return PlaceResult.READY_TO_PLACE;
            }
        }
        if (found) {
            if (wouldSneak) {
                state.setInput(Input.SNEAK, true);
            }
            selectThrowaway(player, true);
            return PlaceResult.ATTEMPTING;
        }
        return PlaceResult.NO_OPTION;
    }

    /**
     * 快捷栏里找可垫路耗材;select=true 时切到该槽位
     * (背包数据写入,后续执行层波次统一接管换手动作)。
     */
    static boolean selectThrowaway(ServerPlayer player, boolean select) {
        var acceptable = NavSettings.get().acceptableThrowawayItems();
        Inventory inventory = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && acceptable.contains(stack.getItem())) {
                if (select) {
                    inventory.selected = i;
                }
                return true;
            }
        }
        return false;
    }

    /** 玩家当前视线是否命中该方块(轮廓射线,不穿流体)。 */
    static boolean isLookingAt(ServerPlayer player, BlockPos pos) {
        BlockHitResult hit = rayTrace(player, player.getEyePosition(),
                player.getYRot(), player.getXRot(), NavSettings.get().blockReachDistance);
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /** 玩家当前视角是否已对准 state 中的目标转角(容差 0.01°)。 */
    static boolean isFacing(ServerPlayer player, MovementState.MovementTarget target) {
        if (!target.hasRotation()) {
            return false;
        }
        return Math.abs(normalizeDegrees(player.getYRot() - target.getYaw())) < 0.01
                && Math.abs(player.getXRot() - target.getPitch()) < 0.01;
    }

    /** 角度归一到 [-180, 180)。 */
    static float normalizeDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        return wrapped;
    }

    /** 执行期霜行者判定:装备有霜行者且目标格是静水源。 */
    static boolean canUseFrostWalker(ServerPlayer player, BlockState state) {
        return frostWalkerLevel(player) != 0
                && state.getBlock() == Blocks.WATER
                && state.getValue(LiquidBlock.LEVEL) == 0;
    }

    /** 全身装备的霜行者附魔最高等级。 */
    static int frostWalkerLevel(ServerPlayer player) {
        int level = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemEnchantments itemEnchantments = player.getItemBySlot(slot).getEnchantments();
            for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                if (enchant.is(Enchantments.FROST_WALKER)) {
                    level = Math.max(level, itemEnchantments.getLevel(enchant));
                }
            }
        }
        return level;
    }

    /** 沿指定转角从 eye 出发的轮廓射线(不含流体)。 */
    static BlockHitResult rayTrace(ServerPlayer player, Vec3 eye, float yaw, float pitch, double reach) {
        Vec3 end = eye.add(direction(yaw, pitch).scale(reach));
        return player.level().clip(new ClipContext(eye, end,
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
    }

    /** 转角 → 单位视线向量。 */
    static Vec3 direction(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(-Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    /** 眼位;wouldSneak 时按潜行眼高取(提前用放置那一刻的视角算贴面)。 */
    static Vec3 eyePosition(ServerPlayer player, boolean wouldSneak) {
        if (wouldSneak) {
            return new Vec3(player.getX(), player.getY() + SNEAK_EYE_HEIGHT, player.getZ());
        }
        return player.getEyePosition();
    }
}
