package com.dwinovo.numen.pathing.world;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;

/**
 * 身体的几项物理量:站立时的碰撞盒和眼高、迈步高度、起跳力度、重力、方块交互距离,以及脚上的装备让它能不能
 * 站在细雪上、能不能踩着冻住的水面走。第 0 层只从这里读身体,不接触实体——宿主从真实的身体上取值交进来(尺寸取 {@code getDimensions(pose)},
 * 其余取同名属性),规划与执行拿到的是同一份。
 *
 * <p>交互距离由调用方给:原版生存模式 4.5、创造模式 5,各随属性与修饰符变。
 *
 * @param standing     站立的尺寸(原版玩家宽 0.6、高 1.8、眼高 1.62)
 * @param stepHeight   不跳就能走上去的高度(属性 {@code step_height},原版 0.6)
 * @param jumpStrength 起跳的初速度(属性 {@code jump_strength},原版 0.42)
 * @param gravity      每刻的重力加速度(属性 {@code gravity},原版 0.08)
 * @param blockReach   方块交互距离(属性 {@code block_interaction_range})
 * @param walksOnPowderSnow 细雪托得住它:原版 {@code PowderSnowBlock.canEntityWalkOnPowderSnow},玩家看脚上是不是皮靴
 * @param frostWalker       脚上的靴子带冰霜行者:走到静水边上,水面冻成冰,踩着走过去
 */
public record BodyStats(EntityDimensions standing, double stepHeight,
                        double jumpStrength, double gravity, double blockReach, boolean walksOnPowderSnow,
                        boolean frostWalker) {

    /** 原版每刻对竖直速度乘的空气阻力({@code LivingEntity.travel} 里的 {@code 0.98F})。 */
    private static final double AIR_DRAG = 0.98F;

    /** 碰撞盒的宽。 */
    public double width() {
        return standing.width();
    }

    /** 站着时碰撞盒的高。 */
    public double height() {
        return standing.height();
    }

    /** 站着时眼睛离脚底的高度。 */
    public double eyeHeight() {
        return standing.eyeHeight();
    }

    /** 站在 {@code (x, z)} 这一列、脚在 {@code feetY} 时眼睛的位置(列中心)。 */
    public Vec3 eye(int x, double feetY, int z) {
        return new Vec3(x + 0.5, feetY + eyeHeight(), z + 0.5);
    }

    /**
     * 从地面起跳,脚能升到的最高处(相对起跳时的脚)。照原版逐刻积分:起跳速度是起跳力度乘脚下方块的起跳系数
     * ({@code LivingEntity.getJumpPower}),之后每刻先按当前速度移动,再减重力、乘空气阻力,速度不再为正时到顶。
     * 原版玩家在普通方块上约 1.252,蜂蜜块上只有一半的力度。
     *
     * @param blockJumpFactor 脚下方块的起跳系数,见 {@link Semantics#jumpFactor}
     */
    public double jumpHeight(double blockJumpFactor) {
        double velocity = jumpStrength * blockJumpFactor;
        double rise = 0;
        while (velocity > 0) {
            rise += velocity;
            velocity = (velocity - gravity) * AIR_DRAG;
        }
        return rise;
    }
}
