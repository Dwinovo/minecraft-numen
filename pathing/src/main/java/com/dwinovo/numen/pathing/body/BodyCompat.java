package com.dwinovo.numen.pathing.body;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 这一代(1.20.2)的原版里,身体的交互距离、潜行速度、重力不是属性,而是写在各处的常数与公式。寻路的其余代码只问身体"够得多远、
 * 蹲着走多快、重力多大",答案统一从这里取:数值与后来的属性默认值一致,算法照这一代原版。
 */
public final class BodyCompat {

    /** 起跳的初速度(未算脚下方块的起跳系数)。 */
    public static final double JUMP_STRENGTH = 0.42;

    /** 玩家不跳就能迈上去的高度,后来的属性 {@code step_height} 的默认值。 */
    private static final float STEP_HEIGHT = 0.6F;

    private BodyCompat() {}

    /**
     * 让服务端的身体像真玩家那样走路。这一代的 {@code ServerPlayer} 构造时把迈步高度设成 1.0(为了迁就客户端的自动起跳),
     * 一格高的门板、台阶都被它直接迈上去,不跳也不开门;真玩家在客户端上是 0.6,后来的版本里服务端玩家也是 0.6。
     * 寻路(规划与执行读同一个迈步高度)与身体的物理都照 0.6。
     */
    public static void walkLikeAPlayer(ServerPlayer body) {
        body.setMaxUpStep(STEP_HEIGHT);
    }

    /** 方块的交互距离:生存 4.5,创造 5(客户端拾取 {@code getPickRange})。 */
    public static double blockReach(ServerPlayer body) {
        return body.getAbilities().instabuild ? 5.0 : 4.5;
    }

    /** 实体的交互距离:生存 3,创造 6(客户端 {@code GameRenderer.pick})。 */
    public static double entityReach(ServerPlayer body) {
        return body.getAbilities().instabuild ? 6.0 : 3.0;
    }

    /** 够不够得着一格方块:眼睛到那一格的碰撞盒不超过方块交互距离加 {@code extra}。 */
    public static boolean canReachBlock(ServerPlayer body, BlockPos pos, double extra) {
        double range = blockReach(body) + extra;
        return new AABB(pos).distanceToSqr(body.getEyePosition()) < range * range;
    }

    /**
     * 够不够得着一只实体,照后来的 {@code Player.canInteractWithEntity}:眼睛到它的碰撞盒(外扩一个拾取半径)不超过实体交互距离加
     * {@code extra}。
     */
    public static boolean canReachEntity(ServerPlayer body, Entity target, double extra) {
        double range = entityReach(body) + extra;
        return target.getBoundingBox().inflate(target.getPickRadius()).distanceToSqr(body.getEyePosition()) < range * range;
    }

    /**
     * 服务端对"用方块"一下的距离检查,照 {@code ServerGamePacketListenerImpl.handleUseItemOn}:眼睛到那一格中心不超过 6。
     */
    public static boolean canUseBlock(ServerPlayer body, BlockPos pos) {
        return body.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= Mth.square(6.0);
    }

    /** 蹲着走时前后左右冲量的乘数:基值 0.3,迅捷潜行附魔提高(原版 {@code LocalPlayer.aiStep})。 */
    public static float sneakFactor(ServerPlayer body) {
        return Mth.clamp(0.3F + EnchantmentHelper.getSneakingSpeedBonus(body), 0.0F, 1.0F);
    }

    /** 每刻的重力加速度:0.08,缓降效果下落时 0.01,无重力为 0(原版 {@code LivingEntity.travel})。 */
    public static double gravity(ServerPlayer body) {
        if (body.isNoGravity()) {
            return 0.0;
        }
        return body.getDeltaMovement().y <= 0.0 && body.hasEffect(MobEffects.SLOW_FALLING) ? 0.01 : 0.08;
    }
}
