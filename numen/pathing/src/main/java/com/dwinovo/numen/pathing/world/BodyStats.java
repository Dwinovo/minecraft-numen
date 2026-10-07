package com.dwinovo.numen.pathing.world;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;

/**
 * 身体的几项物理量:站立与潜行时的碰撞盒和眼高、迈步高度、起跳力度、重力、方块交互距离,以及脚上的装备让它能不能
 * 站在细雪上、能不能踩着冻住的水面走。第 0 层只从这里读身体,不接触实体——宿主从真实的身体上取值交进来(尺寸取 {@code getDimensions(pose)},
 * 其余取同名属性),规划与执行拿到的是同一份。
 *
 * <p>交互距离由调用方给:原版生存模式 4.5、创造模式 5,各随属性与修饰符变。
 *
 * @param standing     站立的尺寸(原版玩家宽 0.6、高 1.8、眼高 1.62)
 * @param crouching    潜行的尺寸(原版玩家宽 0.6、高 1.5、眼高 1.27)
 * @param stepHeight   不跳就能走上去的高度(属性 {@code step_height},原版 0.6)
 * @param jumpStrength 起跳的初速度(属性 {@code jump_strength},原版 0.42)
 * @param gravity      每刻的重力加速度(属性 {@code gravity},原版 0.08)
 * @param blockReach   方块交互距离(属性 {@code block_interaction_range})
 * @param walksOnPowderSnow 细雪托得住它:原版 {@code PowderSnowBlock.canEntityWalkOnPowderSnow},玩家看脚上是不是皮靴
 * @param frostWalker       脚上的靴子带冰霜行者:走到静水边上,水面冻成冰,踩着走过去
 */
public record BodyStats(EntityDimensions standing, EntityDimensions crouching, double stepHeight,
                        double jumpStrength, double gravity, double blockReach, boolean walksOnPowderSnow,
                        boolean frostWalker) {

    /** 原版每刻对竖直速度乘的空气阻力({@code LivingEntity.travel} 里的 {@code 0.98F})。 */
    private static final double AIR_DRAG = 0.98F;

    public BodyStats {
        if (standing.width() != crouching.width()) {
            // 第 0 层按一个宽度算脚下和两侧:站立与潜行的宽度不同,落脚与净空会各算各的,这里先拦下
            throw new IllegalArgumentException("站立与潜行的宽度不同:" + standing.width() + " / " + crouching.width());
        }
    }

    /** 碰撞盒的宽。 */
    public double width() {
        return standing.width();
    }

    /** 这个姿势下碰撞盒的高。只认站立与潜行,寻路不用别的姿势。 */
    public double height(Pose pose) {
        return dimensions(pose).height();
    }

    /** 这个姿势下眼睛离脚底的高度。 */
    public double eyeHeight(Pose pose) {
        return dimensions(pose).eyeHeight();
    }

    private EntityDimensions dimensions(Pose pose) {
        return switch (pose) {
            case STANDING -> standing;
            case CROUCHING -> crouching;
            default -> throw new IllegalArgumentException("寻路只用站立与潜行两种姿势,不认 " + pose);
        };
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
