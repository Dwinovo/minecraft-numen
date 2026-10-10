package com.dwinovo.numen.pathing.world;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;

/**
 * 身体的几项物理量:站立时的碰撞盒和眼高、迈步高度、起跳力度、重力、移动速度、方块交互距离,以及脚上的装备让它能不能
 * 站在细雪上、能不能踩着冻住的水面走。第 0 层只从这里读身体,不接触实体——宿主从真实的身体上取值交进来(尺寸取 {@code getDimensions(pose)},
 * 其余取同名属性),规划与执行拿到的是同一份。这些量怎么变成速度与耗时,全在 {@link Kinematics}。
 *
 * <p>交互距离由调用方给:原版生存模式 4.5、创造模式 5,各随属性与修饰符变。
 *
 * @param standing     站立的尺寸(原版玩家宽 0.6、高 1.8、眼高 1.62)
 * @param stepHeight   不跳就能走上去的高度(属性 {@code step_height},原版 0.6)
 * @param jumpStrength 起跳的初速度(属性 {@code jump_strength},原版 0.42)
 * @param gravity      每刻的重力加速度(属性 {@code gravity},原版 0.08)
 * @param movementSpeed 平走的移动速度(属性 {@code movement_speed},不含疾跑的加成,原版 0.1)
 * @param sneakingSpeed 潜行时移动输入乘的倍数(属性 {@code sneaking_speed},原版 0.3)
 * @param blockReach   方块交互距离(属性 {@code block_interaction_range})
 * @param walksOnPowderSnow 细雪托得住它:原版 {@code PowderSnowBlock.canEntityWalkOnPowderSnow},玩家看脚上是不是皮靴
 * @param frostWalker       脚上的靴子带冰霜行者:走到静水边上,水面冻成冰,踩着走过去
 */
public record BodyStats(EntityDimensions standing, double stepHeight,
                        double jumpStrength, double gravity, double movementSpeed, double sneakingSpeed, double blockReach, boolean walksOnPowderSnow,
                        boolean frostWalker) {

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
}
